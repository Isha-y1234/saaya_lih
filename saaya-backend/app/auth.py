import hashlib
from datetime import datetime, timezone
from typing import Annotated

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from google.auth.exceptions import GoogleAuthError, TransportError
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import id_token

from app.database import get_database


bearer = HTTPBearer(auto_error=False)


def unauthorized() -> HTTPException:
    return HTTPException(401, "Sign in to continue", headers={"WWW-Authenticate": "Bearer"})


def hash_session_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def verify_google_token(token: str, client_id: str) -> dict:
    try:
        # google-auth verifies signature, audience, issuer and expiration.
        claims = id_token.verify_oauth2_token(token, GoogleRequest(), client_id)
    except TransportError:
        raise HTTPException(503, "Google verification temporarily unavailable") from None
    except (ValueError, GoogleAuthError):
        raise unauthorized() from None
    if (
        not isinstance(claims.get("sub"), str)
        or not claims["sub"]
        or not isinstance(claims.get("email"), str)
        or not claims["email"]
        or claims.get("email_verified") is not True
    ):
        raise unauthorized()
    return claims


def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
    database=Depends(get_database),
) -> dict:
    if credentials is None or not 32 <= len(credentials.credentials) <= 512:
        raise unauthorized()
    session = database["auth_sessions"].find_one({
        "tokenHash": hash_session_token(credentials.credentials),
        "expiresAt": {"$gt": datetime.now(timezone.utc)},
    })
    if session is None:
        raise unauthorized()
    user = database["users"].find_one({"_id": session["userId"]})
    if user is None:
        raise unauthorized()
    return user


CurrentUser = Annotated[dict, Depends(get_current_user)]
