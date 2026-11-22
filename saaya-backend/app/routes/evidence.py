from datetime import datetime, timedelta, timezone
from uuid import UUID

from bson import ObjectId
from fastapi import APIRouter, Depends, HTTPException, Request, Response, Query
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

from app.auth import CurrentUser
from app.database import get_database
from app.live_safety import authorized_incident

router = APIRouter(prefix="/incidents/{id}/evidence", tags=["Evidence"])


class Upload(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: UUID
    mediaType: str
    fileSize: int = Field(gt=0, strict=True)
    capturedAt: AwareDatetime | None = None

    @field_validator("capturedAt")
    @classmethod
    def mongo_timestamp(cls, value):
        # Mongo stores milliseconds. Normalize so exact request retries remain idempotent.
        return value.astimezone(timezone.utc).replace(microsecond=value.microsecond // 1000 * 1000) if value else None


def public(doc):
    return {"id": str(doc["_id"]), **{key: doc.get(key) for key in
        ("incidentId", "ownerId", "mediaType", "fileSize", "capturedAt", "uploadedAt")}}


def access(database, id, user, write=False):
    incident = authorized_incident(database, id, str(user["_id"]))
    if write:
        if incident["ownerId"] != str(user["_id"]):
            raise HTTPException(403, "Only the incident owner may upload evidence")
        if incident["status"] != "ACTIVE":
            raise HTTPException(409, "Incident ended. New uploads are not allowed.")
    return incident


def owned(database, id, evidence_id):
    if not ObjectId.is_valid(evidence_id):
        raise HTTPException(404, "Evidence not found")
    doc = database["evidence"].find_one({"_id": ObjectId(evidence_id), "incidentId": id})
    if doc is None:
        raise HTTPException(404, "Evidence not found")
    return doc


@router.post("/uploads")
def authorize(id: str, body: Upload, user: CurrentUser, request: Request, response: Response,
              database=Depends(get_database)):
    incident = access(database, id, user, write=True)
    storage = request.app.state.evidence_storage
    response.headers["Cache-Control"] = "no-store"
    if body.mediaType not in ("image/jpeg", "video/mp4"):
        raise HTTPException(422, "Only JPEG photos and MP4 videos are accepted")
    if body.fileSize > (storage.photo_max if body.mediaType == "image/jpeg" else storage.video_max):
        raise HTTPException(413, "Evidence file exceeds size limit")
    query = {"incidentId": id, "requestId": str(body.requestId)}
    doc = database["evidence"].find_one(query)
    now = datetime.now(timezone.utc)
    if doc is None:
        evidence_id = ObjectId()
        doc = {"_id": evidence_id, **query, "ownerId": incident["ownerId"], "state": "PENDING",
               "objectKey": f"evidence/{incident['ownerId']}/{id}/{evidence_id}",
               "mediaType": body.mediaType, "fileSize": body.fileSize, "capturedAt": body.capturedAt,
               "createdAt": now, "retryUntil": now + timedelta(hours=24)}
        # Generate before inserting: disabled storage must not create abandoned records.
        storage.put_url(doc)
        try:
            database["evidence"].insert_one(doc)
        except DuplicateKeyError:
            doc = database["evidence"].find_one(query)
    if doc["mediaType"] != body.mediaType or doc["fileSize"] != body.fileSize or doc.get("capturedAt") != body.capturedAt:
        raise HTTPException(409, "Retry must use the same file and capture timestamp")
    if doc["state"] == "COMPLETE":
        return {"evidenceId": str(doc["_id"]), "complete": True, "evidence": public(doc)}
    if doc["retryUntil"] < now:
        raise HTTPException(410, "Upload authorization abandoned. Local evidence has been preserved.")
    url = storage.put_url(doc)
    if database["incidents"].find_one_and_update({"_id": incident["_id"], "status": "ACTIVE"},
            {"$set": {"evidenceAuthorizedAt": now}}) is None:
        raise HTTPException(409, "Incident ended. New uploads are not allowed.")
    return {"evidenceId": str(doc["_id"]), "complete": False, "uploadUrl": url, "expiresIn": storage.ttl,
            "headers": {"Content-Type": doc["mediaType"], "x-amz-meta-evidence-id": str(doc["_id"])}}


@router.post("/{evidence_id}/complete")
def complete(id: str, evidence_id: str, user: CurrentUser, request: Request, response: Response,
             database=Depends(get_database)):
    incident = access(database, id, user)
    if incident["ownerId"] != str(user["_id"]):
        raise HTTPException(403, "Only the incident owner may confirm evidence")
    doc = owned(database, id, evidence_id)
    response.headers["Cache-Control"] = "no-store"
    if doc["state"] == "COMPLETE":
        return public(doc)
    access(database, id, user, write=True)
    if doc["retryUntil"] < datetime.now(timezone.utc):
        raise HTTPException(410, "Upload authorization abandoned")
    version, uploaded_at = request.app.state.evidence_storage.verify(doc)
    # Acceptance point ordered against incident termination, as in incident chat.
    if database["incidents"].find_one_and_update({"_id": incident["_id"], "status": "ACTIVE"},
            {"$set": {"evidenceAcceptedAt": datetime.now(timezone.utc)}}) is None:
        raise HTTPException(409, "Incident ended. Upload not accepted.")
    saved = database["evidence"].find_one_and_update({"_id": doc["_id"], "state": "PENDING"},
        {"$set": {"state": "COMPLETE", "versionId": version, "uploadedAt": uploaded_at}},
        return_document=ReturnDocument.AFTER)
    return public(saved or owned(database, id, evidence_id))


@router.get("")
def listing(id: str, user: CurrentUser, response: Response, before: str | None = Query(default=None),
            database=Depends(get_database)):
    incident = access(database, id, user)
    query = {"incidentId": id, "state": "COMPLETE"}
    if before:
        if not ObjectId.is_valid(before):
            raise HTTPException(400, "Invalid evidence cursor")
        query["_id"] = {"$lt": ObjectId(before)}
    docs = list(database["evidence"].find(query).sort([("_id", -1)]).limit(50))
    response.headers["Cache-Control"] = "no-store"
    return {"evidence": [public(doc) for doc in docs], "canUpload": incident["status"] == "ACTIVE" and
            incident["ownerId"] == str(user["_id"]), "nextBefore": str(docs[-1]["_id"]) if len(docs) == 50 else None}


@router.post("/{evidence_id}/download")
def download(id: str, evidence_id: str, user: CurrentUser, request: Request, response: Response,
             database=Depends(get_database)):
    access(database, id, user)
    doc = owned(database, id, evidence_id)
    if doc["state"] != "COMPLETE":
        raise HTTPException(404, "Evidence not found")
    storage = request.app.state.evidence_storage
    response.headers["Cache-Control"] = "no-store"
    return {"downloadUrl": storage.get_url(doc), "expiresIn": storage.ttl}
