from datetime import datetime, timezone
from typing import Annotated

from bson import ObjectId

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, ConfigDict

from app.auth import CurrentUser, bearer, hash_session_token
from app.database import get_database
from app.live_safety import authorized_live_incident, may_join_live
from app.models.incident import IncidentsResponse, serialize_incident

router = APIRouter(tags=["Live safety"])


class JoinRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


@router.get("/live-safety/incidents", response_model=IncidentsResponse)
def active_live_incidents(user: CurrentUser, response: Response, database=Depends(get_database)):
    response.headers["Cache-Control"] = "no-store"
    user_id = str(user["_id"])
    owners = {user_id}
    owners.update(link["ownerId"] for link in database["notification_links"].find({"recipientId": user_id}))
    owners.update(contact["ownerId"] for contact in database["trusted_contacts"].find(
        {"invitedRecipientId": user_id, "invitationStatus": "ACCEPTED"}))
    incidents = [serialize_incident(incident) for owner in sorted(owners)
                 for incident in database["incidents"].find({"ownerId": owner, "status": "ACTIVE"})
                 if may_join_live(database, incident, user_id)]
    return IncidentsResponse(incidents=incidents)


def authorized_viewers(database, incident):
    """Sender-only roster of issued identities; clients intersect with connected room peers."""
    viewers = []
    for participant in database["livekit_participants"].find({"incidentId": incident["_id"], "revoked": False}):
        recipient = participant["userId"]
        if recipient == incident["ownerId"] or not ObjectId.is_valid(recipient):
            continue
        session = database["auth_sessions"].find_one({"tokenHash": participant["sessionHash"],
            "userId": ObjectId(recipient), "expiresAt": {"$gt": datetime.now(timezone.utc)}})
        if session is None or not may_join_live(database, incident, recipient):
            continue
        profile = database["users"].find_one({"_id": ObjectId(recipient)})
        name = str((profile or {}).get("name") or "Trusted viewer")[:80]
        viewers.append({"identity": participant["identity"], "name": name})
    return viewers


@router.get("/incidents/{id}/live")
def live_status(id: str, user: CurrentUser, request: Request, response: Response,
                verify_session: bool = Query(default=False, alias="verifySession"), database=Depends(get_database)):
    response.headers["Cache-Control"] = "no-store"
    incident = authorized_live_incident(database, id, str(user["_id"]))
    service = request.app.state.live_safety
    session = database["livekit_sessions"].find_one({"incidentId": incident["_id"], "status": "OPEN"})
    session_active = incident["status"] == "ACTIVE" and session is not None and service.status == "configured"
    if verify_session and session_active:
        try:
            service.existing_room(database, incident)
        except HTTPException as error:
            if error.status_code != 409:
                raise
            session_active = False
    return {"incidentId": id, "active": incident["status"] == "ACTIVE",
            "sessionActive": session_active,
            "readiness": service.status,
            "role": "publisher" if incident["ownerId"] == str(user["_id"]) else "viewer",
            "viewers": authorized_viewers(database, incident) if session_active and incident["ownerId"] == str(user["_id"]) else []}


@router.post("/incidents/{id}/live/join")
def join_live(id: str, user: CurrentUser, request: Request, response: Response,
              credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)],
              database=Depends(get_database), body: JoinRequest | None = None):
    response.headers["Cache-Control"] = "no-store"
    incident = authorized_live_incident(database, id, str(user["_id"]))
    return request.app.state.live_safety.join(database, incident, str(user["_id"]),
                                             hash_session_token(credentials.credentials))


@router.post("/incidents/{id}/live/audio", status_code=204)
def enable_viewer_audio(id: str, user: CurrentUser, request: Request, response: Response,
                        credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)],
                        database=Depends(get_database), body: JoinRequest | None = None):
    response.headers["Cache-Control"] = "no-store"
    incident = authorized_live_incident(database, id, str(user["_id"]))
    request.app.state.live_safety.enable_viewer_audio(
        database, incident, str(user["_id"]), hash_session_token(credentials.credentials))


@router.post("/incidents/{id}/live/rejoin")
def rejoin_live(id: str, user: CurrentUser, request: Request, response: Response,
                credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)],
                database=Depends(get_database), body: JoinRequest | None = None):
    response.headers["Cache-Control"] = "no-store"
    incident = authorized_live_incident(database, id, str(user["_id"]))
    return request.app.state.live_safety.join(database, incident, str(user["_id"]),
        hash_session_token(credentials.credentials), existing_only=True)
