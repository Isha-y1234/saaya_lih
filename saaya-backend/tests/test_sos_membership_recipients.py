from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from bson import ObjectId
from firebase_admin import messaging

from app.notifications import SosNotifier, resolve_recipients


@pytest.fixture
def pair(client, database, sign_in, monkeypatch):
    owner, sender, _ = sign_in("sender")
    recipient, receiver, account = sign_in("recipient")
    contact = client.post("/contacts", headers=sender, json={"ownerId": owner, "name": "Friend",
        "relation": "Friend", "phone": "+919876543210", "initials": "F"}).json()
    path = f"/contacts/{contact['_id']}/invitation"
    invited = client.post(path, headers=sender, json={"recipientSaayaId": account["user"]["saayaId"]}).json()
    assert client.post("/devices/token", headers=receiver,
        json={"token": "membership-test-device-token", "notificationsEnabled": True}).status_code == 200
    document = database["trusted_contacts"].documents[0]
    messages = []
    def send(message, **kwargs):
        messages.append(message)
        return SimpleNamespace(responses=[SimpleNamespace(exception=None) for _ in message.tokens])
    monkeypatch.setattr(messaging, "send_each_for_multicast", send)
    client.app.state.sos_notifier = SosNotifier(object())
    return SimpleNamespace(owner=owner, sender=sender, recipient=recipient, receiver=receiver,
        contact=document, path=path, invited=invited, messages=messages)


def accept(client, pair):
    response = client.post(pair.path + "/accept", headers=pair.receiver,
        json={"invitationId": pair.invited["invitationId"]})
    assert response.status_code == 200
    # Avoid wall-clock/BSON same-millisecond ties in positive tests.
    pair.contact["decidedAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)


@pytest.mark.parametrize("state,eligible", [("ACCEPTED", True), ("PENDING", False),
    ("REJECTED", False), ("REVOKED", False)])
def test_only_previously_accepted_members_receive_new_sos(client, database, pair, state, eligible):
    if state == "ACCEPTED" or state == "REVOKED":
        accept(client, pair)
    elif state == "REJECTED":
        assert client.post(pair.path + "/reject", headers=pair.receiver,
            json={"invitationId": pair.invited["invitationId"]}).status_code == 200
    pair.contact["invitationStatus"] = state
    incident = client.post("/incidents", headers=pair.sender, json={}).json()
    assert len(pair.messages) == int(eligible)
    snapshots = database["incident_notification_recipients"].documents
    assert len(snapshots) == int(eligible)
    response = client.get(f"/incident-alerts/{incident['id']}", headers=pair.receiver)
    assert response.status_code == (200 if eligible else 404)
    assert database["notification_links"].documents == []
    if eligible:
        snapshot = snapshots[0]
        assert snapshot["contactIds"] == []  # No implicit legacy link/grant.
        assert snapshot["memberships"] == [{"contactId": pair.contact["_id"],
            "invitationId": pair.invited["invitationId"], "decidedAt": pair.contact["decidedAt"]}]
        message = pair.messages[0]
        assert message.data["type"] == "SOS"
        assert message.data["incidentId"] == incident["id"]
        assert message.data["recipientId"] == pair.recipient
        assert message.tokens == ["membership-test-device-token"]
        assert message.android.priority == "high" and message.notification is None
        assert client.post("/incidents", headers=pair.sender, json={}).status_code == 200
        assert len(pair.messages) == 1


def test_late_acceptance_cannot_grant_existing_incident_even_if_sender_is_delayed(client, database, pair):
    incident = client.post("/incidents", headers=pair.sender, json={}).json()
    stored = database["incidents"].find_one({"_id": ObjectId(incident["id"])})
    assert client.post(pair.path + "/accept", headers=pair.receiver,
        json={"invitationId": pair.invited["invitationId"]}).status_code == 200
    pair.contact["decidedAt"] = stored["startedAt"] + timedelta(seconds=1)
    client.app.state.sos_notifier.send(database, stored)
    assert pair.messages == []
    assert database["incident_notification_recipients"].documents == []
    assert client.get(f"/incident-alerts/{incident['id']}", headers=pair.receiver).status_code == 404
    assert client.post("/incidents", headers=pair.sender, json={}).status_code == 200
    assert pair.messages == []
    client.patch(f"/incidents/{incident['id']}/end", headers=pair.sender)
    pair.contact["decidedAt"] = datetime.now(timezone.utc) - timedelta(milliseconds=1)
    new_incident = client.post("/incidents", headers=pair.sender, json={}).json()
    assert new_incident["id"] != incident["id"]
    assert len(pair.messages) == 1
    assert client.get(f"/incident-alerts/{new_incident['id']}", headers=pair.receiver).status_code == 200
    assert client.get(f"/incident-alerts/{incident['id']}", headers=pair.receiver).status_code == 404


@pytest.mark.parametrize("delta,eligible", [(-1000, True), (0, False), (100, False), (1000, False)])
def test_mongo_millisecond_boundary_fails_closed(database, client, pair, delta, eligible):
    pair.contact["invitationStatus"] = "ACCEPTED"
    start = datetime(2026, 11, 13, 12, 0, 0, 500900, tzinfo=timezone.utc)
    pair.contact["decidedAt"] = start + timedelta(microseconds=delta)
    assert (pair.recipient in resolve_recipients(database, pair.owner, start)) == eligible


@pytest.mark.parametrize("missing", ["decidedAt", "invitationId", "invitedRecipientId"])
def test_incomplete_membership_never_guesses_identity_or_acceptance(database, client, pair, missing):
    accept(client, pair)
    pair.contact.pop(missing)
    assert resolve_recipients(database, pair.owner, datetime.now(timezone.utc)) == {}


@pytest.mark.parametrize("change", ["deleted", "revoked", "reinvited", "retargeted"])
def test_snapshot_requires_current_matching_membership(client, database, pair, change):
    accept(client, pair)
    incident = client.post("/incidents", headers=pair.sender, json={}).json()
    path = f"/incident-alerts/{incident['id']}"
    assert client.get(path, headers=pair.receiver).status_code == 200
    if change == "deleted":
        assert client.delete(f"/contacts/{pair.contact['_id']}", headers=pair.sender).status_code == 204
    elif change == "revoked":
        pair.contact["invitationStatus"] = "REVOKED"
    elif change == "reinvited":
        pair.contact["invitationId"] = "new-invitation-generation"
    else:
        pair.contact["invitedRecipientId"] = str(ObjectId())
    assert client.get(path, headers=pair.receiver).status_code == 404


def test_membership_needs_snapshot_and_never_grants_history_or_shared_features(client, database, pair):
    accept(client, pair)
    incident = client.post("/incidents", headers=pair.sender, json={}).json()
    id = incident["id"]
    for path in (f"/incidents/{id}", f"/incidents/{id}/evidence",
                 f"/incidents/{id}/analysis/jobs", f"/incidents/{id}/reports"):
        assert client.get(path, headers=pair.receiver).status_code == 404, path
    assert client.get(f"/incidents/{id}/chat/messages", headers=pair.receiver).status_code == 200
    assert client.get(f"/incidents/{id}/live", headers=pair.receiver).status_code == 200
    assert client.get("/live-safety/incidents", headers=pair.receiver).json()["incidents"][0]["id"] == id
    # Media is disabled in this fixture, but membership authorization succeeds.
    assert client.post(f"/incidents/{id}/live/join", headers=pair.receiver).status_code == 503
    assert client.patch(f"/incidents/{id}/end", headers=pair.receiver).status_code == 404
    snapshots = database["incident_notification_recipients"].documents
    saved = snapshots.pop()
    assert client.get(f"/incident-alerts/{id}", headers=pair.receiver).status_code == 404
    snapshots.append(saved)
    assert client.get(f"/incident-alerts/{id}", headers=pair.receiver).status_code == 200
    assert client.patch(f"/incidents/{id}/end", headers=pair.sender).status_code == 200
    assert client.get(f"/incident-alerts/{id}", headers=pair.receiver).status_code == 404


def test_legacy_link_stays_separate_and_overlapping_sources_send_once(client, database, pair):
    code = client.get("/notification-links/code", headers=pair.receiver).json()["recipientCode"]
    assert client.post("/notification-links", headers=pair.sender,
        json={"contactId": str(pair.contact["_id"]), "recipientCode": code}).status_code == 200
    # An existing link works as before but does not turn PENDING into ACCEPTED.
    assert pair.contact["invitationStatus"] == "PENDING"
    first = client.post("/incidents", headers=pair.sender, json={}).json()
    snapshot = database["incident_notification_recipients"].documents[0]
    assert snapshot["contactIds"] == [pair.contact["_id"]]
    assert snapshot["memberships"] == []
    client.patch(f"/incidents/{first['id']}/end", headers=pair.sender)
    accept(client, pair)
    second = client.post("/incidents", headers=pair.sender, json={}).json()
    assert len(pair.messages) == 2  # One send per incident, not per eligibility source.
    snapshot = database["incident_notification_recipients"].find_one({"incidentId": ObjectId(second["id"])})
    assert len(snapshot["memberships"]) == 1 and len(snapshot["contactIds"]) == 1


@pytest.mark.parametrize("mode", ["disabled_token", "expired_session", "fcm_failure", "fcm_disabled"])
def test_membership_preserves_device_filtering_and_sos_failure_isolation(client, database, pair, monkeypatch, mode):
    accept(client, pair)
    if mode == "disabled_token":
        database["device_tokens"].documents[0]["notificationsEnabled"] = False
    elif mode == "expired_session":
        database["auth_sessions"].documents[-1]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    elif mode == "fcm_failure":
        def fail(*args, **kwargs):
            raise RuntimeError("FCM unavailable")
        monkeypatch.setattr(messaging, "send_each_for_multicast", fail)
    else:
        client.app.state.sos_notifier = SosNotifier(None)
    response = client.post("/incidents", headers=pair.sender, json={})
    assert response.status_code == 201 and response.json()["status"] == "ACTIVE"
    assert pair.messages == []
