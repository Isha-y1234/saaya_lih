from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from bson import BSON, ObjectId
from firebase_admin import messaging
from firebase_admin.exceptions import InvalidArgumentError

from app.auth import hash_session_token
from app.notifications import SosNotifier, initialize_fcm, resolve_recipients


TOKEN = "test-fcm-token-000000000001"


def register(client, headers, token=TOKEN, enabled=True):
    return client.post("/devices/token", headers=headers, json={"token": token, "notificationsEnabled": enabled})


def setup_pair(client, database, sign_in):
    owner, owner_headers, _ = sign_in("sender")
    recipient, recipient_headers, session = sign_in("recipient")
    contact = client.post("/contacts", headers=owner_headers, json={
        "ownerId": owner, "name": "Trusted recipient", "relation": "Friend", "phone": "+919876543210", "initials": "TR"
    }).json()
    code = client.get("/notification-links/code", headers=recipient_headers).json()["recipientCode"]
    link = client.post("/notification-links", headers=owner_headers, json={"contactId": contact["_id"], "recipientCode": code})
    assert link.status_code == 200
    assert register(client, recipient_headers).status_code == 200
    return owner, owner_headers, recipient, recipient_headers, session, contact


def test_token_ownership_dedup_transfer_and_old_session_removal(client, database, sign_in):
    user_a, headers_a, session_a = sign_in("a")
    assert register(client, headers_a).status_code == 200
    assert register(client, headers_a).status_code == 200
    assert len(database["device_tokens"].documents) == 1
    assert database["device_tokens"].documents[0]["userId"] == user_a
    user_b, headers_b, _ = sign_in("b")
    assert register(client, headers_b).status_code == 200
    assert database["device_tokens"].documents[0]["userId"] == user_b
    old = client.request("DELETE", "/devices/token", headers=headers_a, json={"token": TOKEN})
    assert old.status_code == 204
    assert len(database["device_tokens"].documents) == 1
    assert register(client, headers_a).status_code == 409
    removed = client.request("DELETE", "/devices/token", headers=headers_b, json={"token": TOKEN})
    assert removed.status_code == 204 and database["device_tokens"].documents == []


def test_token_client_user_id_is_rejected_and_auth_required(client, database, sign_in):
    _, headers, _ = sign_in()
    assert client.post("/devices/token", json={"token": TOKEN}).status_code == 401
    assert client.request("DELETE", "/devices/token", json={"token": TOKEN}).status_code == 401
    assert client.post("/devices/token", headers=headers, json={"token": TOKEN, "userId": "victim"}).status_code == 422
    assert client.post("/devices/token", headers=headers, json={"token": "bad token"}).status_code == 422
    assert database["device_tokens"].documents == []


@pytest.mark.parametrize("first_binding", ["older", "newer"])
def test_bson_equal_millisecond_sessions_cannot_reassign_each_others_token(client, database, sign_in, first_binding):
    older, older_headers, _ = sign_in("older")
    newer, newer_headers, _ = sign_in("newer")
    start = datetime.now(timezone.utc).replace(microsecond=100001)
    later = start.replace(microsecond=100999)
    assert start < later
    rounded = [BSON.encode({"createdAt": value}).decode()["createdAt"].replace(tzinfo=timezone.utc)
               for value in (start, later)]
    assert rounded[0] == rounded[1]
    for session, value in zip(database["auth_sessions"].documents, rounded):
        session["createdAt"] = value
    user, headers, other_headers = ((older, older_headers, newer_headers) if first_binding == "older"
                                    else (newer, newer_headers, older_headers))
    assert register(client, headers).status_code == 200
    assert register(client, other_headers).status_code == 409
    assert register(client, headers, enabled=False).status_code == 200
    assert database["device_tokens"].documents[0]["userId"] == user
    assert database["device_tokens"].documents[0]["notificationsEnabled"] is False
    client.request("DELETE", "/devices/token", headers=other_headers, json={"token": TOKEN})
    assert len(database["device_tokens"].documents) == 1


@pytest.mark.parametrize("path,method,body", [
    ("/devices/token", "post", {"token": TOKEN, "notificationsEnabled": True}),
    ("/devices/token", "delete", {"token": TOKEN}),
    ("/notification-links", "get", None),
    ("/notification-links", "post", {"contactId": "507f1f77bcf86cd799439011", "recipientCode": "x" * 32}),
    ("/notification-links/507f1f77bcf86cd799439011", "delete", None),
    ("/incident-alerts/507f1f77bcf86cd799439011", "get", None),
])
def test_fcm_endpoints_reject_expired_and_revoked_sessions(client, database, sign_in, path, method, body):
    _, headers, _ = sign_in()
    database["auth_sessions"].documents[0]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    kwargs = {"json": body} if body is not None else {}
    assert client.request(method, path, headers=headers, **kwargs).status_code == 401
    database["auth_sessions"].documents.clear()
    assert client.request(method, path, headers=headers, **kwargs).status_code == 401
    assert client.request(method, path, **kwargs).status_code == 401


def test_another_user_cannot_unlink_an_owned_contact(client, database, sign_in):
    _, owner_headers, _, recipient_headers, _, contact = setup_pair(client, database, sign_in)
    assert client.delete(f"/notification-links/{contact['_id']}", headers=recipient_headers).status_code == 204
    assert client.get("/notification-links", headers=owner_headers).json()["contactIds"] == [contact["_id"]]


def test_recipient_cannot_read_incident_created_before_the_link(client, database, sign_in):
    _, headers, _, recipient_headers, _, _ = setup_pair(client, database, sign_in)
    client.app.state.sos_notifier = SosNotifier(None)
    incident = client.post("/incidents", headers=headers, json={}).json()
    assert client.get(f"/incident-alerts/{incident['id']}", headers=recipient_headers).status_code == 404


def test_logout_removes_only_devices_bound_to_that_session(client, database, sign_in):
    _, headers_a, _ = sign_in("a")
    _, headers_b, _ = sign_in("a")
    register(client, headers_a, TOKEN)
    other = TOKEN + "-another-device"
    register(client, headers_b, other)
    assert client.delete("/users/session", headers=headers_a).status_code == 204
    assert [d["token"] for d in database["device_tokens"].documents] == [other]


def test_recipient_codes_are_stable_and_links_require_contact_ownership(client, database, sign_in):
    owner, owner_headers, recipient, recipient_headers, _, contact = setup_pair(client, database, sign_in)
    first = client.get("/notification-links/code", headers=recipient_headers).json()
    assert len(first["recipientCode"]) == 32
    assert client.get("/notification-links/code", headers=recipient_headers).json() == first
    assert client.post("/notification-links", headers=recipient_headers,
                       json={"contactId": contact["_id"], "recipientCode": first["recipientCode"]}).status_code == 404
    assert client.post("/notification-links", headers=owner_headers,
                       json={"contactId": contact["_id"], "recipientCode": "x" * 32, "recipientId": recipient}).status_code == 422
    assert client.get("/notification-links", headers=owner_headers).json() == {"contactIds": [contact["_id"]]}
    assert client.get("/notification-links/code").status_code == 401


def test_resolution_uses_existing_contacts_explicit_links_live_sessions_and_permissions(client, database, sign_in):
    owner, headers, recipient, recipient_headers, _, contact = setup_pair(client, database, sign_in)
    assert resolve_recipients(database, owner)[recipient]["tokens"] == [TOKEN]
    register(client, recipient_headers, enabled=False)
    assert resolve_recipients(database, owner) == {}
    register(client, recipient_headers)
    database["auth_sessions"].documents[-1]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert resolve_recipients(database, owner) == {}
    database["auth_sessions"].documents[-1]["expiresAt"] = datetime.now(timezone.utc) + timedelta(days=1)
    assert client.delete(f"/contacts/{contact['_id']}", headers=headers).status_code == 204
    assert resolve_recipients(database, owner) == {}


def test_unlinked_phone_contact_does_not_guess_recipient_identity(client, database, sign_in):
    owner, headers, recipient, _, _, contact = setup_pair(client, database, sign_in)
    client.delete(f"/notification-links/{contact['_id']}", headers=headers)
    assert resolve_recipients(database, owner) == {}


def test_sos_payload_and_read_only_recipient_access(client, database, sign_in, monkeypatch):
    owner, headers, recipient, recipient_headers, _, contact = setup_pair(client, database, sign_in)
    captured = []
    client.app.state.sos_notifier = SosNotifier(object())
    def send(message, **kwargs):
        captured.append(message)
        return SimpleNamespace(responses=[SimpleNamespace(exception=None)])
    monkeypatch.setattr(messaging, "send_each_for_multicast", send)
    response = client.post("/incidents", headers=headers, json={})
    assert response.status_code == 201
    incident = response.json()
    message = captured[0]
    assert message.tokens == [TOKEN]
    assert message.data["incidentId"] == incident["id"]
    assert message.data["type"] == "SOS" and message.data["recipientId"] == recipient
    assert message.data["title"] and message.data["body"]
    assert message.notification is None and message.android.priority == "high"
    assert client.get(f"/incident-alerts/{incident['id']}", headers=recipient_headers).json() == incident
    assert client.get(f"/incidents/{incident['id']}", headers=recipient_headers).status_code == 404
    assert client.patch(f"/incidents/{incident['id']}/end", headers=recipient_headers).status_code == 404
    _, stranger_headers, _ = sign_in("stranger")
    assert client.get(f"/incident-alerts/{incident['id']}", headers=stranger_headers).status_code == 404
    assert client.post("/incidents", headers=headers, json={}).status_code == 200
    assert len(captured) == 1
    client.delete(f"/notification-links/{contact['_id']}", headers=headers)
    assert client.get(f"/incident-alerts/{incident['id']}", headers=recipient_headers).status_code == 404


def test_fcm_failure_and_missing_credentials_never_break_incident_creation(client, database, sign_in, monkeypatch):
    owner, headers, _, _, _, _ = setup_pair(client, database, sign_in)
    client.app.state.sos_notifier = SosNotifier(object())
    def unavailable(*args, **kwargs):
        raise RuntimeError("test-only sensitive details must not appear in logs")
    monkeypatch.setattr(messaging, "send_each_for_multicast", unavailable)
    response = client.post("/incidents", headers=headers, json={})
    assert response.status_code == 201 and response.json()["status"] == "ACTIVE"
    client.patch(f"/incidents/{response.json()['id']}/end", headers=headers)
    client.app.state.sos_notifier = SosNotifier(None)
    assert client.post("/incidents", headers=headers, json={}).status_code == 201


@pytest.mark.parametrize("error,removed", [
    (messaging.UnregisteredError("test-only"), True),
    (messaging.SenderIdMismatchError("test-only"), False),
    (InvalidArgumentError("test-only"), False),
])
def test_stale_or_invalid_fcm_tokens_are_removed_or_disabled(client, database, sign_in, monkeypatch, error, removed):
    _, headers, _, _, _, _ = setup_pair(client, database, sign_in)
    client.app.state.sos_notifier = SosNotifier(object())
    monkeypatch.setattr(messaging, "send_each_for_multicast",
                        lambda *a, **k: SimpleNamespace(responses=[SimpleNamespace(exception=error)]))
    assert client.post("/incidents", headers=headers, json={}).status_code == 201
    devices = database["device_tokens"].documents
    if removed:
        assert devices == []
    else:
        assert devices[0]["notificationsEnabled"] is False


def test_firebase_initialization_disabled_missing_credentials_and_reuse(monkeypatch, caplog):
    from app import notifications
    assert initialize_fcm(SimpleNamespace(firebase_service_account_file="")) is None
    existing = object()
    monkeypatch.setattr(notifications.firebase_admin, "get_app", lambda name: existing)
    assert initialize_fcm(SimpleNamespace(firebase_service_account_file="test-only.json")) is existing

    def missing(name):
        raise ValueError("missing")
    def invalid(path):
        raise RuntimeError("test-only-private-detail")
    monkeypatch.setattr(notifications.firebase_admin, "get_app", missing)
    monkeypatch.setattr(notifications.credentials, "Certificate", invalid)
    assert initialize_fcm(SimpleNamespace(firebase_service_account_file="test-only.json")) is None
    assert "test-only-private-detail" not in caplog.text
    assert "test-only.json" not in caplog.text


def test_firebase_initialization_uses_named_app_and_bounded_timeout(monkeypatch):
    from app import notifications
    def missing(name):
        raise ValueError("missing")
    credential, app = SimpleNamespace(project_id="saaya-1d484"), object()
    monkeypatch.setattr(notifications.firebase_admin, "get_app", missing)
    monkeypatch.setattr(notifications.credentials, "Certificate", lambda path: credential)
    def initialize(value, options, name):
        assert value is credential and options == {"httpTimeout": 10} and name == "saaya-fcm"
        return app
    monkeypatch.setattr(notifications.firebase_admin, "initialize_app", initialize)
    assert initialize_fcm(SimpleNamespace(firebase_service_account_file="test-only.json")) is app


def test_deleted_contact_revokes_previously_granted_incident(client, database, sign_in, monkeypatch):
    _, headers, _, recipient_headers, _, contact = setup_pair(client, database, sign_in)
    client.app.state.sos_notifier = SosNotifier(object())
    monkeypatch.setattr(messaging, "send_each_for_multicast",
                        lambda *a, **k: SimpleNamespace(responses=[SimpleNamespace(exception=None)]))
    incident = client.post("/incidents", headers=headers, json={}).json()
    assert client.get(f"/incident-alerts/{incident['id']}", headers=recipient_headers).status_code == 200
    client.delete(f"/contacts/{contact['_id']}", headers=headers)
    assert client.get(f"/incident-alerts/{incident['id']}", headers=recipient_headers).status_code == 404


def test_failed_recipient_does_not_prevent_other_delivery_or_sos(client, database, sign_in, monkeypatch):
    owner, headers, _, _, _, _ = setup_pair(client, database, sign_in)
    _, second_headers, _ = sign_in("second-recipient")
    contact = client.post("/contacts", headers=headers, json={
        "ownerId": owner, "name": "Second", "relation": "Friend", "phone": "123456", "initials": "S"
    }).json()
    code = client.get("/notification-links/code", headers=second_headers).json()["recipientCode"]
    assert client.post("/notification-links", headers=headers,
                       json={"contactId": contact["_id"], "recipientCode": code}).status_code == 200
    assert register(client, second_headers, TOKEN + "-second").status_code == 200
    client.app.state.sos_notifier = SosNotifier(object())
    attempts = []
    def send(message, **kwargs):
        attempts.append(message)
        if len(attempts) == 1:
            raise RuntimeError("test-only network failure")
        return SimpleNamespace(responses=[SimpleNamespace(exception=None)])
    monkeypatch.setattr(messaging, "send_each_for_multicast", send)
    incident = client.post("/incidents", headers=headers, json={})
    assert incident.status_code == 201 and incident.json()["status"] == "ACTIVE"
    assert len(attempts) == 2
    assert client.get(f"/incident-alerts/{incident.json()['id']}", headers=second_headers).status_code == 200
