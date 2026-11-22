from datetime import datetime, timedelta, timezone
from io import BytesIO
from uuid import uuid4

import pytest
from botocore.exceptions import ClientError, EndpointConnectionError
from botocore.response import StreamingBody
from fastapi import HTTPException

from app.evidence import EvidenceStorage
from test_live_safety import pair


class S3:
    def __init__(self):
        self.head = None
        self.prefix = b"\xff\xd8\xff" + b"x" * 29
        self.error = None
        self.calls = []

    def generate_presigned_url(self, method, **kwargs):
        self.calls.append((method, kwargs))
        if self.error:
            raise self.error
        return "https://private-fixture.backblazeb2.com/signed-test-only"

    def head_object(self, **kwargs):
        self.calls.append(("head", kwargs))
        if self.error:
            raise self.error
        if self.head is None:
            raise ClientError({"Error": {"Code": "404"}}, "HeadObject")
        return self.head

    def get_object(self, **kwargs):
        self.calls.append(("get", kwargs))
        return {"Body": StreamingBody(BytesIO(self.prefix), len(self.prefix))}


@pytest.fixture
def s3(client, monkeypatch):
    fake = S3()
    monkeypatch.setattr(EvidenceStorage, "client", lambda self: fake)
    return fake


def setup(client, sign_in):
    _, owner, viewer, contact = pair(client, sign_in)
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    return id, owner, viewer, contact


def payload(**kwargs):
    return {"requestId": str(uuid4()), "mediaType": "image/jpeg", "fileSize": 32, **kwargs}


def uploaded(s3, grant, **kwargs):
    s3.head = {"ContentLength": 32, "ContentType": "image/jpeg", "VersionId": "version-1",
               "Metadata": {"evidence-id": grant["evidenceId"]}, "LastModified": datetime.now(timezone.utc), **kwargs}


def test_complete_idempotence_persistence_and_ended_retrieval(client, database, sign_in, s3):
    id, owner, viewer, _ = setup(client, sign_in)
    base = f"/incidents/{id}/evidence"
    body = payload()
    grant = client.post(base + "/uploads", headers=owner, json=body).json()
    assert not grant["complete"] and grant["expiresIn"] == 120
    assert client.get(base, headers=owner).json()["evidence"] == []
    assert client.post(base + "/uploads", headers=owner, json=body).json()["evidenceId"] == grant["evidenceId"]
    assert len(database["evidence"].documents) == 1
    uploaded(s3, grant)
    saved = client.post(base + f"/{grant['evidenceId']}/complete", headers=owner)
    assert saved.status_code == 200, saved.text
    assert saved.json()["uploadedAt"]
    assert "objectKey" not in saved.json() and "versionId" not in saved.json()
    doc = database["evidence"].documents[0]
    assert doc["state"] == "COMPLETE" and doc["versionId"] == "version-1"
    assert doc["objectKey"] == f"evidence/{doc['ownerId']}/{id}/{grant['evidenceId']}"
    assert client.post(base + "/uploads", headers=owner, json=body).json()["complete"]
    client.patch(f"/incidents/{id}/end", headers=owner)
    assert client.post(base + f"/{grant['evidenceId']}/complete", headers=owner).json() == saved.json()
    assert client.get(base, headers=viewer).json()["evidence"] == [saved.json()]
    assert not client.get(base, headers=owner).json()["canUpload"]
    assert client.post(base + f"/{grant['evidenceId']}/download", headers=viewer).status_code == 200
    assert s3.calls[-1][1]["Params"]["VersionId"] == "version-1"
    for method, arguments in s3.calls:
        assert (arguments["Params"] if method in ("put_object", "get_object") else arguments)["Bucket"] == "saaya-evidence"
    assert client.post(base + "/uploads", headers=owner, json=payload()).status_code == 409
    assert len(database["evidence"].documents) == 1


def test_current_circle_ownership_cross_account_and_revocation(client, database, sign_in, s3):
    id, owner, viewer, contact = setup(client, sign_in)
    _, stranger, _ = sign_in("evidence-stranger")
    base = f"/incidents/{id}/evidence"
    for headers, expected in (({}, 401), (stranger, 404), (viewer, 403)):
        assert client.post(base + "/uploads", headers=headers, json=payload()).status_code == expected
    grant = client.post(base + "/uploads", headers=owner, json=payload()).json()
    uploaded(s3, grant)
    eid = grant["evidenceId"]
    assert client.post(base + f"/{eid}/complete", headers=viewer).status_code == 403
    assert client.post(base + f"/{eid}/download", headers=owner).status_code == 404
    assert client.post(base + f"/{eid}/complete", headers=owner).status_code == 200
    for headers in ({}, stranger):
        assert client.get(base, headers=headers).status_code in (401, 404)
        assert client.post(base + f"/{eid}/download", headers=headers).status_code in (401, 404)
        assert client.post(base + f"/{eid}/complete", headers=headers).status_code in (401, 404)
    other = client.post("/incidents", headers=stranger, json={}).json()["id"]
    assert client.post(f"/incidents/{other}/evidence/{eid}/download", headers=stranger).status_code == 404
    assert client.post(f"/incidents/{other}/evidence/{eid}/complete", headers=stranger).status_code == 404
    assert client.post(base + f"/{eid}/download", headers=viewer).status_code == 200
    client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    assert client.get(base, headers=viewer).status_code == 404
    assert client.post(base + f"/{eid}/download", headers=viewer).status_code == 404


@pytest.mark.parametrize("extra,code", [({"mediaType": "text/html"}, 422),
    ({"fileSize": 10 * 1024 * 1024 + 1}, 413),
    ({"mediaType": "video/mp4", "fileSize": 50 * 1024 * 1024 + 1}, 413),
    ({"fileSize": 0}, 422), ({"fileSize": "32"}, 422), ({"ownerId": "forged"}, 422),
    ({"objectKey": "../../someone"}, 422), ({"recipientId": "forged"}, 422), ({"requestId": "../../x"}, 422)])
def test_validation(client, database, sign_in, s3, extra, code):
    _, owner, _ = sign_in()
    id = client.post("/incidents", headers=owner, json={}).json()["id"]
    assert client.post(f"/incidents/{id}/evidence/uploads", headers=owner, json=payload(**extra)).status_code == code
    assert not database["evidence"].documents


@pytest.mark.parametrize("invalid", [{"ContentLength": 33}, {"ContentType": "text/html"},
    {"Metadata": {}}, {"VersionId": None}, {"VersionId": "null"}])
def test_actual_object_validation(client, database, sign_in, s3, invalid):
    id, owner, _, _ = setup(client, sign_in)
    base = f"/incidents/{id}/evidence"
    grant = client.post(base + "/uploads", headers=owner, json=payload()).json()
    uploaded(s3, grant, **invalid)
    assert client.post(base + f"/{grant['evidenceId']}/complete", headers=owner).status_code == 409
    assert database["evidence"].documents[0]["state"] == "PENDING"


def test_missing_failed_expired_abandoned_and_ended_uploads(client, database, sign_in, s3):
    id, owner, _, _ = setup(client, sign_in)
    base = f"/incidents/{id}/evidence"
    body = payload()
    grant = client.post(base + "/uploads", headers=owner, json=body).json()
    complete = base + f"/{grant['evidenceId']}/complete"
    # A rejected/expired PUT or lost connection leaves no object: never complete.
    assert client.post(complete, headers=owner).status_code == 409
    s3.error = EndpointConnectionError(endpoint_url="https://test-only.invalid")
    assert client.post(complete, headers=owner).status_code == 503
    assert client.post(base + "/uploads", headers=owner, json=body).status_code == 503
    assert not client.get(base, headers=owner).json()["evidence"]
    s3.error = None
    renewed = client.post(base + "/uploads", headers=owner, json=body).json()
    assert renewed["evidenceId"] == grant["evidenceId"]
    assert client.post(base + "/uploads", headers=owner, json={**body, "fileSize": 31}).status_code == 409
    uploaded(s3, grant)
    s3.prefix = b"not a jpeg"
    assert client.post(complete, headers=owner).status_code == 409
    database["evidence"].documents[0]["retryUntil"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert client.post(base + "/uploads", headers=owner, json=body).status_code == 410
    assert client.post(complete, headers=owner).status_code == 410
    client.patch(f"/incidents/{id}/end", headers=owner)
    assert client.post(complete, headers=owner).status_code == 409
    assert client.post(base + "/uploads", headers=owner, json=payload()).status_code == 409
    assert database["evidence"].documents[0]["state"] == "PENDING"


def test_mp4_signature_and_session_expiry(client, database, sign_in, s3):
    id, owner, _, _ = setup(client, sign_in)
    base = f"/incidents/{id}/evidence"
    grant = client.post(base + "/uploads", headers=owner, json=payload(mediaType="video/mp4")).json()
    uploaded(s3, grant, ContentType="video/mp4")
    s3.prefix = b"\x00\x00\x00\x20ftypisom" + b"x" * 20
    assert client.post(base + f"/{grant['evidenceId']}/complete", headers=owner).status_code == 200
    database["auth_sessions"].documents[0]["expiresAt"] = datetime.now(timezone.utc) - timedelta(seconds=1)
    assert client.get(base, headers=owner).status_code == 401
    assert client.post(base + f"/{grant['evidenceId']}/download", headers=owner).status_code == 401


def test_real_sdk_presigns_exact_size_type_metadata_and_expiry(monkeypatch):
    from urllib.parse import urlparse, parse_qs
    for key, value in {"B2_BUCKET": "saaya-evidence", "B2_ENDPOINT_URL": "https://s3.us-west-004.backblazeb2.com",
                       "B2_REGION": "us-west-004", "B2_APPLICATION_KEY_ID": "fixture-key", "B2_APPLICATION_KEY": "fixture-secret"}.items():
        monkeypatch.setenv(key, value)
    storage = EvidenceStorage()
    doc = {"_id": "id", "objectKey": "evidence/owner/incident/id", "mediaType": "image/jpeg", "fileSize": 32, "versionId": "v1"}
    put = urlparse(storage.put_url(doc))
    assert put.path == "/saaya-evidence/evidence/owner/incident/id"
    params = parse_qs(put.query)
    assert params["X-Amz-Expires"] == ["120"]
    assert params["X-Amz-Algorithm"] == ["AWS4-HMAC-SHA256"]
    assert set(params["X-Amz-SignedHeaders"][0].split(";")) == {"host", "content-length", "content-type", "x-amz-meta-evidence-id"}
    get = urlparse(storage.get_url(doc))
    assert get.path == "/saaya-evidence/evidence/owner/incident/id"
    assert parse_qs(get.query)["versionId"] == ["v1"]
    monkeypatch.setenv("B2_ENDPOINT_URL", "http://arbitrary-host")
    with pytest.raises(HTTPException) as error:
        EvidenceStorage().client()
    assert error.value.status_code == 503


@pytest.mark.parametrize("configured", [None, "", "   ", "saaya-evidence", " saaya-evidence "])
def test_bucket_environment_defaults_to_saaya_evidence(monkeypatch, configured):
    if configured is None:
        monkeypatch.delenv("B2_BUCKET", raising=False)
    else:
        monkeypatch.setenv("B2_BUCKET", configured)
    assert EvidenceStorage().bucket == "saaya-evidence"


def test_other_bucket_configuration_cannot_sign_or_access_objects(monkeypatch):
    monkeypatch.setenv("B2_BUCKET", "wrong-bucket")
    monkeypatch.setenv("B2_ENDPOINT_URL", "https://s3.us-west-004.backblazeb2.com")
    monkeypatch.setenv("B2_REGION", "us-west-004")
    monkeypatch.setenv("B2_APPLICATION_KEY_ID", "fixture-key")
    monkeypatch.setenv("B2_APPLICATION_KEY", "fixture-secret")
    monkeypatch.setattr("app.evidence.boto3.client", lambda *args, **kwargs: pytest.fail("Wrong bucket must fail before SDK access"))
    storage = EvidenceStorage()
    for operation in (storage.put_url, storage.verify, storage.get_url):
        with pytest.raises(HTTPException) as error:
            operation({})
        assert error.value.status_code == 503


def test_ending_during_storage_verification_rejects_completion(client, database, sign_in, s3, monkeypatch):
    id, owner, _, _ = setup(client, sign_in)
    base = f"/incidents/{id}/evidence"
    grant = client.post(base + "/uploads", headers=owner, json=payload()).json()
    uploaded(s3, grant)
    original = s3.head_object
    def racing_head(**kwargs):
        client.patch(f"/incidents/{id}/end", headers=owner)
        return original(**kwargs)
    monkeypatch.setattr(s3, "head_object", racing_head)
    assert client.post(base + f"/{grant['evidenceId']}/complete", headers=owner).status_code == 409
    assert database["evidence"].documents[0]["state"] == "PENDING"
    assert client.get(base, headers=owner).json()["evidence"] == []


def test_pagination_and_capture_timestamp_retry(client, database, sign_in, s3):
    id, owner, _, _ = setup(client, sign_in)
    base = f"/incidents/{id}/evidence"
    body = payload(capturedAt="2026-11-04T00:00:00.123456Z")
    grant = client.post(base + "/uploads", headers=owner, json=body).json()
    assert client.post(base + "/uploads", headers=owner, json=body).json()["evidenceId"] == grant["evidenceId"]
    assert database["evidence"].documents[0]["capturedAt"].microsecond == 123000
    assert client.post(base + "/uploads", headers=owner, json=payload(capturedAt="2026-11-04T00:00:00")).status_code == 422
    assert client.get(base + "?before=../../other", headers=owner).status_code == 400
    for _ in range(51):
        database["evidence"].insert_one({"incidentId": id, "ownerId": "fixture", "state": "COMPLETE"})
    first = client.get(base, headers=owner)
    assert first.headers["cache-control"] == "no-store"
    assert len(first.json()["evidence"]) == 50
    second = client.get(base + "?before=" + first.json()["nextBefore"], headers=owner).json()
    assert len(second["evidence"]) == 1 and second["nextBefore"] is None
    assert ([item["id"] for item in first.json()["evidence"] + second["evidence"]] ==
            sorted([str(doc["_id"]) for doc in database["evidence"].documents if doc["state"] == "COMPLETE"], reverse=True))
    assert any(options.get("unique") and key == [("incidentId", 1), ("requestId", 1)]
               for key, options in database["evidence"].indexes)
