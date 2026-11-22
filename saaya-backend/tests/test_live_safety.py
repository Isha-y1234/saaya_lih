from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import jwt
import pytest

from app.config import Settings
from app.live_safety import LiveSafetyService

TEST_SECRET = "test-only-livekit-signing-fixture-not-a-production-key"


@pytest.fixture
def live(client, monkeypatch):
    class Rooms:
        def __init__(self):
            self.created, self.deleted, self.removed, self.updated = [], [], [], []
            self.fail = False
            self.fail_close = False
            self.room_exists = True

        async def __aenter__(self):
            return SimpleNamespace(room=self)

        async def __aexit__(self, *args):
            pass

        async def create_room(self, request):
            if self.fail:
                raise RuntimeError("test-private-sdk-error")
            self.created.append(request.name)

        async def remove_participant(self, request):
            if self.fail_close:
                raise RuntimeError("test-private-sdk-error")
            self.removed.append(request)

        async def list_rooms(self, request):
            return SimpleNamespace(rooms=[SimpleNamespace(name=name) for name in set(self.created)
                if self.room_exists and name not in self.deleted and name in request.names])

        async def update_participant(self, request):
            if self.fail:
                raise RuntimeError("test-private-sdk-error")
            self.updated.append(request)

        async def delete_room(self, request):
            self.deleted.append(request.room)

    rooms = Rooms()
    monkeypatch.setattr(LiveSafetyService, "_client", lambda self: rooms)
    client.app.state.live_safety = LiveSafetyService(Settings(
        "mongodb://localhost:27017", "test", "test.apps.googleusercontent.com",
        livekit_url="wss://test-only.livekit.cloud", livekit_api_key="test-only-api-key", livekit_api_secret=TEST_SECRET))
    return rooms


def pair(client, sign_in):
    owner, headers, _ = sign_in("live-sender")
    recipient, recipient_headers, _ = sign_in("live-viewer")
    contact = client.post("/contacts", headers=headers, json={"ownerId": owner, "name": "Viewer",
        "relation": "Friend", "phone": "1234567890", "initials": "V"}).json()
    code = client.get("/notification-links/code", headers=recipient_headers).json()["recipientCode"]
    assert client.post("/notification-links", headers=headers,
        json={"contactId": contact["_id"], "recipientCode": code}).status_code == 200
    return owner, headers, recipient_headers, contact


def decode(response):
    return jwt.decode(response.json()["token"], TEST_SECRET, algorithms=["HS256"])


def test_room_is_prepared_on_sos_resumed_and_permissions_are_minimal(client, database, sign_in, live):
    _, owner, viewer, _ = pair(client, sign_in)
    incident = client.post("/incidents", headers=owner, json={}).json()
    id = incident["id"]
    assert len(live.created) == 1
    assert client.post("/incidents", headers=owner, json={}).json()["id"] == id
    publisher = client.post(f"/incidents/{id}/live/join", headers=owner)
    subscriber = client.post(f"/incidents/{id}/live/join", headers=viewer)
    assert publisher.status_code == subscriber.status_code == 200
    assert publisher.headers["cache-control"] == "no-store"
    pub, sub = decode(publisher), decode(subscriber)
    assert pub["video"]["room"] == sub["video"]["room"] == live.created[0]
    assert len(set(live.created)) == 1
    assert pub["video"]["canPublish"] is True and pub["video"]["canSubscribe"] is True
    assert pub["video"]["canPublishSources"] == ["camera", "microphone"]
    assert sub["video"]["canPublish"] is True and sub["video"]["canSubscribe"] is True
    assert sub["video"]["canPublishSources"] == ["microphone"]
    assert sub["video"]["canPublishData"] is False
    assert not sub["video"].get("roomAdmin") and not sub["video"].get("roomCreate")
    assert 0 < sub["exp"] - sub["nbf"] <= 120
    assert database["incidents"].documents[0]["livekitRoomName"] == live.created[0]
    assert "token" not in database["livekit_participants"].documents[0]
    # No FCM device registration or successful push is needed for live authorization.
    assert client.get("/live-safety/incidents", headers=viewer).json()["incidents"][0]["id"] == id


def test_arbitrary_users_unlinked_contacts_and_expired_sessions_cannot_join(client, database, sign_in, live):
    _, owner, viewer, contact = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    _, stranger, _ = sign_in("stranger")
    for suffix in ("/live", "/live/join"):
        method = client.get if suffix == "/live" else client.post
        assert method(f"/incidents/{id}{suffix}", headers=stranger).status_code == 404
        assert method(f"/incidents/{id}{suffix}").status_code == 401
    assert client.get("/live-safety/incidents", headers=stranger).json() == {"incidents": []}
    assert client.get("/live-safety/incidents").status_code == 401
    client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    assert client.post(f"/incidents/{id}/live/join", headers=viewer).status_code == 404
    database["auth_sessions"].documents[0]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert client.post(f"/incidents/{id}/live/join", headers=owner).status_code == 401


def test_multiple_viewers_and_end_revokes_every_issued_identity(client, database, sign_in, live):
    owner_id, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    _, second_viewer, _ = sign_in("second-viewer")
    contact = client.post("/contacts", headers=owner, json={"ownerId": owner_id, "name": "Second",
        "relation": "Friend", "phone": "1234567890", "initials": "S"}).json()
    code = client.get("/notification-links/code", headers=second_viewer).json()["recipientCode"]
    client.post("/notification-links", headers=owner, json={"contactId": contact["_id"], "recipientCode": code})
    responses = [client.post(f"/incidents/{id}/live/join", headers=h) for h in (owner, viewer, second_viewer)]
    assert len({decode(r)["sub"] for r in responses}) == 3
    assert client.patch(f"/incidents/{id}/end", headers=viewer).status_code == 404
    assert client.patch(f"/incidents/{id}/end", headers=owner).status_code == 200
    assert len(live.removed) == 3 and live.deleted == [live.created[0]]
    assert all(r.revoke_token_ts > datetime.now(timezone.utc).timestamp() for r in live.removed)
    for headers in (owner, viewer, second_viewer):
        assert client.post(f"/incidents/{id}/live/join", headers=headers).status_code == 409
        assert client.get(f"/incidents/{id}/live", headers=headers).json()["active"] is False
    assert client.get("/live-safety/incidents", headers=viewer).json() == {"incidents": []}
    assert database["livekit_sessions"].documents[0]["status"] == "CLOSED"


def test_room_failures_do_not_break_sos_and_cleanup_retries(client, database, sign_in, live, caplog):
    _, owner, viewer, _ = pair(client, sign_in)
    live.fail = True
    created = client.post("/incidents", headers=owner, json={})
    assert created.status_code == 201
    id = created.json()["id"]
    assert client.post(f"/incidents/{id}/live/join", headers=viewer).status_code == 503
    live.fail = False
    assert client.post(f"/incidents/{id}/live/join", headers=owner).status_code == 200
    live.fail_close = True
    assert client.patch(f"/incidents/{id}/end", headers=owner).status_code == 200
    assert database["livekit_sessions"].documents[0]["status"] == "CLOSE_PENDING"
    assert client.post(f"/incidents/{id}/live/join", headers=owner).status_code == 409
    live.fail_close = False
    client.app.state.live_safety.reconcile(database)
    assert database["livekit_sessions"].documents[0]["status"] == "CLOSED"
    assert TEST_SECRET not in caplog.text and "test-private-sdk-error" not in caplog.text


@pytest.mark.parametrize("revoke", ["link", "contact", "session"])
def test_revoked_viewers_are_removed_from_media(client, database, sign_in, live, revoke):
    _, owner, viewer, contact = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    response = client.post(f"/incidents/{id}/live/join", headers=viewer)
    identity = decode(response)["sub"]
    if revoke == "link": client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    elif revoke == "contact": client.delete(f"/contacts/{contact['_id']}", headers=owner)
    else: client.delete("/users/session", headers=viewer)
    client.app.state.live_safety.reconcile(database)
    assert live.removed[-1].identity == identity
    assert database["livekit_participants"].documents[0]["revoked"] is True


def test_disabled_mode_and_readiness(client, sign_in):
    _, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    assert client.get("/health/live").json() == {"status": "disabled", "deliveryVerified": False}
    assert client.post(f"/incidents/{id}/live/join", headers=viewer).status_code == 503
    assert client.patch(f"/incidents/{id}/end", headers=owner).status_code == 200


def test_self_hosted_and_partial_configuration_are_rejected():
    for url, key, secret in (("wss://self-hosted.example", "key", TEST_SECRET),
                              ("wss://[malformed", "key", TEST_SECRET),
                              ("wss://test.livekit.cloud", "", TEST_SECRET)):
        service = LiveSafetyService(Settings("mongodb://localhost", "test", "test.apps.googleusercontent.com",
            livekit_url=url, livekit_api_key=key, livekit_api_secret=secret))
        assert service.status == "invalid_configuration"


def test_room_or_role_override_is_rejected(client, sign_in, live):
    _, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    assert client.post(f"/incidents/{id}/live/join", headers=viewer,
                       json={"room": "arbitrary", "role": "publisher"}).status_code == 422


def test_end_during_room_creation_never_returns_a_join_token(client, database, sign_in, live, monkeypatch):
    _, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    async def concurrent_end(request):
        database["incidents"].documents[0]["status"] = "ENDED"
    monkeypatch.setattr(live, "create_room", concurrent_end)
    response = client.post(f"/incidents/{id}/live/join", headers=viewer)
    assert response.status_code == 409 and "token" not in response.json()
    assert live.deleted


def test_live_routes_have_authenticated_openapi_contract(client):
    paths = client.get("/openapi.json").json()["paths"]
    for path, method in (("/live-safety/incidents", "get"), ("/incidents/{id}/live", "get"),
                         ("/incidents/{id}/live/join", "post")):
        assert paths[path][method]["security"] == [{"HTTPBearer": []}]


@pytest.mark.parametrize("state,eligible", [("ACCEPTED", True), ("PENDING", False),
    ("REJECTED", False), ("REVOKED", False), ("LATE", False)])
def test_membership_live_access_is_incident_scoped_without_push_or_legacy_link(
        client, database, sign_in, live, state, eligible):
    owner_id, owner, _ = sign_in("membership-owner")
    recipient_id, viewer, _ = sign_in("membership-viewer")
    contact = client.post("/contacts", headers=owner, json={"ownerId": owner_id, "name": "Member",
        "relation": "Friend", "phone": "1234567890", "initials": "M"}).json()
    stored = database["trusted_contacts"].documents[0]
    stored.update(invitedRecipientId=recipient_id, invitationId="test-invitation",
                  invitationStatus="ACCEPTED" if state == "LATE" else state,
                  decidedAt=datetime.now(timezone.utc) - timedelta(seconds=1))
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    if state == "LATE":
        stored["decidedAt"] = database["incidents"].documents[0]["startedAt"] + timedelta(seconds=1)
    assert database["notification_links"].documents == []
    assert database["incident_notification_recipients"].documents == []
    assert client.get(f"/incidents/{id}/live", headers=viewer).status_code == (200 if eligible else 404)
    joined = client.post(f"/incidents/{id}/live/join", headers=viewer)
    assert joined.status_code == (200 if eligible else 404)
    listed = client.get("/live-safety/incidents", headers=viewer).json()["incidents"]
    assert [item["id"] for item in listed] == ([id] if eligible else [])
    # Live authorization must not expand the shared evidence/chat/AI policy.
    from app.live_safety import may_view
    assert not may_view(database, database["incidents"].documents[0], recipient_id)
    if eligible:
        publisher = client.post(f"/incidents/{id}/live/join", headers=owner)
        assert decode(joined)["video"]["room"] == decode(publisher)["video"]["room"]
        assert decode(joined)["video"]["canSubscribe"] is True
        assert decode(joined)["video"]["canPublish"] is True
        client.app.state.live_safety.reconcile(database)
        assert not live.removed
        stored["invitationStatus"] = "REVOKED"
        client.app.state.live_safety.reconcile(database)
        assert live.removed[-1].identity == decode(joined)["sub"]
        assert client.post(f"/incidents/{id}/live/join", headers=viewer).status_code == 404
        assert client.get("/live-safety/incidents", headers=viewer).json()["incidents"] == []


def test_membership_cannot_resolve_a_different_or_ended_incident(client, database, sign_in, live):
    owner_id, owner, _ = sign_in("member-owner")
    recipient_id, viewer, _ = sign_in("member-viewer")
    _, other, _ = sign_in("other-owner")
    database["trusted_contacts"].insert_one({"ownerId": owner_id, "invitedRecipientId": recipient_id,
        "invitationId": "test-invitation", "invitationStatus": "ACCEPTED",
        "decidedAt": datetime.now(timezone.utc) - timedelta(seconds=1)})
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    other_id = client.post("/incidents", headers=other, json={}).json()["id"]
    assert client.post(f"/incidents/{other_id}/live/join", headers=viewer).status_code == 404
    assert client.post(f"/incidents/{id}/live/join", headers=viewer).status_code == 200
    assert client.patch(f"/incidents/{id}/end", headers=owner).status_code == 200
    assert client.post(f"/incidents/{id}/live/join", headers=viewer).status_code == 404


def test_viewer_audio_upgrades_existing_room_without_recreating_or_disconnecting(client, database, sign_in, live):
    from livekit import api
    _, owner, viewer, contact = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    # No audio grant until this authenticated session has joined this incident.
    assert client.post(f"/incidents/{id}/live/audio", headers=viewer).status_code == 409
    pub = client.post(f"/incidents/{id}/live/join", headers=owner)
    sub = client.post(f"/incidents/{id}/live/join", headers=viewer)
    original_rooms = list(live.created)
    identity_records = [dict(p) for p in database["livekit_participants"].documents]
    response = client.post(f"/incidents/{id}/live/audio", headers=viewer)
    assert response.status_code == 204
    assert response.headers["cache-control"] == "no-store"
    assert live.created == original_rooms and not live.deleted and not live.removed
    assert database["livekit_participants"].documents == identity_records
    publisher, subscriber = live.updated
    assert publisher.identity == decode(pub)["sub"]
    assert subscriber.identity == decode(sub)["sub"]
    assert publisher.room == subscriber.room == original_rooms[0]
    assert publisher.permission.can_subscribe and publisher.permission.can_publish
    assert list(publisher.permission.can_publish_sources) == [api.TrackSource.CAMERA, api.TrackSource.MICROPHONE]
    assert subscriber.permission.can_publish and subscriber.permission.can_subscribe
    assert list(subscriber.permission.can_publish_sources) == [api.TrackSource.MICROPHONE]
    assert not subscriber.permission.can_publish_data
    assert client.post(f"/incidents/{id}/live/audio", headers=owner).status_code == 409
    assert client.post(f"/incidents/{id}/live/audio", headers=viewer,
        json={"identity": decode(pub)["sub"], "camera": True}).status_code == 422
    _, stranger, _ = sign_in("audio-stranger")
    assert client.post(f"/incidents/{id}/live/audio", headers=stranger).status_code == 404
    assert client.post(f"/incidents/{id}/live/audio").status_code == 401
    client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    assert client.post(f"/incidents/{id}/live/audio", headers=viewer).status_code == 404
    assert len(live.updated) == 2


def test_audio_permission_failure_does_not_close_working_session(client, sign_in, live):
    _, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    client.post(f"/incidents/{id}/live/join", headers=viewer)
    live.fail = True
    assert client.post(f"/incidents/{id}/live/audio", headers=viewer).status_code == 503
    assert not live.removed and not live.deleted
    assert client.get(f"/incidents/{id}/live", headers=viewer).json()["active"]


def test_history_rejoin_requires_authorization_open_session_and_existing_livekit_room(client, database, sign_in, live):
    _, owner, viewer, contact = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    rooms_before = list(live.created)
    assert client.get(f"/incidents/{id}/live", headers=viewer).json()["sessionActive"] is True
    result = client.post(f"/incidents/{id}/live/rejoin", headers=viewer)
    assert result.status_code == 200
    assert decode(result)["video"]["room"] == rooms_before[0]
    assert live.created == rooms_before
    live.room_exists = False
    assert client.get(f"/incidents/{id}/live?verifySession=true", headers=viewer).json()["sessionActive"] is False
    assert client.post(f"/incidents/{id}/live/rejoin", headers=viewer).status_code == 409
    live.room_exists = True
    database["livekit_sessions"].documents[0]["status"] = "CLOSED"
    assert client.get(f"/incidents/{id}/live", headers=viewer).json()["sessionActive"] is False
    assert client.post(f"/incidents/{id}/live/rejoin", headers=owner).status_code == 409
    database["livekit_sessions"].documents[0]["status"] = "OPEN"
    client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    assert client.post(f"/incidents/{id}/live/rejoin", headers=viewer).status_code == 404
    assert client.post(f"/incidents/{id}/live/rejoin").status_code == 401
    client.patch(f"/incidents/{id}/end", headers=owner)
    assert client.post(f"/incidents/{id}/live/rejoin", headers=owner).status_code == 409
    assert live.created == rooms_before


def test_sender_roster_only_contains_currently_authorized_viewer_sessions(client, database, sign_in, live):
    _, owner, viewer, contact = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    client.post(f"/incidents/{id}/live/join", headers=owner)
    joined = client.post(f"/incidents/{id}/live/join", headers=viewer)
    expected_identity = decode(joined)["sub"]
    response = client.get(f"/incidents/{id}/live", headers=owner)
    assert response.headers["cache-control"] == "no-store"
    roster = response.json()["viewers"]
    assert len(roster) == 1 and roster[0]["identity"] == expected_identity
    assert set(roster[0]) == {"identity", "name"} and roster[0]["name"]
    assert client.get(f"/incidents/{id}/live", headers=viewer).json()["viewers"] == []
    _, stranger, _ = sign_in("roster-stranger")
    assert client.get(f"/incidents/{id}/live", headers=stranger).status_code == 404
    record = database["livekit_participants"].documents[1]
    record["revoked"] = True
    assert client.get(f"/incidents/{id}/live", headers=owner).json()["viewers"] == []
    record["revoked"] = False
    viewer_session = next(s for s in database["auth_sessions"].documents if s["tokenHash"] == record["sessionHash"])
    expires_at = viewer_session["expiresAt"]
    viewer_session["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert client.get(f"/incidents/{id}/live", headers=owner).json()["viewers"] == []
    viewer_session["expiresAt"] = expires_at
    client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    assert client.get(f"/incidents/{id}/live", headers=owner).json()["viewers"] == []
    assert len(live.created) == 3 and not live.deleted
