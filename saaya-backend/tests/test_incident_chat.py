from datetime import datetime, timedelta, timezone

import pytest
from starlette.websockets import WebSocketDisconnect
from test_live_safety import pair


def open_chat(client, id, headers):
    return client.websocket_connect(f"/incidents/{id}/chat/ws", headers=headers)


def test_persistence_reconnect_history_and_read_only(client, database, sign_in):
    owner_id, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    with open_chat(client, id, owner) as ws:
        assert ws.receive_json()["messages"] == []
        ws.send_json({"text": "  Help is coming  "})
        assert ws.receive_json() == {"type": "sent"}
        message = ws.receive_json()["messages"][0]
        assert message["senderId"] == owner_id
        assert message["text"] == "Help is coming"
        assert message["createdAt"]
        ws.send_json({"text": "forged", "senderId": "other"})
        assert ws.receive_json()["type"] == "error"
    assert len(database["incident_messages"].documents) == 1
    with open_chat(client, id, viewer) as ws:
        assert ws.receive_json()["messages"] == [message]
        ws.send_json({"text": "On my way"})
        assert ws.receive_json()["type"] == "sent"
        assert ws.receive_json()["messages"][0]["senderId"] != owner_id
        client.patch(f"/incidents/{id}/end", headers=owner)
        ws.send_json({"text": "too late"})
        frames = [ws.receive_json(), ws.receive_json()]
        assert any(f["type"] == "error" and f["readOnly"] for f in frames)
    page = client.get(f"/incidents/{id}/chat/messages", headers=viewer)
    assert page.headers["cache-control"] == "no-store"
    assert page.json()["readOnly"]
    assert [m["text"] for m in page.json()["messages"]] == ["Help is coming", "On my way"]
    with open_chat(client, id, owner) as ws:
        assert ws.receive_json()["readOnly"]
    assert len(database["incident_messages"].documents) == 2


def test_access_revocation_sessions_and_account_isolation(client, database, sign_in):
    _, owner, viewer, contact = pair(client, sign_in)
    _, stranger, _ = sign_in("chat-stranger")
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    for headers in ({}, stranger):
        assert client.get(f"/incidents/{id}/chat/messages", headers=headers).status_code in (401, 404)
        with pytest.raises(WebSocketDisconnect):
            with open_chat(client, id, headers) as ws:
                ws.receive_json()
    with open_chat(client, id, viewer) as ws:
        ws.receive_json()
        client.delete(f"/notification-links/{contact['_id']}", headers=owner)
        ws.send_json({"text": "revoked"})
        with pytest.raises(WebSocketDisconnect):
            while True:
                ws.receive_json()
    assert database["incident_messages"].documents == []
    with open_chat(client, id, owner) as ws:
        ws.receive_json()
        database["auth_sessions"].documents[0]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
        ws.send_json({"text": "expired"})
        with pytest.raises(WebSocketDisconnect):
            while True:
                ws.receive_json()
    assert database["incident_messages"].documents == []


def test_bounded_chronological_pages_and_validation(client, database, sign_in):
    _, headers, _ = sign_in()
    id = client.post("/incidents", headers=headers, json={}).json()["id"]
    for n in range(105):
        database["incident_messages"].insert_one({"incidentId": id, "senderId": "fixture", "text": str(n),
                                                "createdAt": datetime.now(timezone.utc)})
    page = client.get(f"/incidents/{id}/chat/messages", headers=headers).json()
    assert len(page["messages"]) == 100
    assert page["messages"][0]["text"] == "5"
    older = client.get(f"/incidents/{id}/chat/messages?before={page['nextBefore']}", headers=headers).json()
    assert [m["text"] for m in older["messages"]] == [str(n) for n in range(5)]
    assert older["nextBefore"] is None
    assert client.get(f"/incidents/{id}/chat/messages?before=bad", headers=headers).status_code == 400
    with open_chat(client, id, headers) as ws:
        assert len(ws.receive_json()["messages"]) == 100
        for text in ("  ", "x" * 2001):
            ws.send_json({"text": text})
            while ws.receive_json()["type"] != "error":
                pass
    assert len(database["incident_messages"].documents) == 105

def test_two_connected_accounts_receive_persisted_messages_and_resume_cursor(client, database, sign_in):
    _, owner, viewer, _ = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    with open_chat(client, id, owner) as sender, open_chat(client, id, viewer) as receiver:
        sender.receive_json()
        receiver.receive_json()
        sender.send_json({"text": "Together"})
        assert sender.receive_json()["type"] == "sent"
        first = sender.receive_json()["messages"][0]
        while True:
            frame = receiver.receive_json()
            if frame["messages"]:
                assert frame["messages"] == [first]
                break
    for n in range(105):
        database["incident_messages"].insert_one({"incidentId": id, "senderId": "fixture", "text": str(n),
                                                "createdAt": datetime.now(timezone.utc)})
    with client.websocket_connect(f"/incidents/{id}/chat/ws?after={first['id']}", headers=viewer) as ws:
        assert len(ws.receive_json()["messages"]) == 100
        assert len(ws.receive_json()["messages"]) == 5


@pytest.mark.parametrize("state,allowed", [("ACCEPTED", True), ("PENDING", False),
    ("REJECTED", False), ("REVOKED", False)])
def test_active_membership_viewer_can_send_without_bypassing_authorization(client, database, sign_in, state, allowed):
    owner_id, owner, _ = sign_in("chat-member-owner")
    viewer_id, viewer, _ = sign_in("chat-member-viewer")
    database["trusted_contacts"].insert_one({"ownerId": owner_id, "invitedRecipientId": viewer_id,
        "invitationId": "test-chat-invitation", "invitationStatus": state,
        "decidedAt": datetime.now(timezone.utc) - timedelta(seconds=1)})
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    assert client.get(f"/incidents/{id}/chat/messages", headers=viewer).status_code == (200 if allowed else 404)
    if not allowed:
        with pytest.raises(WebSocketDisconnect):
            with open_chat(client, id, viewer) as ws:
                ws.receive_json()
        return
    with open_chat(client, id, owner) as sender, open_chat(client, id, viewer) as receiver:
        sender.receive_json()
        receiver.receive_json()
        receiver.send_json({"text": "I can hear you"})
        assert receiver.receive_json()["type"] == "sent"
        message = receiver.receive_json()["messages"][0]
        assert message["senderId"] == viewer_id
        while True:
            frame = sender.receive_json()
            if frame["messages"]:
                assert frame["messages"] == [message]
                break
        database["trusted_contacts"].documents[0]["invitationStatus"] = "REVOKED"
        receiver.send_json({"text": "must be rejected"})
        with pytest.raises(WebSocketDisconnect):
            while True:
                receiver.receive_json()
    assert len(database["incident_messages"].documents) == 1
    assert client.get(f"/incidents/{id}/chat/messages", headers=viewer).status_code == 404


def test_chat_summary_endpoint_is_removed_while_history_remains_available(client, sign_in, monkeypatch):
    from app import analysis
    _, owner, _ = sign_in("chat-history-without-summary")
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    monkeypatch.setattr(analysis, "invoke", lambda payload: pytest.fail("Chat history must not invoke AI"))
    assert client.post(f"/incidents/{id}/chat/summary", headers=owner,
                       json={"consent": True}).status_code == 404
    assert "/incidents/{id}/chat/summary" not in client.get("/openapi.json").json()["paths"]
    assert client.get(f"/incidents/{id}/chat/messages", headers=owner).status_code == 200
    with open_chat(client, id, owner) as ws:
        assert ws.receive_json()["messages"] == []
