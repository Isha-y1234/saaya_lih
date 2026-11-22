from datetime import datetime, timedelta, timezone

import pytest

from app.auth import hash_session_token


def contact(owner_id):
    return dict(ownerId=owner_id, name="Ananya", relation="Sister", phone="+919876543210", initials="AS")


def test_stable_user_and_hashed_persisted_session(client, database, sign_in):
    owner, headers, first = sign_in()
    repeated_owner, _, second = sign_in()
    assert owner == repeated_owner
    assert first["token"] != second["token"]
    assert len(database["users"].documents) == 1
    assert client.get("/users/me", headers=headers).json()["_id"] == owner
    stored = database["auth_sessions"].documents[0]
    assert stored["tokenHash"] == hash_session_token(first["token"])
    assert first["token"] not in str(stored)
    assert first["expiresAt"] > int(datetime.now(timezone.utc).timestamp())
    assert ("googleSub", {"unique": True}) in database["users"].indexes
    assert ("expiresAt", {"expireAfterSeconds": 0}) in database["auth_sessions"].indexes


def test_google_subject_not_email_is_the_stable_identity(client, sign_in):
    owner, _, _ = sign_in("google-a")
    updated_owner, headers, _ = sign_in("google-a", email="renamed@example.com", name="New name")
    assert updated_owner == owner
    assert client.get("/users/me", headers=headers).json()["email"] == "renamed@example.com"


def test_google_transport_failure_is_503_not_an_invalid_login(client, google_token, monkeypatch):
    from google.auth.exceptions import TransportError
    def unavailable(*args, **kwargs):
        raise TransportError("private transport details")
    monkeypatch.setattr("app.auth.id_token.verify_oauth2_token", unavailable)
    response = client.post("/users/google", json={"idToken": google_token()})
    assert response.status_code == 503
    assert "private transport details" not in response.text


@pytest.mark.parametrize("overrides", [
    {"aud": "different-app.apps.googleusercontent.com"},
    {"iss": "https://attacker.example"},
    {"exp": 1},
    {"sub": ""},
    {"email_verified": False},
])
def test_invalid_google_claims_are_rejected(client, database, google_token, overrides):
    response = client.post("/users/google", json={"idToken": google_token(**overrides)})
    assert response.status_code == 401
    assert database["users"].documents == []
    assert database["auth_sessions"].documents == []


def test_tampered_token_and_missing_token_are_rejected(client, google_token):
    token = google_token()
    header, payload, signature = token.split(".")
    signature = ("a" if signature[0] != "a" else "b") + signature[1:]
    assert client.post("/users/google", json={"idToken": f"{header}.{payload}.{signature}"}).status_code == 401
    assert client.post("/users/google", json={}).status_code == 422


def test_contact_crud_preserves_contract_and_isolates_users(client, sign_in):
    owner_a, headers_a, _ = sign_in("google-a")
    owner_b, headers_b, _ = sign_in("google-b")
    assert owner_a != owner_b
    created = client.post("/contacts", json=contact(owner_a), headers=headers_a)
    assert created.status_code == 201
    document = created.json()
    assert set(document) == {"_id", "ownerId", "name", "relation", "phone", "initials", "createdAt"}
    response = client.get("/contacts", params={"ownerId": owner_a}, headers=headers_a)
    assert response.status_code == 200 and response.json() == {"contacts": [document]}
    assert client.get("/contacts", params={"ownerId": owner_b}, headers=headers_b).json() == {"contacts": []}
    assert client.get("/contacts", params={"ownerId": owner_a}, headers=headers_b).status_code == 403
    assert client.post("/contacts", json=contact(owner_a), headers=headers_b).status_code == 403
    assert client.delete(f"/contacts/{document['_id']}", headers=headers_b).status_code == 404
    assert len(client.get("/contacts", params={"ownerId": owner_a}, headers=headers_a).json()["contacts"]) == 1
    removed = client.delete(f"/contacts/{document['_id']}", headers=headers_a)
    assert removed.status_code == 204 and removed.content == b""
    assert client.get("/contacts", params={"ownerId": owner_a}, headers=headers_a).json() == {"contacts": []}


@pytest.mark.parametrize("authorization", [None, "Bearer invalid", "Basic invalid", "Bearer " + "x" * 43])
def test_unauthenticated_cannot_access_users_or_contacts(client, authorization):
    headers = {"Authorization": authorization} if authorization else {}
    assert client.get("/users/me", headers=headers).status_code == 401
    assert client.get("/contacts", params={"ownerId": "test-user-001"}, headers=headers).status_code == 401
    assert client.post("/contacts", json=contact("test-user-001"), headers=headers).status_code == 401
    assert client.delete("/contacts/507f1f77bcf86cd799439011", headers=headers).status_code == 401


def test_expired_and_revoked_sessions_are_rejected(client, database, sign_in):
    _, headers, _ = sign_in()
    database["auth_sessions"].documents[0]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert client.get("/users/me", headers=headers).status_code == 401
    _, valid_headers, _ = sign_in()
    logout = client.delete("/users/session", headers=valid_headers)
    assert logout.status_code == 204 and logout.content == b""
    assert client.get("/users/me", headers=valid_headers).status_code == 401


def test_missing_deleted_user_does_not_authenticate(client, database, sign_in):
    _, headers, _ = sign_in()
    database["users"].documents.clear()
    assert client.get("/users/me", headers=headers).status_code == 401


def test_validation_errors_and_health(client, sign_in):
    owner, headers, _ = sign_in()
    assert client.get("/contacts", headers=headers).status_code == 422
    assert client.post("/contacts", json=contact(owner) | {"phone": "bad"}, headers=headers).status_code == 422
    assert client.delete("/contacts/not-an-object-id", headers=headers).status_code == 400
    assert client.get("/health").json() == {"status": "ok"}
