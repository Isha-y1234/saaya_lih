"""Private Activity evidence and consented analysis, independent of incidents."""
import hashlib
import base64
import binascii
import json
import os
from datetime import timedelta, timezone
from typing import Literal
from uuid import UUID

from bson import ObjectId
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, ValidationError, model_validator
from pymongo.errors import DuplicateKeyError
from starlette.concurrency import run_in_threadpool

from app import analysis, evidence_intelligence
from app.auth import CurrentUser
from app.database import get_database

router = APIRouter(prefix="/activity/evidence", tags=["Activity evidence"])
MIME = {"photo": "image/jpeg", "video": "video/mp4", "audio": "audio/wav"}


class Capture(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: UUID
    kind: Literal["photo", "video", "audio", "note"]
    capturedAt: AwareDatetime
    details: str = Field(default="", max_length=2000)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    fileSize: int = Field(default=0, ge=0, strict=True)

    @model_validator(mode="after")
    def validate_capture(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("Both location coordinates are required")
        if self.kind == "note":
            if self.fileSize or not self.details.strip():
                raise ValueError("A note requires text")
        elif not self.fileSize:
            raise ValueError("Media requires a file")
        return self


class Asset(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["frame", "audio"]
    fileSize: int = Field(gt=0, le=1000000)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    offsetMs: int = Field(ge=0, le=30000)
    consent: Literal[True]


class Run(BaseModel):
    model_config = ConfigDict(extra="forbid")
    assetIds: list[str] = Field(default_factory=list, max_length=5)
    consent: Literal[True]
    retry: bool = False


def private(response: Response):
    response.headers["Cache-Control"] = "private, no-store"


def indexes(db):
    db["activity_synthesis_results"].create_index("createdAt", expireAfterSeconds=604800)
    db["activity_evidence"].create_index([("ownerId", 1), ("requestId", 1)], unique=True)
    db["activity_evidence"].create_index([("ownerId", 1), ("state", 1), ("_id", -1)])
    db["activity_assets"].create_index([("evidenceId", 1), ("fingerprint", 1)], unique=True)


def owned(db, eid, user):
    item = db["activity_evidence"].find_one({"_id": ObjectId(eid), "ownerId": str(user["_id"])}) if ObjectId.is_valid(eid) else None
    if not item or item["state"] == "ARCHIVED":
        raise HTTPException(404, "Activity evidence not found")
    return item


def confirmed(db, eid, user):
    item = owned(db, eid, user)
    if item["state"] != "COMPLETE":
        raise HTTPException(409, "Finish uploading the capture first")
    return item


def public(item):
    keys = ("kind", "capturedAt", "details", "latitude", "longitude", "fileSize", "mediaType",
            "state", "uploadedAt", "requestId", "analysis")
    return {"id": str(item["_id"]), **{key: item.get(key) for key in keys}}


def grant(storage, item, key):
    result = {key: str(item["_id"]), "complete": item["state"] == "COMPLETE"}
    if not result["complete"]:
        if item["retryUntil"] < analysis.now():
            raise HTTPException(410, "Upload expired")
        result.update(uploadUrl=storage.put_url(item), headers={
            "Content-Type": item["mediaType"], "x-amz-meta-evidence-id": str(item["_id"])})
    return result


@router.post("")
def reserve(body: Capture, request: Request, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    storage = request.app.state.evidence_storage
    owner = str(user["_id"])
    query = {"ownerId": owner, "requestId": str(body.requestId)}
    captured = body.capturedAt.astimezone(timezone.utc)
    spec = body.model_dump(exclude={"requestId"})
    spec["capturedAt"] = captured.replace(microsecond=captured.microsecond // 1000 * 1000)
    maximum = {"photo": storage.photo_max, "video": storage.video_max, "audio": 960044, "note": 0}
    if body.fileSize > maximum[body.kind]:
        raise HTTPException(413, "Capture exceeds size limit")
    if body.kind == "audio" and (body.fileSize < 46 or body.fileSize % 2):
        raise HTTPException(422, "Invalid WAV size")
    collection = db["activity_evidence"]
    item = collection.find_one(query)
    if not item:
        identity = ObjectId()
        now = analysis.now()
        item = {"_id": identity, **query, **spec, "state": "COMPLETE" if body.kind == "note" else "PENDING",
                "mediaType": MIME.get(body.kind), "createdAt": now, "retryUntil": now + timedelta(hours=24),
                "objectKey": f"evidence/activity/{owner}/{identity}"}
        if body.kind != "note":
            storage.put_url(item)
        try:
            collection.insert_one(item)
        except DuplicateKeyError:
            item = collection.find_one(query)
    if item["state"] == "ARCHIVED":
        raise HTTPException(410, "Capture has been removed from Activity")
    if any(item[key] != value for key, value in spec.items()):
        raise HTTPException(409, "Retry must match the original capture")
    if item["state"] == "PENDING" and item["retryUntil"] < analysis.now():
        renewed = collection.find_one_and_update(
            {"_id": item["_id"], "state": "PENDING", "retryUntil": item["retryUntil"]},
            {"$set": {"retryUntil": analysis.now() + timedelta(hours=24)}})
        item = renewed or owned(db, str(item["_id"]), user)
    return {**grant(storage, item, "evidenceId"), "item": public(item)}


@router.get("")
def listing(user: CurrentUser, before: str | None = None, db=Depends(get_database), _=Depends(private)):
    query = {"ownerId": str(user["_id"]), "state": "COMPLETE"}
    if before:
        if not ObjectId.is_valid(before):
            raise HTTPException(400, "Invalid cursor")
        query["_id"] = {"$lt": ObjectId(before)}
    items = list(db["activity_evidence"].find(query).sort([("_id", -1)]).limit(50))
    return {"items": [public(item) for item in items], "nextBefore": str(items[-1]["_id"]) if len(items) == 50 else None}


@router.post("/{eid}/complete")
def complete(eid: str, request: Request, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    item = owned(db, eid, user)
    if item["state"] == "COMPLETE":
        return public(item)
    if item["retryUntil"] < analysis.now():
        raise HTTPException(410, "Upload expired")
    version, uploaded = request.app.state.evidence_storage.verify(item, wav=item["kind"] == "audio")
    db["activity_evidence"].find_one_and_update({"_id": item["_id"], "state": "PENDING"},
        {"$set": {"state": "COMPLETE", "versionId": version, "uploadedAt": uploaded}})
    return public(confirmed(db, eid, user))


@router.post("/{eid}/download")
def download(eid: str, request: Request, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    item = confirmed(db, eid, user)
    if item["kind"] == "note":
        raise HTTPException(404, "This record has no media")
    return {"downloadUrl": request.app.state.evidence_storage.get_url(item)}


@router.post("/{eid}/archive")
def archive(eid: str, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    item = owned(db, eid, user)
    db["activity_evidence"].find_one_and_update({"_id": item["_id"]}, {"$set": {"state": "ARCHIVED"}})
    return {"archived": True}


@router.get("/analysis-config")
def configuration(user: CurrentUser, _=Depends(private)):
    return {"audioEnabled": audio_enabled(), "synthesisProvider": evidence_intelligence.preferred_provider()}


def audio_enabled():
    return os.getenv("AI_WHISPER_WAV_VERIFIED") == "true"


class LocalAnalysis(BaseModel):
    model_config = ConfigDict(extra="forbid")
    kind: Literal["frame", "audio", "report"]
    consent: Literal[True]
    media: str | None = Field(default=None, max_length=1333336)
    context: str | None = Field(default=None, max_length=10000)
    synthesisProvider: evidence_intelligence.Provider = "cloudflare"
    synthesisConsent: evidence_intelligence.Provider | None = None


@router.post("/local-analysis")
async def local_analysis(request: Request, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    # Authenticate before reading a bounded body. The media is forwarded in memory and is never stored.
    body = bytearray()
    async for chunk in request.stream():
        if len(body) + len(chunk) > 1340000:
            raise HTTPException(413, "Analysis input exceeds size limit")
        body.extend(chunk)
    try:
        task = LocalAnalysis.model_validate_json(body)
    except ValidationError:
        raise HTTPException(422, "Invalid consent or local analysis input") from None
    if task.synthesisProvider != "cloudflare":
        if task.kind != "report" or task.synthesisConsent != task.synthesisProvider:
            raise HTTPException(403, "Explicit consent for the selected synthesis provider is required")
        if task.synthesisProvider != evidence_intelligence.preferred_provider():
            raise HTTPException(409, "The selected synthesis provider is not enabled")
        if not task.context or task.media is not None:
            raise HTTPException(422, "A text-only evidence context is required")
        try:
            evidence = evidence_intelligence.activity_evidence(task.context)
        except ValueError:
            raise HTTPException(422, "No valid source-linked evidence is available") from None
        owner = str(user["_id"])
        await run_in_threadpool(analysis.rate_limit, db, "activity:" + owner)
        return await evidence_intelligence.synthesize_once(db, owner, task.synthesisProvider, evidence)
    return await run_in_threadpool(process_local_analysis, db, str(user["_id"]), task)


def process_local_analysis(db, owner, task):
    kind = task.kind
    payload = {"kind": kind, "model": {"frame": analysis.VISION, "audio": analysis.AUDIO, "report": analysis.TEXT}[kind]}
    source_ids = set()
    if kind == "report":
        if task.media is not None or not task.context:
            raise HTTPException(422, "Report context is required")
        try:
            evidence = evidence_intelligence.activity_evidence(task.context)
        except ValueError:
            raise HTTPException(422, "No valid source-linked evidence is available") from None
        context = evidence.model_dump(mode="json", exclude={"location"})
        context["descriptionTruncated"] = json.loads(task.context).get("descriptionTruncated") is True
        payload["context"] = json.dumps(context, ensure_ascii=False)
        source_ids = {source.id for source in evidence.sources}
    else:
        if not task.media or task.context is not None:
            raise HTTPException(422, "Media input is required")
        if kind == "audio" and not audio_enabled():
            raise HTTPException(503, "Verified WAV transcription is disabled")
        try:
            decoded = base64.b64decode(task.media, validate=True)
        except (ValueError, binascii.Error):
            raise HTTPException(422, "Invalid media encoding") from None
        maximum = 960044 if kind == "audio" else 1000000
        if not 1 <= len(decoded) <= maximum:
            raise HTTPException(413, "Derived media exceeds size limit")
        payload.update(media=task.media, size=len(decoded), sha256=hashlib.sha256(decoded).hexdigest())
    analysis.cloud_configuration()
    analysis.rate_limit(db, "activity:" + owner)
    cost = {"frame": 700, "audio": 100, "report": 1200}[kind]
    if not analysis.atomic_allow(db, f"activity-local:{ObjectId()}", cost,
                                 analysis.limit("AI_DAILY_NEURONS", 8000, 1, 9000), owner):
        raise HTTPException(429, "Cloudflare analysis budget exhausted")
    result = analysis.invoke(payload, activity_local=True)
    if not isinstance(result, dict) or result.get("status") not in ("ok", "quota", "retry", "unavailable", "uncertain"):
        return {"status": "unavailable", "reason": "Cloudflare returned an invalid analysis response."}
    reason = result.get("reason")
    if not isinstance(reason, str):
        reason = None
    if result.get("status") == "quota":
        identity = os.getenv("CLOUDFLARE_ACCOUNT_ID", "unconfigured") + ":" + analysis.now().strftime("%Y-%m-%d")
        db["analysis_usage"].find_one_and_update({"_id": identity}, {"$set": {"blocked": True}})
    if result["status"] != "ok":
        return {"status": result["status"], "reason": reason}
    output = result.get("result")
    fields = ("observations", "interpretations", "uncertainties", "evidenceReferences")
    if not isinstance(output, dict) or (
        kind == "audio" and (not isinstance(output.get("text"), str) or not output["text"].strip())
    ) or (
        kind != "audio" and any(not isinstance(output.get(key), list) or
                                any(not isinstance(item, str) for item in output[key]) for key in fields)
    ):
        return {"status": "unavailable", "reason": "Cloudflare returned incomplete analysis findings."}
    if kind == "report":
        if not any(value.strip() for value in output["uncertainties"]):
            return {"status": "unavailable", "reason": "Cloudflare omitted uncertainty from the report."}
        output = {**output, "provider": "Cloudflare",
                  "evidenceReferences": list(dict.fromkeys(ref for ref in output["evidenceReferences"] if ref in source_ids))}
    return {"status": "ok", "reason": reason, "result": output}


@router.post("/{eid}/assets")
def asset_upload(eid: str, body: Asset, request: Request, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    item = confirmed(db, eid, user)
    allowed = {"photo": {"frame"}, "video": {"frame", "audio"}, "audio": {"audio"}, "note": set()}
    if body.kind not in allowed[item["kind"]]:
        raise HTTPException(422, "Asset type does not match the capture")
    if body.kind == "audio" and (not audio_enabled() or not 46 <= body.fileSize <= 960044):
        raise HTTPException(422, "Verified WAV transcription is unavailable or asset size is invalid")
    spec = body.model_dump(exclude={"consent"})
    fingerprint = hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()
    query = {"evidenceId": eid, "fingerprint": fingerprint}
    storage = request.app.state.evidence_storage
    collection = db["activity_assets"]
    asset = collection.find_one(query)
    if not asset:
        analysis.rate_limit(db, "activity:" + item["ownerId"])
        identity = ObjectId()
        asset = {"_id": identity, **query, **spec, "state": "PENDING",
                 "mediaType": "image/jpeg" if body.kind == "frame" else "audio/wav",
                 "retryUntil": analysis.now() + timedelta(hours=24),
                 "objectKey": f"analysis/activity/{item['ownerId']}/{eid}/{identity}"}
        storage.put_url(asset)
        try:
            collection.insert_one(asset)
        except DuplicateKeyError:
            asset = collection.find_one(query)
    if asset["state"] == "PENDING" and asset["retryUntil"] < analysis.now():
        renewed = collection.find_one_and_update(
            {"_id": asset["_id"], "state": "PENDING", "retryUntil": asset["retryUntil"]},
            {"$set": {"retryUntil": analysis.now() + timedelta(hours=24)}})
        asset = renewed or collection.find_one(query)
    return grant(storage, asset, "assetId")


def find_asset(db, eid, aid):
    asset = db["activity_assets"].find_one({"_id": ObjectId(aid), "evidenceId": eid}) if ObjectId.is_valid(aid) else None
    if not asset:
        raise HTTPException(404, "Activity analysis asset not found")
    return asset


@router.post("/{eid}/assets/{aid}/complete")
def asset_complete(eid: str, aid: str, request: Request, user: CurrentUser, db=Depends(get_database), _=Depends(private)):
    confirmed(db, eid, user)
    asset = find_asset(db, eid, aid)
    if asset["state"] != "COMPLETE":
        if asset["retryUntil"] < analysis.now():
            raise HTTPException(410, "Asset upload expired")
        storage = request.app.state.evidence_storage
        version, _ = storage.verify(asset, wav=asset["kind"] == "audio")
        db["activity_assets"].find_one_and_update({"_id": asset["_id"], "state": "PENDING"},
            {"$set": {"state": "COMPLETE", "versionId": version}})
    return {"assetId": aid, "complete": True}


@router.post("/{eid}/analysis")
def run(eid: str, body: Run, user: CurrentUser, request: Request, db=Depends(get_database), _=Depends(private)):
    item = confirmed(db, eid, user)
    ids = sorted(set(body.assetIds))
    assets = [find_asset(db, eid, aid) for aid in ids]
    if (len(ids) != len(body.assetIds) or any(a["state"] != "COMPLETE" for a in assets)
            or sum(a["kind"] == "audio" for a in assets) > 1 or sum(a["kind"] == "frame" for a in assets) > 4):
        raise HTTPException(422, "Invalid analysis assets")
    if not assets and item["kind"] != "note":
        raise HTTPException(422, "Prepare the media for analysis first")
    previous = item.get("analysis")
    now = analysis.now()
    if previous and previous["state"] in ("COMPLETE", "PARTIAL", "UNCERTAIN"):
        return public(item)
    if previous and previous["assetIds"] != ids:
        raise HTTPException(409, "Continue with the original analysis assets")
    if previous and previous["state"] == "RUNNING" and previous["leaseUntil"] > now:
        return public(item)
    if previous and previous.get("retryAfter") and previous["retryAfter"] > now:
        return public(item)
    analysis.rate_limit(db, "activity:" + item["ownerId"])
    failed = previous and previous["state"] == "FAILED"
    attempt = (previous or {}).get("attempt", 0)
    if failed and (not body.retry or attempt >= 2):
        return public(item)
    stages = [] if failed else (previous or {}).get("stages", [])
    if failed:
        attempt += 1
    state = {"assetIds": ids, "stages": stages, "state": "RUNNING", "leaseUntil": now + timedelta(seconds=120),
             "consentedAt": (previous or {}).get("consentedAt", now), "attempt": attempt}
    collection = db["activity_evidence"]
    query = {"_id": item["_id"], "state": "COMPLETE", "analysisRevision": item.get("analysisRevision")}
    revision = ObjectId()
    if not collection.find_one_and_update(query, {"$set": {"analysis": state, "analysisRevision": revision}}):
        return public(confirmed(db, eid, user))
    if previous and previous["state"] == "RUNNING":
        state.update(state="UNCERTAIN", reason="The previous call was interrupted. Refresh to review its status.")
    elif len(stages) < len(assets):
        asset = assets[len(stages)]
        kind = asset["kind"]
        try:
            if kind == "audio" and not audio_enabled():
                raise HTTPException(503, "Verified WAV transcription is disabled")
            analysis.cloud_configuration()
            storage = request.app.state.evidence_storage
            payload = {"kind": kind, "model": analysis.VISION if kind == "frame" else analysis.AUDIO,
                       "url": storage.get_url(asset), "size": asset["fileSize"], "sha256": asset["sha256"],
                       "versionId": asset["versionId"]}
            if not analysis.atomic_allow(db, f"activity:{item['_id']}:{asset['_id']}:{attempt}", 700 if kind == "frame" else 100,
                                         analysis.limit("AI_DAILY_NEURONS", 8000, 1, 9000), item["ownerId"]):
                raise HTTPException(429, "Cloudflare analysis budget exhausted")
            result = analysis.invoke(payload)
            if result.get("status") == "quota":
                identity = os.getenv("CLOUDFLARE_ACCOUNT_ID", "unconfigured") + ":" + now.strftime("%Y-%m-%d")
                db["analysis_usage"].find_one_and_update({"_id": identity}, {"$set": {"blocked": True}})
            if result.get("status") in ("quota", "retry"):
                state.update(state="PAUSED", reason=result.get("reason"))
            else:
                stages = [*stages, {"kind": kind, "offsetMs": asset["offsetMs"], "status": result.get("status"),
                                    "sourceTimestamp": (item["capturedAt"] + timedelta(milliseconds=asset["offsetMs"])).isoformat(),
                                    "result": result.get("result"), "reason": result.get("reason")}]
                state.update(state="READY", stages=stages)
        except HTTPException as error:
            state.update(state="PAUSED", reason=error.detail)
    else:
        if assets and not any(stage["status"] == "ok" for stage in stages):
            uncertain = any(stage["status"] == "uncertain" for stage in stages)
            state.update(state="UNCERTAIN" if uncertain else "FAILED",
                         reason="Cloudflare produced no usable media results. No interpretation was generated.")
        else:
            summarize(db, item, state)
    if state["state"] == "PAUSED":
        state["retryAfter"] = now + timedelta(seconds=30)
    collection.find_one_and_update({"_id": item["_id"], "state": "COMPLETE", "analysisRevision": revision},
                                   {"$set": {"analysis": state}})
    return public(confirmed(db, eid, user))


def summarize(db, item, state):
    stages = state["stages"]
    context = json.dumps({"evidenceId": str(item["_id"]), "kind": item["kind"],
                          "capturedAt": item["capturedAt"].isoformat(),
                          "notes": item["details"],
                          "coverage": "This is a standalone Activity capture. Notes are user statements. Only listed stages were processed.",
                          "stages": [{"kind": stage["kind"], "offsetMs": stage["offsetMs"],
                                      "sourceTimestamp": stage.get("sourceTimestamp"),
                                      "status": stage["status"],
                                      "unverifiedResult": json.dumps(stage.get("result"), ensure_ascii=False)[:500]}
                                     for stage in stages]}, ensure_ascii=False)
    missing_audio = item["kind"] == "video" and not any(
        stage["kind"] == "audio" and stage["status"] == "ok" for stage in stages)
    partial = missing_audio or any(stage["status"] != "ok" for stage in stages)
    coverage_reason = "Video audio was unavailable or omitted." if missing_audio else None
    try:
        analysis.cloud_configuration()
        if not analysis.atomic_allow(db, f"activity:{item['_id']}:report:{state['attempt']}", 1200,
                                     analysis.limit("AI_DAILY_NEURONS", 8000, 1, 9000), item["ownerId"]):
            raise HTTPException(429, "Cloudflare analysis budget exhausted")
        result = analysis.invoke({"kind": "report", "model": analysis.TEXT, "context": context})
        if result.get("status") == "ok":
            state.update(state="PARTIAL" if partial else "COMPLETE",
                         report=result["result"], reason=coverage_reason or result.get("reason"))
        elif result.get("status") in ("quota", "retry"):
            state.update(state="PAUSED", reason=result.get("reason"))
        elif result.get("status") == "uncertain":
            state.update(state="UNCERTAIN", reason=result.get("reason"))
        else:
            state.update(state="PARTIAL" if stages else "FAILED",
                         reason=result.get("reason") or "Cloudflare returned no usable analysis.")
    except HTTPException as error:
        state.update(state="PAUSED", reason=error.detail)
