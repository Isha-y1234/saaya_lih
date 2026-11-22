from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from threading import RLock
from uuid import uuid4

import pytest
from bson import ObjectId
from pymongo.errors import DuplicateKeyError

from app import analysis
from conftest import Collection
from test_evidence import setup, s3  # noqa: F401; reuse private-storage fixture


class AtomicCollection(Collection):
    """Atomic Mongo operations with _id uniqueness, including literal document CAS matching."""
    def __init__(self):
        super().__init__()
        self.lock = RLock()

    def insert_one(self, doc):
        with self.lock:
            if any(d["_id"] == doc.get("_id") for d in self.documents):
                raise DuplicateKeyError("fixture _id")
            return super().insert_one(doc)

    def find_one_and_update(self, query, update, **options):
        with self.lock:
            for doc in self.documents:
                if all(doc.get(k) == v for k, v in query.items()):
                    doc.update(update.get("$set", {}))
                    return doc.copy()
            return None


@pytest.fixture(autouse=True)
def usage(database, monkeypatch):
    database["analysis_usage"] = AtomicCollection()
    database["analysis_rates"] = AtomicCollection()
    monkeypatch.setenv("CLOUDFLARE_ACCOUNT_ID", "c" * 32)
    monkeypatch.setenv("CLOUDFLARE_API_TOKEN", "fixture-only-not-a-real-token")
    monkeypatch.setenv("AI_WORKER_SECRET", "fixture-only-not-a-real-secret-00000000")


def seed(database, incident):
    eid, aid = ObjectId(), ObjectId()
    database["evidence"].insert_one({"_id": eid, "incidentId": incident, "state": "COMPLETE", "mediaType": "video/mp4"})
    database["analysis_assets"].insert_one({"_id": aid, "incidentId": incident, "state": "COMPLETE", "kind": "frame",
        "evidenceId": str(eid), "capturedAt": analysis.now(), "offsetMs": 1000, "fileSize": 100,
        "sha256": "a" * 64, "versionId": "private-version", "objectKey": "analysis/private"})
    return str(eid), str(aid)


def body(aid, **changes):
    return {"requestId": str(uuid4()), "assetIds": [aid], "notes": "Participant note", "consent": True, **changes}


def test_consent_authorization_isolation_and_revocation(client, database, sign_in):
    incident, owner, viewer, contact = setup(client, sign_in)
    _, aid = seed(database, incident)
    base = f"/incidents/{incident}/analysis"
    _, stranger, _ = sign_in("analysis-stranger")
    assert client.post(base + "/jobs", headers=owner, json=body(aid, consent=False)).status_code == 422
    assert client.post(base + "/jobs", json=body(aid)).status_code == 401
    assert client.post(base + "/jobs", headers=stranger, json=body(aid)).status_code == 404
    job = client.post(base + "/jobs", headers=owner, json=body(aid)).json()
    assert client.get(base + "/jobs/" + job["id"], headers=viewer).status_code == 200
    assert client.delete(f"/notification-links/{contact['_id']}", headers=owner).status_code == 204
    assert client.get(base + "/jobs/" + job["id"], headers=viewer).status_code == 404
    assert client.post(base + "/jobs/" + job["id"] + "/advance", headers=stranger).status_code == 404
    other = client.post("/incidents", headers=stranger, json={}).json()["id"]
    assert client.post(f"/incidents/{other}/analysis/jobs", headers=stranger, json=body(aid)).status_code == 404


def test_job_idempotency_and_conflicting_retry(client, database, sign_in):
    incident, owner, _, _ = setup(client, sign_in)
    _, aid = seed(database, incident)
    url = f"/incidents/{incident}/analysis/jobs"
    request = body(aid)
    first = client.post(url, headers=owner, json=request)
    assert first.status_code == 200
    assert client.post(url, headers=owner, json=request).json()["id"] == first.json()["id"]
    assert client.post(url, headers=owner, json={**request, "requestId": str(uuid4())}).json()["id"] == first.json()["id"]
    assert client.post(url, headers=owner, json={**request, "notes": "changed"}).status_code == 409
    assert len(database["analysis_jobs"].documents) == 1


def test_concurrent_budget_reservations_never_exceed_cap(database, monkeypatch):
    monkeypatch.setenv("CLOUDFLARE_ACCOUNT_ID", "a" * 32)
    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda n: analysis.atomic_allow(database, f"stage-{n}", 700, 2100, "owner"), range(20)))
    assert sum(results) == 3
    assert database["analysis_usage"].documents[0]["used"] == 2100
    entry = next(iter(database["analysis_usage"].documents[0]["entries"]))
    assert analysis.atomic_allow(database, entry, 700, 2100, "owner")
    assert database["analysis_usage"].documents[0]["used"] == 2100
    monkeypatch.setenv("CLOUDFLARE_ACCOUNT_ID", "b" * 32)
    assert analysis.atomic_allow(database, "another-account", 700, 2100, "owner")


def test_quota_pause_recovery_checkpoints_and_no_duplicate_calls(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    _, aid = seed(database, incident)
    base = f"/incidents/{incident}/analysis/jobs"
    job = client.post(base, headers=owner, json=body(aid)).json()
    url = base + f"/{job['id']}/advance"
    monkeypatch.setenv("AI_FREE_PLAN_CONFIRMED", "true")
    monkeypatch.setenv("AI_WORKER_URL", "https://fixture.workers.dev")
    monkeypatch.setenv("AI_DAILY_NEURONS", "1")
    calls = []
    monkeypatch.setattr(analysis, "invoke", lambda p: calls.append(p) or {"status": "ok", "result": {"observations": []}})
    monkeypatch.setattr(client.app.state.evidence_storage, "get_url", lambda d: "https://private-signed-fixture")
    assert client.post(url, headers=owner).json()["state"] == "PAUSED"
    assert not calls
    monkeypatch.setenv("AI_DAILY_NEURONS", "8000")
    database["analysis_jobs"].documents[0]["retryAfter"] = analysis.now() - timedelta(seconds=1)
    assert client.post(url, headers=owner).json()["state"] == "QUEUED"
    assert len(calls) == 1
    assert client.post(url, headers=owner).json()["state"] == "COMPLETE"
    assert client.post(url, headers=owner).json()["state"] == "COMPLETE"
    assert len(calls) == 2
    assert database["analysis_usage"].documents[0]["used"] == 1900


def test_interrupted_inflight_call_is_never_replayed(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    _, aid = seed(database, incident)
    job = client.post(f"/incidents/{incident}/analysis/jobs", headers=owner, json=body(aid)).json()
    doc = database["analysis_jobs"].documents[0]
    doc.update(state="RUNNING", leaseUntil=analysis.now() - timedelta(seconds=1))
    doc["stages"][0]["state"] = "INFLIGHT"
    monkeypatch.setattr(analysis, "invoke", lambda p: pytest.fail("must not replay ambiguous call"))
    response = client.post(f"/incidents/{incident}/analysis/jobs/{job['id']}/advance", headers=owner)
    assert response.status_code == 200
    assert doc["stages"][0]["state"] == "UNAVAILABLE"


def test_private_asset_expiry_owner_only_and_no_media_download(client, database, sign_in, s3):
    incident, owner, viewer, _ = setup(client, sign_in)
    eid, _ = seed(database, incident)
    base = f"/incidents/{incident}/analysis/assets"
    request = {"requestId": str(uuid4()), "evidenceId": eid, "kind": "frame", "fileSize": 100,
               "offsetMs": 1000, "sha256": "a" * 64, "consent": True}
    assert client.post(base, headers=viewer, json=request).status_code == 403
    grant = client.post(base, headers=owner, json=request).json()
    assert grant["expiresIn"] == 120
    assert s3.calls[-1][1]["Params"]["Bucket"] == "saaya-evidence"
    assert s3.calls[-1][1]["Params"]["Key"].startswith("analysis/")
    doc = database["analysis_assets"].documents[-1]
    doc["retryUntil"] = analysis.now() - timedelta(seconds=1)
    assert client.post(base, headers=owner, json=request).status_code == 410
    assert client.post(base + f"/{grant['assetId']}/complete", headers=owner).status_code == 410
    assert all(method != "get" for method, _ in s3.calls)


def test_analysis_rate_limit(database):
    for _ in range(12):
        analysis.rate_limit(database, "user")
    with pytest.raises(Exception) as error:
        analysis.rate_limit(database, "user")
    assert error.value.status_code == 429


def test_provider_exhaustion_pauses_account_and_does_not_reinvoke(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    _, aid = seed(database, incident)
    job = client.post(f"/incidents/{incident}/analysis/jobs", headers=owner, json=body(aid)).json()
    monkeypatch.setenv("AI_FREE_PLAN_CONFIRMED", "true")
    monkeypatch.setenv("AI_WORKER_URL", "https://fixture.workers.dev")
    monkeypatch.setattr(client.app.state.evidence_storage, "get_url", lambda d: "https://private-fixture")
    calls = []
    monkeypatch.setattr(analysis, "invoke", lambda p: calls.append(p) or {"status": "quota", "reason": "Free allocation exhausted"})
    url = f"/incidents/{incident}/analysis/jobs/{job['id']}/advance"
    result = client.post(url, headers=owner).json()
    assert result["state"] == "PAUSED"
    assert database["analysis_usage"].documents[0]["blocked"]
    assert not analysis.atomic_allow(database, "another-stage", 100, 8000, "owner")
    database["analysis_jobs"].documents[0]["retryAfter"] = analysis.now() - timedelta(seconds=1)
    assert client.post(url, headers=owner).json()["state"] == "PAUSED"
    assert len(calls) == 1


def test_partial_failure_keeps_report_and_discloses_unavailable_evidence(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    _, aid = seed(database, incident)
    job = client.post(f"/incidents/{incident}/analysis/jobs", headers=owner, json=body(aid)).json()
    monkeypatch.setenv("AI_FREE_PLAN_CONFIRMED", "true")
    monkeypatch.setenv("AI_WORKER_URL", "https://fixture.workers.dev")
    monkeypatch.setattr(client.app.state.evidence_storage, "get_url", lambda d: "https://private-fixture")
    results = iter([{"status": "uncertain"}, {"status": "ok", "result": {"uncertainties": ["Missing visual evidence"]},
                     "usage": {"prompt_tokens": 50, "completion_tokens": 10}}])
    monkeypatch.setattr(analysis, "invoke", lambda p: next(results))
    url = f"/incidents/{incident}/analysis/jobs/{job['id']}/advance"
    client.post(url, headers=owner)
    result = client.post(url, headers=owner).json()
    assert result["state"] == "PARTIAL"
    assert len(result["report"]["unavailable"]) == 1
    assert result["report"]["confidence"] == "unverified"
    assert database["analysis_jobs"].documents[0]["stages"][-1]["usage"]["prompt_tokens"] == 50


def test_standalone_verification_is_owner_only_pinned_and_never_enables_production_audio(client, database, sign_in, s3, monkeypatch):
    incident, owner, viewer, _ = setup(client, sign_in)
    base = f"/incidents/{incident}/analysis"
    request = {"requestId": str(uuid4()), "kind": "audio", "verificationOnly": True, "fileSize": 32044,
        "offsetMs": 0, "sha256": "b" * 64, "capturedAt": now_iso(), "consent": True}
    assert client.post(base + "/assets", headers=viewer, json=request).status_code == 403
    grant = client.post(base + "/assets", headers=owner, json=request).json()
    s3.head = {"ContentLength": 32044, "ContentType": "audio/wav", "VersionId": "pinned-original-wav",
               "Metadata": {"evidence-id": grant["assetId"]}}
    assert client.post(base + f"/assets/{grant['assetId']}/complete", headers=owner).status_code == 200
    aid = grant["assetId"]
    assert client.post(base + "/jobs", headers=owner, json=body(aid)).status_code == 422
    request = body(aid, verificationOnly=True)
    job = client.post(base + "/jobs", headers=owner, json=request).json()
    assert client.post(base + "/jobs", headers=owner, json=request).json()["id"] == job["id"]
    monkeypatch.setenv("AI_FREE_PLAN_CONFIRMED", "true")
    monkeypatch.setenv("AI_WORKER_URL", "https://fixture.workers.dev")
    monkeypatch.setenv("AI_ALLOW_AUDIO_VERIFICATION", "true")
    monkeypatch.setenv("AI_WHISPER_WAV_VERIFIED", "false")
    monkeypatch.setattr(client.app.state.evidence_storage, "get_url", lambda d: "https://private-fixture?versionId=" + d["versionId"])
    calls = []
    monkeypatch.setattr(analysis, "invoke", lambda p: calls.append(p) or {"status": "ok", "result": {"text": "hello", "words": []}})
    url = base + f"/jobs/{job['id']}/advance"
    assert client.post(url, headers=owner).json()["state"] == "COMPLETE"
    client.post(url, headers=owner)
    assert len(calls) == 1 and calls[0]["versionId"] == "pinned-original-wav"
    assert database["analysis_usage"].documents[0]["used"] == 100
    assert not client.get(base + "/config", headers=owner).json()["audioEnabled"]


def now_iso():
    return analysis.now().replace(microsecond=0).isoformat()


def test_verification_exception_cannot_enable_regular_incident_audio(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    _, aid = seed(database, incident)
    database["analysis_assets"].documents[0]["kind"] = "audio"
    job = client.post(f"/incidents/{incident}/analysis/jobs", headers=owner, json=body(aid)).json()
    monkeypatch.setenv("AI_WHISPER_WAV_VERIFIED", "false")
    monkeypatch.setenv("AI_ALLOW_AUDIO_VERIFICATION", "true")
    monkeypatch.setattr(client.app.state.evidence_storage, "get_url", lambda d: pytest.fail("Disabled audio must not request evidence access"))
    monkeypatch.setattr(analysis, "invoke", lambda p: pytest.fail("Production audio remains disabled"))
    result = client.post(f"/incidents/{incident}/analysis/jobs/{job['id']}/advance", headers=owner)
    assert result.status_code == 200
    assert database["analysis_jobs"].documents[0]["stages"][0]["state"] == "UNAVAILABLE"
    assert not database["analysis_usage"].documents


@pytest.mark.parametrize("size", [0, 44, 45, 960045, 960046])
def test_invalid_verification_asset_sizes_rejected(client, sign_in, size):
    incident, owner, _, _ = setup(client, sign_in)
    result = client.post(f"/incidents/{incident}/analysis/assets", headers=owner, json={"requestId": str(uuid4()),
        "kind": "audio", "verificationOnly": True, "fileSize": size, "offsetMs": 0, "sha256": "b" * 64,
        "capturedAt": now_iso(), "consent": True})
    assert result.status_code == 422
