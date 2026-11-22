import secrets
from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response
from fastapi.security import HTTPAuthorizationCredentials
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

from app.auth import CurrentUser, bearer, hash_session_token, verify_google_token
from app.database import get_database
from app.models.user import GoogleSignInRequest, SessionResponse, UserResponse, serialize_user
from app.saaya_ids import ensure_saaya_id


router = APIRouter(prefix="/users", tags=["Users"])
SESSION_LIFETIME = timedelta(days=30)


@router.post("/google", response_model=SessionResponse)
def sign_in(body: GoogleSignInRequest, request: Request, database=Depends(get_database)):
    claims = verify_google_token(body.idToken, request.app.state.settings.google_web_client_id)
    now = datetime.now(timezone.utc)
    profile = {"name": str(claims.get("name") or claims["email"]), "email": claims["email"]}
    try:
        user = database["users"].find_one_and_update(
            {"googleSub": claims["sub"]},
            {"$set": profile, "$setOnInsert": {"createdAt": now}},
            upsert=True,
            return_document=ReturnDocument.AFTER,
        )
    except DuplicateKeyError:
        # The unique subject index also protects concurrent first sign-ins.
        user = database["users"].find_one({"googleSub": claims["sub"]})
        if user is None:
            raise
    user = ensure_saaya_id(database["users"], user)
    token = secrets.token_urlsafe(32)
    expires_at = now + SESSION_LIFETIME
    database["auth_sessions"].insert_one({
        "userId": user["_id"],
        "tokenHash": hash_session_token(token),
        "createdAt": now,
        "expiresAt": expires_at,
    })
    return SessionResponse(user=serialize_user(user), token=token, expiresAt=int(expires_at.timestamp()))


@router.get("/me", response_model=UserResponse)
def get_me(user: CurrentUser):
    return serialize_user(user)


@router.post("/session", status_code=204, response_class=Response)
@router.delete("/session", status_code=204, response_class=Response)
def sign_out(
    user: CurrentUser,
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer)],
    database=Depends(get_database),
):
    session_hash = hash_session_token(credentials.credentials)
    database["auth_sessions"].delete_one({"tokenHash": session_hash})
    database["device_tokens"].delete_many({"sessionHash": session_hash, "userId": str(user["_id"])})
    return Response(status_code=204)
