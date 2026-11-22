from datetime import datetime, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Response
from fastapi.security import HTTPAuthorizationCredentials
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

from app.auth import CurrentUser, bearer, hash_session_token
from app.database import get_database
from app.models.device import DeviceTokenRegistration, DeviceTokenRemoval


router = APIRouter(prefix="/devices", tags=["FCM devices"])
Credentials = Annotated[HTTPAuthorizationCredentials, Depends(bearer)]


@router.post("/token")
def register_token(body: DeviceTokenRegistration, user: CurrentUser, credentials: Credentials,
                   database=Depends(get_database)):
    session_hash = hash_session_token(credentials.credentials)
    now = datetime.now(timezone.utc)
    session = database["auth_sessions"].find_one({"tokenHash": session_hash, "userId": user["_id"],
                                                 "expiresAt": {"$gt": now}})
    if session is None:
        raise HTTPException(401, "Sign in to continue")
    try:
        # BSON rounds datetimes to milliseconds. Equal times from different sessions
        # are ambiguous: only the exact bound session may refresh an equal-time binding.
        database["device_tokens"].find_one_and_update(
            {"token": body.token, "$or": [{"sessionHash": session_hash},
                                           {"sessionCreatedAt": {"$lt": session["createdAt"]}}]},
            {"$set": {"userId": str(user["_id"]), "sessionHash": session_hash,
                      "sessionCreatedAt": session["createdAt"], "notificationsEnabled": body.notificationsEnabled,
                      "updatedAt": now}, "$setOnInsert": {"createdAt": now}},
            upsert=True, return_document=ReturnDocument.AFTER,
        )
    except DuplicateKeyError:
        raise HTTPException(409, "Another session has registered this device") from None
    return {"registered": True}


@router.delete("/token", status_code=204, response_class=Response)
def remove_token(body: DeviceTokenRemoval, user: CurrentUser, credentials: Credentials,
                 database=Depends(get_database)):
    database["device_tokens"].delete_one({"token": body.token, "userId": str(user["_id"]),
                                         "sessionHash": hash_session_token(credentials.credentials)})
    return Response(status_code=204)
