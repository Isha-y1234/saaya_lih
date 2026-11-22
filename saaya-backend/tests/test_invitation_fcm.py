from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from firebase_admin import messaging
from firebase_admin.exceptions import InvalidArgumentError

from app.notifications import SosNotifier, notify_invitation_safely


@pytest.fixture
def pair(client, sign_in):
    owner, sender, _ = sign_in("sender")
    recipient, receiver, account = sign_in("recipient")
    contact = client.post("/contacts", headers=sender, json={
        "ownerId": owner, "name": "Private name", "relation": "Friend",
        "phone": "+919876543210", "initials": "PN"}).json()
    token = "test-invitation-device-token"
    assert client.post("/devices/token", headers=receiver, json={
        "token": token, "notificationsEnabled": True}).status_code == 200
    return SimpleNamespace(owner=owner, sender=sender, recipient=recipient, receiver=receiver,
        contact=contact, token=token, path=f"/contacts/{contact['_id']}/invitation",
        body={"recipientSaayaId": account["user"]["saayaId"]})


def delivered(message):
    return SimpleNamespace(responses=[SimpleNamespace(exception=None) for _ in message.tokens])


def test_saved_before_send_minimal_payload_and_no_duplicate(client, database, pair, monkeypatch):
    app = object()
    client.app.state.sos_notifier = SosNotifier(app)
    messages = []
    def send(message, **kwargs):
        assert kwargs == {"app": app}
        stored = database["trusted_contacts"].documents[0]
        assert stored["invitationStatus"] == "PENDING"
        assert message.data == {"type": "TRUSTED_CIRCLE_INVITATION", "invitationId": stored["invitationId"]}
        assert message.data["type"] != "SOS" and "incidentId" not in message.data
        assert message.notification is None
        assert message.android.priority == "high"
        assert message.android.ttl == timedelta(minutes=10)
        assert message.tokens == [pair.token]
        messages.append(message)
        return delivered(message)
    monkeypatch.setattr(messaging, "send_each_for_multicast", send)
    response = client.post(pair.path, headers=pair.sender, json=pair.body)
    assert response.status_code == 200
    assert client.post(pair.path, headers=pair.sender, json=pair.body).status_code == 409
    duplicate = client.post("/contacts", headers=pair.sender, json={key: pair.contact[key]
        for key in ("ownerId", "name", "relation", "phone", "initials")}).json()
    assert client.post(f"/contacts/{duplicate['_id']}/invitation", headers=pair.sender, json=pair.body).status_code == 409
    assert len(messages) == 1
    assert database["notification_links"].documents == []
    assert database["incident_notification_recipients"].documents == []
    assert database["livekit_sessions"].documents == []


@pytest.mark.parametrize("mode", ["disabled", "batch_error", "unexpected_error"])
def test_failure_preserves_pending_invitation(client, database, pair, monkeypatch, caplog, mode):
    client.app.state.sos_notifier = SosNotifier(None if mode == "disabled" else object())
    def fail(*args, **kwargs):
        raise RuntimeError("private-provider-detail")
    monkeypatch.setattr(messaging, "send_each_for_multicast", fail)
    if mode == "unexpected_error":
        monkeypatch.setattr(database["device_tokens"], "find", fail)
    response = client.post(pair.path, headers=pair.sender, json=pair.body)
    assert response.status_code == 200
    assert response.json()["invitationStatus"] == "PENDING"
    assert client.get("/contacts/invitations/incoming", headers=pair.receiver).json()["contacts"] == [response.json()]
    assert client.post(pair.path, headers=pair.sender, json=pair.body).status_code == 409
    assert "private-provider-detail" not in caplog.text
    assert pair.token not in caplog.text


@pytest.mark.parametrize("mode", ["disabled", "expired", "revoked", "other_account", "no_devices"])
def test_only_enabled_live_recipient_devices(client, database, pair, monkeypatch, mode):
    client.app.state.sos_notifier = SosNotifier(object())
    device = database["device_tokens"].documents[0]
    if mode == "disabled":
        device["notificationsEnabled"] = False
    elif mode == "expired":
        database["auth_sessions"].documents[-1]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    elif mode == "revoked":
        database["auth_sessions"].delete_one({"tokenHash": device["sessionHash"]})
    elif mode == "other_account":
        device["userId"] = pair.owner
    else:
        database["device_tokens"].documents.clear()
    sends = []
    monkeypatch.setattr(messaging, "send_each_for_multicast", lambda *a, **k: sends.append(a))
    assert client.post(pair.path, headers=pair.sender, json=pair.body).status_code == 200
    assert sends == []


@pytest.mark.parametrize("error,removed", [
    (messaging.UnregisteredError("test-only"), True),
    (messaging.SenderIdMismatchError("test-only"), False),
    (InvalidArgumentError("test-only"), False),
])
def test_existing_token_cleanup_policy(client, database, pair, monkeypatch, error, removed):
    client.app.state.sos_notifier = SosNotifier(object())
    monkeypatch.setattr(messaging, "send_each_for_multicast", lambda *a, **k:
                        SimpleNamespace(responses=[SimpleNamespace(exception=error)]))
    assert client.post(pair.path, headers=pair.sender, json=pair.body).status_code == 200
    assert database["trusted_contacts"].documents[0]["invitationStatus"] == "PENDING"
    if removed:
        assert database["device_tokens"].documents == []
    else:
        assert database["device_tokens"].documents[0]["notificationsEnabled"] is False


def test_batching_dedup_and_continue_after_failure(client, database, pair, monkeypatch):
    client.app.state.sos_notifier = SosNotifier(object())
    device = database["device_tokens"].documents[0].copy()
    database["device_tokens"].documents.extend(
        dict(device, token=f"test-extra-token-{i}") for i in range(500))
    database["device_tokens"].documents.append(device.copy())
    batches = []
    def send(message, **kwargs):
        batches.append(message.tokens)
        if len(batches) == 1:
            raise RuntimeError("network error")
        return delivered(message)
    monkeypatch.setattr(messaging, "send_each_for_multicast", send)
    assert client.post(pair.path, headers=pair.sender, json=pair.body).status_code == 200
    assert list(map(len, batches)) == [500, 1]
    assert len(set(batches[0] + batches[1])) == 501


@pytest.mark.parametrize("change", ["deleted", "answered", "replaced"])
def test_stale_queued_invitation_not_sent(client, database, pair, monkeypatch, change):
    assert client.post(pair.path, headers=pair.sender, json=pair.body).status_code == 200
    original = database["trusted_contacts"].documents[0].copy()
    if change == "deleted":
        database["trusted_contacts"].documents.clear()
    elif change == "answered":
        database["trusted_contacts"].documents[0]["invitationStatus"] = "ACCEPTED"
    else:
        database["trusted_contacts"].documents[0]["invitationId"] = "replacement"
    sends = []
    monkeypatch.setattr(messaging, "send_each_for_multicast", lambda *a, **k: sends.append(a))
    notify_invitation_safely(database, original, object())
    assert sends == []
