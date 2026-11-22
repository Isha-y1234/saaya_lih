"""Authorized, bounded report snapshots. Android renders PDFs; no media is fetched here."""
import os
import re
from copy import deepcopy
from datetime import datetime, timedelta, timezone

from bson import ObjectId
from fastapi import APIRouter, Depends, HTTPException, Response
from pydantic import BaseModel, ConfigDict, Field

from app.auth import CurrentUser
from app.database import get_database
from app.live_safety import authorized_incident

router = APIRouter(prefix="/incidents/{id}/reports", tags=["Incident reports"])


class Options(BaseModel):
    model_config = ConfigDict(extra="forbid")
    includeChat: bool = False
    includeAudio: bool = False
    notes: str = Field(default="", max_length=2000)


def text(value, maximum=2000):
    # Report exports never carry URLs copied from source content or model output.
    return re.sub(r"https?://\S+", "[link omitted]", str(value or ""))[:maximum]


def stamp(value):
    return value.isoformat() if isinstance(value, datetime) else None


def access(db, id, user, response):
    response.headers["Cache-Control"] = "private, no-store"
    return authorized_incident(db, id, str(user["_id"]))


def snapshot(db, incident, options):
    id = str(incident["_id"])
    timeline = []
    missing = ["This report covers a bounded snapshot, not the complete circumstances.",
               "Frame sampling can miss events; device-reported capture times may be inaccurate."]
    for key, label in (("startedAt", "Incident started"), ("endedAt", "Incident ended")):
        if incident.get(key):
            timeline.append({"timestamp": stamp(incident[key]), "category": "Incident metadata", "text": label, "reference": id})
    evidence = list(db["evidence"].find({"incidentId": id, "state": "COMPLETE"}).sort([("_id", 1)]).limit(101))
    if len(evidence) > 100:
        missing.append("Evidence list limited to the first 100 records.")
    public_evidence = [{"id": str(e["_id"]), "mediaType": e.get("mediaType"), "fileSize": e.get("fileSize"),
                        "capturedAt": stamp(e.get("capturedAt")), "uploadedAt": stamp(e.get("uploadedAt"))} for e in evidence[:100]]
    if not public_evidence:
        missing.append("No confirmed evidence available in this snapshot.")
    for e in public_evidence:
        timeline.append({"timestamp": e["capturedAt"] or e["uploadedAt"], "category": "Evidence metadata",
                         "text": f"{e['mediaType']} evidence; capture time unverified", "reference": e["id"]})
    if not any(e["mediaType"] == "video/mp4" for e in public_evidence):
        missing.append("Video evidence unavailable in this snapshot.")
    if options.includeChat:
        chats = list(db["incident_messages"].find({"incidentId": id}).sort([("_id", -1)]).limit(101))
        if len(chats) > 100:
            missing.append("Chat limited to the latest 100 messages.")
        for c in chats[:100]:
            timeline.append({"timestamp": stamp(c.get("createdAt")), "category": "Participant statement (unverified)",
                             "text": text(c.get("text")), "reference": str(c["_id"])})
    else:
        missing.append("Chat excluded by report author.")
    audio_allowed = options.includeAudio and os.getenv("AI_WHISPER_WAV_VERIFIED") == "true"
    audio_found = False
    visual_found = False
    jobs = list(db["analysis_jobs"].find({"incidentId": id}).sort([("createdAt", -1)]).limit(20))
    seen = set()
    for j in jobs:
        if j.get("verificationOnly"):
            continue
        report = j.get("report") or {}
        if j.get("notes"):
            timeline.append({"timestamp": None, "category": "Analysis participant note (unverified)", "text": text(j["notes"]), "reference": str(j["_id"])})
        if j.get("state") not in ("COMPLETE", "PARTIAL"):
            missing.append(f"Analysis {j['_id']} is {j.get('state', 'unavailable')}.")
        for s in report.get("timeline", []):
            kind = s.get("kind")
            if kind not in ("frame", "audio"):
                continue
            identity = (s.get("assetId"), kind)
            if identity in seen:
                continue
            seen.add(identity)
            if s.get("state") != "COMPLETE":
                missing.append(f"{kind} analysis unavailable for reference {text(s.get('reference'), 100)}.")
                continue
            result = s.get("result") or {}
            if kind == "audio":
                if not audio_allowed or not j.get("consentedAt"):
                    continue
                transcript = text(result.get("text"), 12000)
                if not transcript:
                    continue
                audio_found = True
                timeline.append({"timestamp": s.get("timestamp"), "category": "AI transcript (unverified)",
                    "text": transcript, "reference": text(s.get("reference"), 100)})
                # Only provider-returned, validated word offsets are retained, never invented.
                for word in result.get("words", [])[:1000]:
                    start, end = word.get("start"), word.get("end")
                    if (isinstance(start, (int, float)) and isinstance(end, (int, float)) and 0 <= start <= end <= 30):
                        word_time = None
                        try:
                            if s.get("timestamp"):
                                word_time = (datetime.fromisoformat(s["timestamp"]) + timedelta(seconds=start)).isoformat()
                        except (ValueError, TypeError, OverflowError):
                            pass
                        timeline.append({"timestamp": word_time, "category": "AI word offset (seconds, recording-relative)",
                            "text": f"{start:g}–{end:g}: {text(word.get('word'), 200)}", "reference": text(s.get("reference"), 100)})
                if result.get("language"):
                    missing.append("Provider-reported audio language: " + text(result["language"], 30))
            else:
                visual_found = True
                for field, label in (("observations", "AI visual observation (unverified)"),
                                     ("interpretations", "AI interpretation (uncertain)"), ("uncertainties", "AI uncertainty")):
                    for item in result.get(field, [])[:30]:
                        if isinstance(item, str):
                            timeline.append({"timestamp": s.get("timestamp"), "category": label,
                                             "text": text(item), "reference": text(s.get("reference"), 100)})
    if not audio_found:
        missing.append("Audio transcript unavailable, excluded, not consented, or audio verification is disabled. This does not establish silence.")
    if not visual_found:
        missing.append("AI visual observations unavailable in this snapshot.")
    if len(jobs) == 20:
        missing.append("Analysis selection limited to the latest 20 jobs; older analysis may not be included.")
    timeline.sort(key=lambda item: (item.get("timestamp") is None, item.get("timestamp") or ""))
    if len(timeline) > 500:
        timeline = timeline[:500]
        missing.append("Timeline limited to 500 entries. Additional source entries are not included.")
    return {"incident": {"id": id, "status": incident["status"], "startedAt": stamp(incident.get("startedAt")),
            "endedAt": stamp(incident.get("endedAt")), "timezone": "UTC (source timestamps include offsets)"},
            "timeline": timeline, "evidence": public_evidence, "notes": text(options.notes),
            "disclosures": list(dict.fromkeys(missing)), "limitations": [
                "User-provided statements and AI output are unverified. No identity, event or legal conclusion is established by AI.",
                "This is a user-generated Saaya incident summary, not an official police report, forensic certification or legal finding.",
                "No guarantee of admissibility or legal outcome. General information is not legal advice.",
                "Saaya cannot revoke a PDF copy after you export or share it externally."]}


@router.post("")
def create(id: str, body: Options, user: CurrentUser, response: Response, db=Depends(get_database)):
    incident = access(db, id, user, response)
    from app.analysis import rate_limit
    rate_limit(db, str(user["_id"]))
    rid = ObjectId()
    report = {"id": str(rid), "generatedAt": datetime.now(timezone.utc).isoformat(), **snapshot(db, incident, body)}
    db["incident_reports"].insert_one({"_id": rid, "incidentId": id, "createdBy": str(user["_id"]),
        "createdAt": datetime.now(timezone.utc), "report": report})
    return report


@router.get("")
def listing(id: str, user: CurrentUser, response: Response, db=Depends(get_database)):
    access(db, id, user, response)
    return {"reports": [{"id": str(d["_id"]), "generatedAt": stamp(d["createdAt"])} for d in
            db["incident_reports"].find({"incidentId": id}).sort([("createdAt", -1)]).limit(20)]}


@router.get("/{rid}")
def read(id: str, rid: str, user: CurrentUser, response: Response, db=Depends(get_database)):
    access(db, id, user, response)
    doc = db["incident_reports"].find_one({"_id": ObjectId(rid), "incidentId": id}) if ObjectId.is_valid(rid) else None
    if not doc:
        raise HTTPException(404, "Report unavailable")
    report = deepcopy(doc["report"])
    if os.getenv("AI_WHISPER_WAV_VERIFIED") != "true":
        report["timeline"] = [line for line in report["timeline"] if not line["category"].startswith(("AI transcript", "AI word"))]
        report["disclosures"].append("Audio is currently disabled; any previously included transcript is withheld.")
    return report
