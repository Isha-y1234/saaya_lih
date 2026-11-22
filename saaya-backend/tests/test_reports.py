from datetime import timedelta

from bson import ObjectId

from app.analysis import now
from test_analysis import usage  # noqa: F401; atomic rate-limit fixture
from test_evidence import setup


def test_report_authorization_revocation_isolation_and_ended_access(client, database, sign_in):
    incident, owner, viewer, contact = setup(client, sign_in)
    base = f"/incidents/{incident}/reports"
    _, stranger, _ = sign_in("report-stranger")
    assert client.post(base, json={}).status_code == 401
    assert client.post(base, headers=stranger, json={}).status_code == 404
    client.patch(f"/incidents/{incident}/end", headers=owner)
    result = client.post(base, headers=owner, json={"includeChat": True, "notes": "User account of events"})
    assert result.status_code == 200, result.text
    report = result.json()
    assert report["incident"]["status"] == "ENDED"
    assert report["incident"]["endedAt"]
    url = base + "/" + report["id"]
    assert client.get(url, headers=viewer).status_code == 200
    assert client.get(url, headers=stranger).status_code == 404
    assert "no-store" in client.get(url, headers=owner).headers["cache-control"]
    client.delete(f"/notification-links/{contact['_id']}", headers=owner)
    assert client.get(url, headers=viewer).status_code == 404
    other = client.post("/incidents", headers=stranger, json={}).json()["id"]
    assert client.get(f"/incidents/{other}/reports/{report['id']}", headers=stranger).status_code == 404
    assert client.get(base, headers=owner).json()["reports"][0]["id"] == report["id"]


def test_report_snapshot_chronology_disclosures_and_no_storage_credentials(client, database, sign_in):
    incident, owner, _, _ = setup(client, sign_in)
    time = now()
    database["incident_messages"].insert_one({"incidentId": incident, "createdAt": time,
        "text": "Later statement https://private.backblazeb2.com/signed?secret=hidden"})
    database["incident_messages"].insert_one({"incidentId": incident, "createdAt": time - timedelta(seconds=20), "text": "Earlier statement"})
    evidence = ObjectId()
    database["evidence"].insert_one({"_id": evidence, "incidentId": incident, "state": "COMPLETE", "mediaType": "image/jpeg",
        "fileSize": 100, "capturedAt": time, "uploadedAt": time, "objectKey": "INTERNAL-OBJECT-KEY", "versionId": "INTERNAL-VERSION"})
    result = client.post(f"/incidents/{incident}/reports", headers=owner, json={"includeChat": True}).json()
    assert result["generatedAt"]
    stamps = [line["timestamp"] for line in result["timeline"] if line["timestamp"]]
    assert stamps == sorted(stamps)
    raw = str(result)
    assert "INTERNAL" not in raw and "secret=hidden" not in raw and "backblazeb2.com" not in raw
    assert any("Audio transcript unavailable" in s for s in result["disclosures"])
    assert any("Video evidence unavailable" in s for s in result["disclosures"])
    assert any("AI visual" in s for s in result["disclosures"])
    assert database["analysis_usage"].documents == []  # Reporting itself never invokes AI.


def test_chat_requires_opt_in_and_verification_samples_never_enter_reports(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    database["incident_messages"].insert_one({"incidentId": incident, "createdAt": now(), "text": "private-chat-fixture"})
    database["analysis_jobs"].insert_one({"incidentId": incident, "createdAt": now(), "state": "COMPLETE", "verificationOnly": True,
        "report": {"timeline": [{"kind": "audio", "state": "COMPLETE", "result": {"text": "verification-fixture"}}]}})
    monkeypatch.setenv("AI_WHISPER_WAV_VERIFIED", "true")
    result = client.post(f"/incidents/{incident}/reports", headers=owner, json={"includeAudio": True}).json()
    assert "private-chat-fixture" not in str(result)
    assert "verification-fixture" not in str(result)


def test_audio_requires_both_consent_and_enabled_gate_and_can_be_withheld_on_retrieval(client, database, sign_in, monkeypatch):
    incident, owner, _, _ = setup(client, sign_in)
    stage = {"kind": "audio", "state": "COMPLETE", "assetId": "sample", "timestamp": now().isoformat(),
             "reference": "evidence", "result": {"text": "नमस्ते fixture", "words": [{"word": "नमस्ते", "start": 0.1, "end": 0.5}]}}
    job = {"incidentId": incident, "createdAt": now(), "state": "COMPLETE", "consentedAt": now(), "report": {"timeline": [stage]}}
    database["analysis_jobs"].insert_one(job)
    base = f"/incidents/{incident}/reports"
    monkeypatch.setenv("AI_WHISPER_WAV_VERIFIED", "false")
    assert "नमस्ते fixture" not in str(client.post(base, headers=owner, json={"includeAudio": True}).json())
    monkeypatch.setenv("AI_WHISPER_WAV_VERIFIED", "true")
    result = client.post(base, headers=owner, json={"includeAudio": True}).json()
    assert "नमस्ते fixture" in str(result)
    words = [line for line in result["timeline"] if line["category"].startswith("AI word")]
    assert words and words[0]["timestamp"] is not None
    monkeypatch.setenv("AI_WHISPER_WAV_VERIFIED", "false")
    assert "नमस्ते fixture" not in str(client.get(base + "/" + result["id"], headers=owner).json())
