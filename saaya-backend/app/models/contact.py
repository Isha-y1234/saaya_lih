from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


OwnerId = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=128)]
Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Relation = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Phone = Annotated[
    str,
    StringConstraints(
        strip_whitespace=True,
        min_length=3,
        max_length=32,
        pattern=r"^\+?[0-9][0-9 ()-]*$",
    ),
]
Initials = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=10)]
InvitationStatus = Literal["PENDING", "ACCEPTED", "REJECTED", "REVOKED"]


class ContactInvitationCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recipientSaayaId: Annotated[str, StringConstraints(
        strip_whitespace=True, to_upper=True, pattern=r"^SAAYA-[0-9A-Fa-f]{24}$")]


class ContactInvitationDecision(BaseModel):
    model_config = ConfigDict(extra="forbid")

    invitationId: str = Field(min_length=32, max_length=32)


class ContactCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ownerId: OwnerId
    name: Name
    relation: Relation
    phone: Phone
    initials: Initials


class ContactResponse(ContactCreate):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    id: str = Field(alias="_id")
    createdAt: datetime
    invitedRecipientId: str | None = None
    invitationStatus: InvitationStatus | None = None
    invitedAt: datetime | None = None
    invitationId: str | None = None
    decidedAt: datetime | None = None


class ContactsResponse(BaseModel):
    contacts: list[ContactResponse]


def serialize_contact(document: dict) -> ContactResponse:
    return ContactResponse(
        _id=str(document["_id"]),
        ownerId=document["ownerId"],
        name=document["name"],
        relation=document["relation"],
        phone=document["phone"],
        initials=document["initials"],
        createdAt=document["createdAt"],
        invitedRecipientId=document.get("invitedRecipientId"),
        invitationStatus=document.get("invitationStatus"),
        invitedAt=document.get("invitedAt"),
        invitationId=document.get("invitationId"),
        decidedAt=document.get("decidedAt"),
    )
