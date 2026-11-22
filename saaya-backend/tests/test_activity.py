import json
import base64
import hashlib
from datetime import datetime, timezone
from uuid import uuid4

import pytest

from app import analysis
from test_evidence import s3

BASE = "/activity/evidence"


def test_local_media_analysis_requires_owner_session_and_consent_without_storage(client, database, sign_in, monkeypatch):
    _, owner, _ = sign_in("local-activity")
    monkeypatch.setattr(analysis, "cloud_configuration", lambda: None)
    calls = []

    def invoke(payload, *, activity_local):
        assert activity_local
        calls.append(payload)
        return {"status": "ok", "result": {"observations": ["fixture"], "interpretations": [],
                                            "uncertainties": [], "evidenceReferences": []}}

    monkeypatch.setattr(analysis, "invoke", invoke)
    media = b"\xff\xd8\xfffixture"
    body = {"kind": "frame", "media": base64.b64encode(media).decode(), "consent": True}
    path = BASE + "/local-analysis"
    assert client.post(path, json=body).status_code == 401
    assert client.post(path, headers=owner, json={**body, "consent": False}).status_code == 422
    assert client.post(path, headers=owner, json={**body, "media": "not base64"}).status_code == 422
    assert client.post(path, headers=owner, json=body).json()["status"] == "ok"
    assert calls == [{"kind": "frame", "model": analysis.VISION, "media": body["media"],
                     "size": len(media), "sha256": hashlib.sha256(media).hexdigest()}]
    assert not database["activity_evidence"].documents
    assert not database["activity_assets"].documents
    assert not database["incidents"].documents


def capture(kind="note", **changes):
    return {"kind": kind, "requestId": str(uuid4()), "capturedAt": "2026-11-22T12:00:00.123456Z",
            "details": "Private user statement", **changes}


def test_note_without_incident_owner_only_and_idempotent(client, database, sign_in):
    _, owner, _ = sign_in("activity-owner")
    _, stranger, _ = sign_in("activity-stranger")
    body = capture(latitude=25.0, longitude=76.0)
    assert client.post(BASE, json=body).status_code == 401
    response = client.post(BASE, headers=owner, json=body)
    assert response.status_code == 200, response.text
    assert response.headers["Cache-Control"] == "private, no-store"
    item = response.json()["item"]
    assert item["latitude"] == 25 and item["longitude"] == 76
    assert client.post(BASE, headers=owner, json=body).json()["item"] == item
    assert client.post(BASE, headers=owner, json={**body, "details": "different"}).status_code == 409
    assert client.get(BASE, headers=owner).json()["items"] == [item]
    assert client.get(BASE, headers=stranger).json()["items"] == []
    for operation in ("download", "archive", "complete", "analysis", "assets"):
        payload = {"consent": True} if operation == "analysis" else None
        if operation == "assets":
            payload = {"kind": "frame", "fileSize": 32, "sha256": "1" * 64, "offsetMs": 0, "consent": True}
        assert client.post(f"{BASE}/{item['id']}/{operation}", headers=stranger, json=payload).status_code == 404
    assert client.post(f"{BASE}/{item['id']}/archive", headers=owner).status_code == 200
    assert not client.get(BASE, headers=owner).json()["items"]
    assert not database["incidents"].documents and not database["evidence"].documents


@pytest.mark.parametrize("kind,mime,prefix", [
    ("photo", "image/jpeg", b"\xff\xd8\xff"),
    ("video", "video/mp4", b"0000ftyp0000"),
    ("audio", "audio/wav", b"RIFF0000WAVE"),
])
def test_media_retry_and_version_pinned_download(client, database, sign_in, s3, kind, mime, prefix):
    _, owner, _ = sign_in("activity-media")
    body = capture(kind, fileSize=46)
    grant = client.post(BASE, headers=owner, json=body).json()
    assert not grant["complete"] and grant["headers"]["Content-Type"] == mime
    eid = grant["evidenceId"]
    assert client.post(f"{BASE}/{eid}/complete", headers=owner).status_code == 409
    assert client.post(BASE, headers=owner, json=body).json()["evidenceId"] == eid
    s3.head = {"ContentLength": 46, "ContentType": mime, "VersionId": "fixture-version",
               "Metadata": {"evidence-id": eid}, "LastModified": datetime.now(timezone.utc)}
    s3.prefix = b"invalid"
    assert client.post(f"{BASE}/{eid}/complete", headers=owner).status_code == 409
    s3.prefix = prefix.ljust(32, b"0")
    saved = client.post(f"{BASE}/{eid}/complete", headers=owner)
    assert saved.status_code == 200, saved.text
    assert saved.json()["uploadedAt"] and "objectKey" not in saved.json()
    assert client.post(BASE, headers=owner, json=body).json()["complete"]
    assert client.post(f"{BASE}/{eid}/download", headers=owner).status_code == 200
    params = s3.calls[-1][1]["Params"]
    assert params["Bucket"] == "saaya-evidence" and params["VersionId"] == "fixture-version"
    assert params["Key"].startswith("evidence/activity/")
    assert len(database["activity_evidence"].documents) == 1


def test_note_analysis_reuses_cloudflare_report_without_incident(client, sign_in, monkeypatch):
    _, owner, _ = sign_in("activity-analysis")
    item = client.post(BASE, headers=owner, json=capture()).json()["item"]
    path = f"{BASE}/{item['id']}/analysis"
    assert client.post(path, headers=owner, json={"consent": False}).status_code == 422
    expected = {"observations": [], "interpretations": [], "uncertainties": ["Only a user statement was supplied"],
                "evidenceReferences": [item["id"]], "confidence": "unverified"}
    monkeypatch.setattr(analysis, "cloud_configuration", lambda: None)

    def invoke(payload):
        assert payload["kind"] == "report" and payload["model"] == analysis.TEXT
        assert json.loads(payload["context"])["evidenceId"] == item["id"]
        return {"status": "ok", "result": expected}

    monkeypatch.setattr(analysis, "invoke", invoke)
    result = client.post(path, headers=owner, json={"consent": True}).json()["analysis"]
    assert result["state"] == "COMPLETE"
    assert result["report"] == expected


def test_activity_vision_checkpoint_is_independent_of_incident_jobs(client, database, sign_in, s3, monkeypatch):
    _, owner, _ = sign_in("activity-vision")
    grant = client.post(BASE, headers=owner, json=capture("photo", fileSize=32)).json()
    parent = f"{BASE}/{grant['evidenceId']}"
    s3.head = {"ContentLength": 32, "ContentType": "image/jpeg", "VersionId": "verified",
               "Metadata": {"evidence-id": grant["evidenceId"]}, "LastModified": datetime.now(timezone.utc)}
    assert client.post(parent + "/complete", headers=owner).status_code == 200
    body = {"kind": "frame", "fileSize": 32, "offsetMs": 0, "sha256": "1" * 64, "consent": True}
    asset = client.post(parent + "/assets", headers=owner, json=body).json()["assetId"]
    s3.head["Metadata"]["evidence-id"] = asset
    assert client.post(parent + f"/assets/{asset}/complete", headers=owner).status_code == 200
    monkeypatch.setattr(analysis, "cloud_configuration", lambda: None)
    calls = []

    def invoke(payload):
        calls.append(payload)
        return {"status": "ok", "result": {"observations": ["One frame"]}}

    monkeypatch.setattr(analysis, "invoke", invoke)
    run = {"assetIds": [asset], "consent": True}
    ready = client.post(parent + "/analysis", headers=owner, json=run).json()["analysis"]
    assert ready["state"] == "READY"
    assert ready["stages"][0]["sourceTimestamp"] == "2026-11-22T12:00:00.123000+00:00"
    waiting = client.post(parent + "/analysis", headers=owner, json=run).json()["analysis"]
    assert waiting["state"] == "COMPLETE"
    assert len(calls) == 2 and calls[0]["model"] == analysis.VISION and calls[0]["versionId"] == "verified"
    assert calls[1]["model"] == analysis.TEXT
    assert not database["analysis_jobs"].documents and not database["analysis_assets"].documents


def test_definite_failure_requires_explicit_retry(client, sign_in, monkeypatch):
    _, owner, _ = sign_in("activity-retry")
    item = client.post(BASE, headers=owner, json=capture()).json()["item"]
    path = f"{BASE}/{item['id']}/analysis"
    monkeypatch.setattr(analysis, "cloud_configuration", lambda: None)
    calls = []

    def invoke(payload):
        calls.append(payload)
        return {"status": "unavailable", "reason": "Fixture failure"}

    monkeypatch.setattr(analysis, "invoke", invoke)
    consent = {"consent": True}
    assert client.post(path, headers=owner, json=consent).json()["analysis"]["state"] == "FAILED"
    assert client.post(path, headers=owner, json=consent).json()["analysis"]["attempt"] == 0
    assert len(calls) == 1
    assert client.post(path, headers=owner, json={**consent, "retry": True}).json()["analysis"]["attempt"] == 1
    assert len(calls) == 2


def test_adaption_is_used_only_after_provider_consent_and_is_idempotent(client, sign_in, monkeypatch):
    from app import evidence_intelligence

    _, owner, _ = sign_in("activity-adaption")
    calls = []

    async def infer(evidence):
        calls.append(evidence)
        return {"status": "ok", "result": {"provider": "Adaption pretrained"}}

    monkeypatch.setattr(evidence_intelligence, "adaption_report", infer)
    monkeypatch.setenv("AI_SYNTHESIS_PROVIDER", "adaption")
    monkeypatch.setenv("ADAPTION_API_KEY", "fixture-key")
    context = json.dumps({"kind": "note", "evidenceId": str(uuid4()), "userDescription": "Mujhe yaad nahi.",
                          "sources": []})
    path = BASE + "/local-analysis"
    body = {"kind": "report", "context": context, "consent": True, "synthesisProvider": "adaption"}
    assert client.post(path, json=body).status_code == 401
    assert client.post(path, headers=owner, json=body).status_code == 403
    assert client.post(path, headers=owner, json={**body, "synthesisProvider": "checkpoint",
                       "synthesisConsent": "checkpoint"}).status_code == 409
    assert not calls
    body["synthesisConsent"] = "adaption"
    first = client.post(path, headers=owner, json=body)
    assert first.json()["result"]["provider"] == "Adaption pretrained"
    assert client.post(path, headers=owner, json=body).json() == first.json()
    assert len(calls) == 1

    monkeypatch.setattr(analysis, "cloud_configuration", lambda: None)
    def cloud(payload, *, activity_local):
        assert activity_local and payload["model"] == analysis.TEXT
        assert json.loads(payload["context"])["sources"][0]["id"] == "note"
        return {"status": "ok", "result": {"observations": [], "interpretations": [],
                                            "uncertainties": ["Fixture"], "evidenceReferences": ["note", "unknown"]}}
    monkeypatch.setattr(analysis, "invoke", cloud)
    explicit_cloud = {**body, "synthesisProvider": "cloudflare", "synthesisConsent": "cloudflare"}
    output = client.post(path, headers=owner, json=explicit_cloud).json()["result"]
    assert output["provider"] == "Cloudflare"
    assert output["evidenceReferences"] == ["note"]
    assert len(calls) == 1


def test_checkpoint_config_requires_verified_persistent_host(client, sign_in, monkeypatch):
    _, headers, _ = sign_in("checkpoint-config")
    monkeypatch.setenv("AI_SYNTHESIS_PROVIDER", "checkpoint")
    monkeypatch.setenv("ACTIVITY_INFERENCE_TOKEN", "fixture-token")
    monkeypatch.setenv("ACTIVITY_INFERENCE_CHECKPOINT", "fixture-alias")
    monkeypatch.setenv("ACTIVITY_INFERENCE_URL", "https://inference.example/v1/chat/completions")
    monkeypatch.delenv("ACTIVITY_INFERENCE_VERIFIED", raising=False)
    path = BASE + "/analysis-config"
    assert client.get(path, headers=headers).json()["synthesisProvider"] == "cloudflare"
    monkeypatch.setenv("ACTIVITY_INFERENCE_VERIFIED", "true")
    assert client.get(path, headers=headers).json()["synthesisProvider"] == "checkpoint"
    monkeypatch.setenv("ACTIVITY_INFERENCE_URL", "https://colab.research.google.com/v1/chat/completions")
    assert client.get(path, headers=headers).json()["synthesisProvider"] == "cloudflare"


def test_consented_checkpoint_retries_busy_host_and_returns_grounded_report(client, sign_in, monkeypatch):
    from unittest.mock import MagicMock
    from app import activity_adaption

    _, headers, _ = sign_in("activity-trained-checkpoint")
    monkeypatch.setenv("AI_SYNTHESIS_PROVIDER", "checkpoint")
    monkeypatch.setenv("ACTIVITY_INFERENCE_VERIFIED", "true")
    monkeypatch.setenv("ACTIVITY_INFERENCE_URL", "https://inference.example/v1/chat/completions")
    monkeypatch.setenv("ACTIVITY_INFERENCE_TOKEN", "fixture-token")
    monkeypatch.setenv("ACTIVITY_INFERENCE_CHECKPOINT", "saaya-adapter")
    report = {"claims": [{"category": "reported", "text": "Synthetic note", "sourceId": "note",
                           "quote": "Synthetic note", "offsetMs": None}],
              "uncertainties": ["Time is unknown."], "abstain": True}
    responses = []
    for status, alias in [(503, "saaya-adapter"), (200, "different-model"), (200, "saaya-adapter")]:
        response = MagicMock()
        response.__enter__.return_value.status_code = status
        response.__enter__.return_value.iter_content.return_value = [json.dumps({
            "model": alias, "choices": [{"message": {"content": json.dumps(report)}}],
        }).encode()]
        responses.append(response)
    upstream = MagicMock(side_effect=responses)
    monkeypatch.setattr(activity_adaption.requests, "post", upstream)
    cf = MagicMock(side_effect=AssertionError("Unexpected Cloudflare synthesis fallback"))
    monkeypatch.setattr(analysis, "invoke", cf)
    payload = {"kind": "report", "consent": True, "synthesisProvider": "checkpoint",
               "context": json.dumps({"kind": "note", "evidenceId": str(uuid4()),
                                      "userDescription": "Synthetic note", "sources": []})}
    path = BASE + "/local-analysis"
    assert client.post(path, headers=headers, json=payload).status_code == 403
    assert upstream.call_count == 0
    payload["synthesisConsent"] = "checkpoint"
    assert client.post(path, headers=headers, json=payload).json()["status"] == "retry"
    assert client.post(path, headers=headers, json=payload).json()["status"] == "unavailable"
    result = client.post(path, headers=headers, json=payload).json()
    assert result["result"]["groundedReport"] == report
    assert result["result"]["provider"] == "Adaption-trained SAAYA"
    assert client.post(path, headers=headers, json=payload).json() == result
    assert upstream.call_count == 3 and not cf.called
    assert upstream.call_args.kwargs["json"]["model"] == "saaya-adapter"
    assert "media" not in upstream.call_args.kwargs["json"]


def test_local_report_rejects_invalid_context_and_incomplete_provider_response(client, database, sign_in, monkeypatch):
    _, headers, _ = sign_in("local-report-contract")
    monkeypatch.setattr(analysis, "cloud_configuration", lambda: None)
    monkeypatch.setattr(analysis, "invoke", lambda payload, *, activity_local: {"status": "ok", "result": {}})
    body = {"kind": "report", "consent": True}
    path = BASE + "/local-analysis"
    assert client.post(path, headers=headers, json={**body, "context": "{}"}).status_code == 422
    note = json.dumps({"kind": "note", "evidenceId": str(uuid4()), "userDescription": "Synthetic note", "sources": []})
    result = client.post(path, headers=headers, json={**body, "context": note}).json()
    assert result == {"status": "unavailable", "reason": "Cloudflare returned incomplete analysis findings."}
    assert not database["incidents"].documents and not database["activity_evidence"].documents
