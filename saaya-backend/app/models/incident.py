from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class IncidentCreate(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    latitude: float | None = Field(default=None, ge=-90, le=90, strict=True)
    longitude: float | None = Field(default=None, ge=-180, le=180, strict=True)

    @model_validator(mode="after")
    def paired_coordinates(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("Supply both latitude and longitude, or neither")
        return self


class IncidentResponse(IncidentCreate):
    id: str
    ownerId: str
    status: Literal["ACTIVE", "ENDED"]
    createdAt: datetime
    updatedAt: datetime
    startedAt: datetime
    endedAt: datetime | None


class IncidentsResponse(BaseModel):
    incidents: list[IncidentResponse]


def serialize_incident(document: dict) -> IncidentResponse:
    # MongoDB's native _id is exposed as id in the incident API.
    return IncidentResponse(
        id=str(document["_id"]),
        **{field: document[field] for field in IncidentResponse.model_fields if field != "id"},
    )
