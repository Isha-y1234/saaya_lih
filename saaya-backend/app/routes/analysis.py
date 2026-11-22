"""Consent, authorization and private derived-asset metadata only."""
from datetime import timedelta
from typing import Literal
from uuid import UUID

from bson import ObjectId
from botocore.exceptions import BotoCoreError, ClientError
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field
from pymongo.errors import DuplicateKeyError

from app import analysis
from app.auth import CurrentUser
from app.database import get_database
from app.live_safety import authorized_incident

router = APIRouter(prefix="/incidents/{id}/analysis", tags=["Incident analysis"])


class AssetUpload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: UUID
    evidenceId: str | None = None
    verificationOnly: bool = False
    capturedAt: AwareDatetime | None = None
    kind: Literal["frame", "audio"]
    fileSize: int = Field(gt=0, le=1000000)
    offsetMs: int = Field(ge=0, le=30000)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    consent: Literal[True]


class JobCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: UUID
    assetIds: list[str] = Field(min_length=1, max_length=5)
    notes: str = Field(default="", max_length=2000)
    consent: Literal[True]
    verificationOnly: bool = False


def access(db, id, user, response):
    response.headers["Cache-Control"] = "private, no-store"
    return authorized_incident(db, id, str(user["_id"]))


def owned_asset(db, id, aid):
    doc = db["analysis_assets"].find_one({"_id": ObjectId(aid), "incidentId": id}) if ObjectId.is_valid(aid) else None
    if not doc:
        raise HTTPException(404, "Analysis asset not found")
    return doc


@router.get("/config")
def config(id: str, user: CurrentUser, response: Response, db=Depends(get_database)):
    access(db, id, user, response)
    import os
    return {"maxFrames": analysis.limit("AI_MAX_FRAMES", 3, 1, 4), "maxEdge": 640,
            "maxDurationMs": 30000, "maxAssetBytes": 1000000,
            "verificationAllowed": os.getenv("AI_ALLOW_AUDIO_VERIFICATION") == "true",
            "audioEnabled": os.getenv("AI_WHISPER_WAV_VERIFIED") == "true"}


@router.post("/assets")
def upload(id: str, body: AssetUpload, user: CurrentUser, request: Request, response: Response, db=Depends(get_database)):
    incident = access(db, id, user, response)
    if incident["ownerId"] != str(user["_id"]):
        raise HTTPException(403, "Only the incident owner may prepare analysis assets")
    evidence = db["evidence"].find_one({"_id": ObjectId(body.evidenceId), "incidentId": id, "state": "COMPLETE"}) if body.evidenceId and ObjectId.is_valid(body.evidenceId) else None
    if body.verificationOnly:
        if body.evidenceId or body.kind != "audio" or body.offsetMs != 0 or not body.capturedAt:
            raise HTTPException(422, "Verification requires a standalone consented audio sample and capture timestamp")
        if body.fileSize < 46 or body.fileSize > 960044 or (body.fileSize - 44) % 2:
            raise HTTPException(422, "Invalid WAV size")
    elif not evidence:
        raise HTTPException(404, "Original evidence unavailable")
    if body.kind == "audio" and evidence and evidence["mediaType"] != "video/mp4":
        raise HTTPException(422, "Audio requires original video")
    if body.kind == "audio" and not 46 <= body.fileSize <= 960044:
        raise HTTPException(422, "Audio exceeds WAV limits")
    query = {"incidentId": id, "requestId": str(body.requestId)}
    spec = body.model_dump(exclude={"consent", "requestId"})
    doc = db["analysis_assets"].find_one(query)
    storage = request.app.state.evidence_storage
    if doc is None:
        analysis.rate_limit(db, str(user["_id"]))
        aid = ObjectId()
        doc = {"_id": aid, **query, **spec, "ownerId": incident["ownerId"], "state": "PENDING",
               "mediaType": "image/jpeg" if body.kind == "frame" else "audio/wav",
               "objectKey": f"analysis/{incident['ownerId']}/{id}/{aid}",
               "capturedAt": evidence.get("capturedAt") if evidence else body.capturedAt, "createdAt": analysis.now(),
               "retryUntil": analysis.now() + timedelta(hours=24)}
        storage.put_url(doc)
        try:
            db["analysis_assets"].insert_one(doc)
        except DuplicateKeyError:
            doc = db["analysis_assets"].find_one(query)
    if any(doc.get(k, False if k == "verificationOnly" else None) != v for k, v in spec.items() if k != "capturedAt") or (body.verificationOnly and doc.get("capturedAt") != body.capturedAt):
        raise HTTPException(409, "Asset retry must match its original content")
    if doc["state"] == "COMPLETE":
        return {"assetId": str(doc["_id"]), "complete": True}
    if doc["retryUntil"] < analysis.now():
        raise HTTPException(410, "Analysis asset upload expired")
    return {"assetId": str(doc["_id"]), "complete": False, "uploadUrl": storage.put_url(doc), "expiresIn": storage.ttl,
            "headers": {"Content-Type": doc["mediaType"], "x-amz-meta-evidence-id": str(doc["_id"])}}


@router.post("/assets/{aid}/complete")
def complete(id: str, aid: str, user: CurrentUser, request: Request, response: Response, db=Depends(get_database)):
    incident = access(db, id, user, response)
    if incident["ownerId"] != str(user["_id"]):
        raise HTTPException(403, "Only the owner may confirm analysis assets")
    doc = owned_asset(db, id, aid)
    if doc["state"] == "COMPLETE":
        return {"assetId": aid, "complete": True}
    if doc["retryUntil"] < analysis.now():
        raise HTTPException(410, "Analysis asset upload expired")
    storage = request.app.state.evidence_storage
    try:
        head = storage.client().head_object(Bucket=storage.bucket, Key=doc["objectKey"])
    except (ClientError, BotoCoreError):
        raise HTTPException(503, "Analysis asset verification unavailable") from None
    if (head.get("ContentLength") != doc["fileSize"] or head.get("ContentType") != doc["mediaType"] or
        head.get("Metadata", {}).get("evidence-id") != aid or not head.get("VersionId") or head["VersionId"] == "null"):
        raise HTTPException(409, "Analysis asset metadata mismatch")
    db["analysis_assets"].find_one_and_update({"_id": doc["_id"], "state": "PENDING"},
        {"$set": {"state": "COMPLETE", "versionId": head["VersionId"]}})
    return {"assetId": aid, "complete": True}


@router.post("/jobs")
def create(id: str, body: JobCreate, user: CurrentUser, response: Response, db=Depends(get_database)):
    incident = access(db, id, user, response)
    if len(set(body.assetIds)) != len(body.assetIds):
        raise HTTPException(422, "Duplicate assets")
    assets = [owned_asset(db, id, a) for a in body.assetIds]
    if any(bool(a.get("verificationOnly")) != body.verificationOnly for a in assets):
        raise HTTPException(422, "Verification samples cannot be mixed with incident analysis")
    if body.verificationOnly and (incident["ownerId"] != str(user["_id"]) or len(assets) != 1):
        raise HTTPException(403, "Only the owner may test one consented sample")
    if sum(a["kind"] == "frame" for a in assets) > 4 or sum(a["kind"] == "audio" for a in assets) > 1:
        raise HTTPException(422, "At most four frames and one audio chunk per report")
    return analysis.public(analysis.create(db, incident, str(user["_id"]), body))


@router.get("/jobs")
def listing(id: str, user: CurrentUser, response: Response, db=Depends(get_database)):
    access(db, id, user, response)
    return {"jobs": [analysis.public(d) for d in db["analysis_jobs"].find({"incidentId": id}).sort([("createdAt", -1)]).limit(20)]}


def job(db, id, jid):
    doc = db["analysis_jobs"].find_one({"_id": ObjectId(jid), "incidentId": id}) if ObjectId.is_valid(jid) else None
    if not doc:
        raise HTTPException(404, "Analysis job unavailable")
    return doc


@router.get("/jobs/{jid}")
def read(id: str, jid: str, user: CurrentUser, response: Response, db=Depends(get_database)):
    access(db, id, user, response)
    return analysis.public(job(db, id, jid))


@router.post("/jobs/{jid}/advance")
def advance(id: str, jid: str, user: CurrentUser, request: Request, response: Response, db=Depends(get_database)):
    access(db, id, user, response)
    analysis.rate_limit(db, str(user["_id"]))
    return analysis.public(analysis.advance(db, request.app.state.evidence_storage, job(db, id, jid)))
