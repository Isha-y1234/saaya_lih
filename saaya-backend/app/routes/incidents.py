from datetime import datetime, timezone

from bson import ObjectId
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request, Response
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

from app.auth import CurrentUser
from app.database import get_database
from app.models.incident import IncidentCreate, IncidentResponse, IncidentsResponse, serialize_incident
from app.notifications import notify_sos_safely
from app.live_safety import prepare_live_safely, close_live_safely


router = APIRouter(prefix="/incidents", tags=["Incidents"])


def incident_id(value: str) -> ObjectId:
    if not ObjectId.is_valid(value):
        raise HTTPException(400, "Invalid incident ID")
    return ObjectId(value)


@router.post("", response_model=IncidentResponse, status_code=201)
def create_incident(body: IncidentCreate, response: Response, request: Request, background_tasks: BackgroundTasks,
                    user: CurrentUser, database=Depends(get_database)):
    collection = database["incidents"]
    active_query = {"ownerId": str(user["_id"]), "status": "ACTIVE"}
    existing = collection.find_one(active_query)
    if existing is not None:
        response.status_code = 200
        background_tasks.add_task(prepare_live_safely, database, existing, request.app.state.live_safety)
        return serialize_incident(existing)
    now = datetime.now(timezone.utc)
    document = {**body.model_dump(), **active_query, "createdAt": now,
                "updatedAt": now, "startedAt": now, "endedAt": None}
    try:
        document["_id"] = collection.insert_one(document).inserted_id
    except DuplicateKeyError:
        # The partial unique index prevents concurrent/retried SOS from creating two active incidents.
        existing = collection.find_one(active_query)
        if existing is None:
            raise
        response.status_code = 200
        background_tasks.add_task(prepare_live_safely, database, existing, request.app.state.live_safety)
        return serialize_incident(existing)
    background_tasks.add_task(notify_sos_safely, database, document, request.app.state.sos_notifier)
    background_tasks.add_task(prepare_live_safely, database, document, request.app.state.live_safety)
    return serialize_incident(document)


@router.get("", response_model=IncidentsResponse)
def list_incidents(user: CurrentUser, database=Depends(get_database)):
    documents = database["incidents"].find({"ownerId": str(user["_id"])}).sort([("createdAt", -1), ("_id", -1)])
    return IncidentsResponse(incidents=[serialize_incident(document) for document in documents])


@router.get("/{id}", response_model=IncidentResponse)
def get_incident(id: str, user: CurrentUser, database=Depends(get_database)):
    document = database["incidents"].find_one({"_id": incident_id(id), "ownerId": str(user["_id"])})
    if document is None:
        raise HTTPException(404, "Incident not found")
    return serialize_incident(document)


@router.patch("/{id}/end", response_model=IncidentResponse)
def end_incident(id: str, user: CurrentUser, request: Request, background_tasks: BackgroundTasks,
                 database=Depends(get_database)):
    owned_query = {"_id": incident_id(id), "ownerId": str(user["_id"])}
    now = datetime.now(timezone.utc)
    document = database["incidents"].find_one_and_update(
        {**owned_query, "status": "ACTIVE"},
        {"$set": {"status": "ENDED", "updatedAt": now, "endedAt": now}},
        return_document=ReturnDocument.AFTER,
    )
    if document is None:
        # Ending twice is safe and preserves the original end timestamp.
        document = database["incidents"].find_one(owned_query)
    if document is None:
        raise HTTPException(404, "Incident not found")
    background_tasks.add_task(close_live_safely, database, document, request.app.state.live_safety)
    return serialize_incident(document)
