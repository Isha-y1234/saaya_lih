from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class GoogleSignInRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    idToken: str = Field(min_length=1, max_length=16384)


class UserResponse(BaseModel):
    id: str = Field(alias="_id")
    saayaId: str
    name: str
    email: str
    createdAt: datetime


class SessionResponse(BaseModel):
    user: UserResponse
    token: str
    expiresAt: int  # Unix seconds, not the Google token expiration.


def serialize_user(document: dict) -> UserResponse:
    return UserResponse(
        _id=str(document["_id"]),
        saayaId=document["saayaId"],
        name=document["name"],
        email=document["email"],
        createdAt=document["createdAt"],
    )
