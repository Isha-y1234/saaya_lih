from datetime import datetime, timezone

import pytest
from bson import ObjectId
from pydantic import ValidationError

from app.models.contact import ContactResponse
from app.saaya_ids import initialize_saaya_ids


def contact(owner):
    return dict(ownerId=owner, name="Friend", relation="Friend",
                phone="+919876543210", initials="F")


def test_saaya_ids_unique_stable_and_public(client, database, sign_in):
    _, headers, first = sign_in("first")
    _, _, second = sign_in("second")
    _, _, repeated = sign_in("first", name="Changed", email="changed@example.com")
    assert first["user"]["saayaId"] != second["user"]["saayaId"]
    assert first["user"]["saayaId"] == repeated["user"]["saayaId"]
    assert client.get("/users/me", headers=headers).json()["saayaId"] == first["user"]["saayaId"]
    assert ("saayaId", {"unique": True, "partialFilterExpression": {
        "saayaId": {"$type": "string"}}}) in database["users"].indexes


def test_startup_backfills_existing_users_idempotently(database):
    ids = [ObjectId(), ObjectId()]
    for oid in ids:
        database["users"].insert_one({"_id": oid, "googleSub": str(oid)})
    initialize_saaya_ids(database)
    first = [u.copy() for u in database["users"].documents]
    initialize_saaya_ids(database)
    assert database["users"].documents == first
    assert len({u["saayaId"] for u in first}) == 2
    assert [u["_id"] for u in first] == ids


def test_sign_in_backfills_legacy_account(client, database, sign_in):
    oid = ObjectId()
    database["users"].insert_one({"_id": oid, "googleSub": "legacy",
                                  "createdAt": datetime.now(timezone.utc)})
    owner, _, result = sign_in("legacy")
    assert owner == str(oid)
    assert result["user"]["saayaId"] == "SAAYA-" + str(oid).upper()


def test_invitation_ownership_phone_and_no_access_grants(client, database, sign_in):
    owner, headers, _ = sign_in("owner")
    recipient, recipient_headers, account = sign_in("recipient")
    created = client.post("/contacts", json=contact(owner), headers=headers).json()
    path = f"/contacts/{created['_id']}/invitation"
    body = {"recipientSaayaId": account["user"]["saayaId"]}
    assert client.post(path, json=body).status_code == 401
    assert client.post(path, json=body, headers=recipient_headers).status_code == 404
    response = client.post(path, json=body, headers=headers)
    assert response.status_code == 200, response.text
    invited = response.json()
    assert invited["_id"] == created["_id"]
    assert invited["phone"] == created["phone"]
    assert invited["invitedRecipientId"] == recipient
    assert invited["invitationStatus"] == "PENDING"
    assert client.post(path, json=body, headers=headers).status_code == 409
    assert client.get("/contacts", params={"ownerId": owner}, headers=headers).json()["contacts"] == [invited]
    assert database["notification_links"].documents == []
    assert database["incident_notification_recipients"].documents == []
    from app.live_safety import may_view
    assert not may_view(database, {"ownerId": owner}, recipient)
    assert client.delete(f"/contacts/{created['_id']}", headers=headers).status_code == 204


def test_self_unknown_and_client_controlled_invitation_rejected(client, sign_in):
    owner, headers, account = sign_in()
    created = client.post("/contacts", json=contact(owner), headers=headers).json()
    path = f"/contacts/{created['_id']}/invitation"
    body = {"recipientSaayaId": account["user"]["saayaId"]}
    assert client.post(path, json=body, headers=headers).status_code == 400
    assert client.post(path, json={"recipientSaayaId": "SAAYA-" + "0" * 24}, headers=headers).status_code == 404
    for extra in ({"invitationStatus": "ACCEPTED"}, {"invitedRecipientId": owner}, {"invitationStatus": "INVALID"}):
        assert client.post(path, json=body | extra, headers=headers).status_code == 422
        assert client.post("/contacts", json=contact(owner) | extra, headers=headers).status_code == 422
    assert client.post(path, json={"recipientSaayaId": "bad"}, headers=headers).status_code == 422


@pytest.mark.parametrize("state", ["PENDING", "ACCEPTED", "REJECTED", "REVOKED"])
def test_supported_invitation_states(state):
    model = ContactResponse(**contact("owner"), _id=str(ObjectId()),
                            createdAt=datetime.now(timezone.utc), invitationStatus=state)
    assert model.invitationStatus == state


def test_invalid_invitation_state():
    with pytest.raises(ValidationError):
        ContactResponse(**contact("owner"), _id=str(ObjectId()),
                        createdAt=datetime.now(timezone.utc), invitationStatus="UNKNOWN")


def invitation(client, sign_in):
    owner, sender, _ = sign_in("sender")
    recipient, receiver, account = sign_in("receiver")
    created = client.post("/contacts", json=contact(owner), headers=sender).json()
    path = f"/contacts/{created['_id']}/invitation"
    target = {"recipientSaayaId": account["user"]["saayaId"]}
    invited = client.post(path, json=target, headers=sender).json()
    return owner, recipient, sender, receiver, path, target, invited


@pytest.mark.parametrize("decision", ["accept", "reject"])
def test_recipient_only_decision_and_pending_listing(client, database, sign_in, decision):
    owner, recipient, sender, receiver, path, _, invited = invitation(client, sign_in)
    _, outsider, _ = sign_in("outsider")
    listing = "/contacts/invitations/incoming"
    body = {"invitationId": invited["invitationId"]}
    assert client.get(listing).status_code == 401
    response = client.get(listing, headers=receiver)
    assert response.json() == {"contacts": [invited]}
    assert response.headers["cache-control"] == "no-store"
    assert client.get(listing, headers=sender).json() == {"contacts": []}
    assert client.get(listing, headers=outsider).json() == {"contacts": []}
    assert client.post(path + "/" + decision, json=body).status_code == 401
    for unauthorized in (sender, outsider):
        assert client.post(path + "/" + decision, json=body, headers=unauthorized).status_code == 404
    response = client.post(path + "/" + decision, json=body, headers=receiver)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["invitationStatus"] == ("ACCEPTED" if decision == "accept" else "REJECTED")
    assert result["decidedAt"]
    assert result["phone"] == invited["phone"]
    assert result["_id"] == invited["_id"]
    assert client.get(listing, headers=receiver).json() == {"contacts": []}
    for repeated in ("accept", "reject"):
        assert client.post(path + "/" + repeated, json=body, headers=receiver).status_code == 409
    from app.live_safety import may_view
    assert not may_view(database, {"ownerId": owner}, recipient)
    assert database["notification_links"].documents == []
    assert database["incident_notification_recipients"].documents == []
    assert client.get("/contacts", params={"ownerId": owner}, headers=sender).json()["contacts"] == [result]
    assert client.delete(f"/contacts/{invited['_id']}", headers=sender).status_code == 204


def test_duplicate_pending_pair_and_directionality(client, database, sign_in):
    owner, recipient, sender, receiver, path, target, invited = invitation(client, sign_in)
    second = client.post("/contacts", json=contact(owner), headers=sender).json()
    assert client.post(path, json=target, headers=sender).status_code == 409
    assert client.post(f"/contacts/{second['_id']}/invitation", json=target, headers=sender).status_code == 409
    assert "invitationStatus" not in client.get("/contacts", params={"ownerId": owner}, headers=sender).json()["contacts"][1]
    owner_code = client.get("/users/me", headers=sender).json()["saayaId"]
    reverse = client.post("/contacts", json=contact(recipient), headers=receiver).json()
    assert client.post(f"/contacts/{reverse['_id']}/invitation", json={"recipientSaayaId": owner_code}, headers=receiver).status_code == 200
    assert ([("ownerId", 1), ("invitedRecipientId", 1)], {
        "unique": True, "partialFilterExpression": {"invitationStatus": "PENDING"},
        "name": "one_pending_invitation_per_pair"}) in database["trusted_contacts"].indexes


def test_stale_decision_cannot_accept_reinvitation(client, sign_in):
    _, _, sender, receiver, path, target, invited = invitation(client, sign_in)
    old = {"invitationId": invited["invitationId"]}
    assert client.post(path + "/reject", json=old, headers=receiver).status_code == 200
    replacement = client.post(path, json=target, headers=sender)
    assert replacement.status_code == 200
    assert replacement.json()["invitationId"] != old["invitationId"]
    assert client.post(path + "/accept", json=old, headers=receiver).status_code == 409
    assert client.post(path + "/accept", json={"invitationId": replacement.json()["invitationId"]}, headers=receiver).status_code == 200


def test_invalid_decision_states_and_payload(client, database, sign_in):
    _, _, _, receiver, path, _, invited = invitation(client, sign_in)
    body = {"invitationId": invited["invitationId"]}
    assert client.post(path + "/accept", json=body | {"invitationStatus": "ACCEPTED"}, headers=receiver).status_code == 422
    for state in ("REVOKED", "ACCEPTED", "REJECTED"):
        database["trusted_contacts"].documents[0]["invitationStatus"] = state
        for decision in ("accept", "reject"):
            assert client.post(path + "/" + decision, json=body, headers=receiver).status_code == 409


def test_duplicate_key_race_returns_conflict(client, database, sign_in, monkeypatch):
    from pymongo.errors import DuplicateKeyError
    owner, headers, _ = sign_in("owner")
    _, _, recipient = sign_in("recipient")
    created = client.post("/contacts", json=contact(owner), headers=headers).json()
    def duplicate(*args, **kwargs):
        raise DuplicateKeyError("concurrent pending invitation")
    monkeypatch.setattr(database["trusted_contacts"], "find_one_and_update", duplicate)
    response = client.post(f"/contacts/{created['_id']}/invitation",
                           json={"recipientSaayaId": recipient["user"]["saayaId"]}, headers=headers)
    assert response.status_code == 409
