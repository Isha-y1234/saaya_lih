from datetime import datetime

import pytest
from bson import ObjectId
from pymongo.errors import DuplicateKeyError, ServerSelectionTimeoutError


def create(client, headers, **coordinates):
    response = client.post("/incidents", headers=headers, json=coordinates)
    assert response.status_code == 201, response.text
    return response.json()


def test_create_get_list_end_lifecycle_and_server_identity(client, database, sign_in):
    owner, headers, _ = sign_in()
    incident = create(client, headers, latitude=12.9716, longitude=77.5946)
    assert set(incident) == {"id", "ownerId", "status", "createdAt", "updatedAt", "startedAt", "endedAt", "latitude", "longitude"}
    assert ObjectId.is_valid(incident["id"])
    assert incident["ownerId"] == owner and incident["status"] == "ACTIVE"
    assert incident["endedAt"] is None
    assert incident["createdAt"] == incident["startedAt"] == incident["updatedAt"]
    assert incident["latitude"] == 12.9716 and incident["longitude"] == 77.5946
    assert client.get(f"/incidents/{incident['id']}", headers=headers).json() == incident
    assert client.get("/incidents", headers=headers).json() == {"incidents": [incident]}
    stored = database["incidents"].documents[0]
    assert isinstance(stored["createdAt"], datetime)
    ended = client.patch(f"/incidents/{incident['id']}/end", headers=headers)
    assert ended.status_code == 200
    final = ended.json()
    assert final["status"] == "ENDED" and final["endedAt"] == final["updatedAt"]
    assert final["createdAt"] == incident["createdAt"] and final["startedAt"] == incident["startedAt"]
    assert datetime.fromisoformat(final["endedAt"].replace("Z", "+00:00")) >= stored["createdAt"]
    assert client.patch(f"/incidents/{incident['id']}/end", headers=headers).json() == final
    assert len(database["incidents"].documents) == 1
    newer = create(client, headers)
    assert newer["id"] != incident["id"]
    assert newer["latitude"] is None and newer["longitude"] is None
    assert [x["id"] for x in client.get("/incidents", headers=headers).json()["incidents"]] == [newer["id"], incident["id"]]


def test_retried_sos_resumes_one_active_incident(client, database, sign_in):
    _, headers, _ = sign_in()
    incident = create(client, headers)
    retried = client.post("/incidents", headers=headers, json={})
    assert retried.status_code == 200 and retried.json() == incident
    assert len(database["incidents"].documents) == 1
    assert ("ownerId", {"unique": True, "partialFilterExpression": {"status": "ACTIVE"},
                        "name": "one_active_incident_per_user"}) in database["incidents"].indexes


def test_concurrent_insert_conflict_returns_existing_incident(client, database, sign_in, monkeypatch):
    _, headers, _ = sign_in()
    incident = create(client, headers)
    collection = database["incidents"]
    real_find = collection.find_one
    attempts = 0

    def race(query):
        nonlocal attempts
        attempts += 1
        return None if attempts == 1 else real_find(query)

    def duplicate(document):
        raise DuplicateKeyError("test-only duplicate")

    monkeypatch.setattr(collection, "find_one", race)
    monkeypatch.setattr(collection, "insert_one", duplicate)
    response = client.post("/incidents", headers=headers, json={})
    assert response.status_code == 200 and response.json() == incident


def test_incident_ownership_cannot_be_spoofed(client, database, sign_in):
    owner_a, headers_a, _ = sign_in("google-a")
    _, headers_b, _ = sign_in("google-b")
    incident = create(client, headers_a)
    assert client.get("/incidents", headers=headers_b).json() == {"incidents": []}
    assert client.get(f"/incidents/{incident['id']}", headers=headers_b).status_code == 404
    assert client.patch(f"/incidents/{incident['id']}/end", headers=headers_b).status_code == 404
    assert client.post("/incidents", headers=headers_b, json={"ownerId": owner_a}).status_code == 422
    assert database["incidents"].documents[0]["status"] == "ACTIVE"
    assert len(database["incidents"].documents) == 1


@pytest.mark.parametrize("body", [
    {"latitude": 91, "longitude": 0}, {"latitude": 0, "longitude": -181},
    {"latitude": 12}, {"longitude": 77}, {"latitude": "not-a-number", "longitude": 77},
    {"latitude": "NaN", "longitude": 0}, {"latitude": True, "longitude": 77},
    {"status": "ENDED"}, {"createdAt": "2020-01-01"}, {"id": "chosen-by-client"},
])
def test_invalid_incident_payloads_do_not_write(client, database, sign_in, body):
    _, headers, _ = sign_in()
    assert client.post("/incidents", headers=headers, json=body).status_code == 422
    assert database["incidents"].documents == []


@pytest.mark.parametrize("method,path,body", [
    ("post", "/incidents", {}), ("get", "/incidents", None),
    ("get", "/incidents/507f1f77bcf86cd799439011", None),
    ("patch", "/incidents/507f1f77bcf86cd799439011/end", None),
])
def test_incidents_require_authenticated_session(client, method, path, body):
    kwargs = {"json": body} if method in {"post", "patch"} else {}
    assert getattr(client, method)(path, **kwargs).status_code == 401


@pytest.mark.parametrize("id,status", [("bad-id", 400), ("507f1f77bcf86cd799439011", 404)])
def test_invalid_and_missing_incident_ids(client, sign_in, id, status):
    _, headers, _ = sign_in()
    assert client.get(f"/incidents/{id}", headers=headers).status_code == status
    assert client.patch(f"/incidents/{id}/end", headers=headers).status_code == status


def test_incident_database_errors_are_safe_and_patch_cors_is_allowed(client, database, sign_in, monkeypatch):
    _, headers, _ = sign_in()

    def unavailable(query):
        raise ServerSelectionTimeoutError("test-only internal database details")

    monkeypatch.setattr(database["incidents"], "find_one", unavailable)
    response = client.post("/incidents", headers=headers, json={})
    assert response.status_code == 503 and response.json() == {"detail": "Database temporarily unavailable"}
    preflight = client.options("/incidents/507f1f77bcf86cd799439011/end", headers={
        "Origin": "http://localhost:8000", "Access-Control-Request-Method": "PATCH",
        "Access-Control-Request-Headers": "authorization",
    })
    assert preflight.status_code == 200
    assert "PATCH" in preflight.headers["access-control-allow-methods"]
