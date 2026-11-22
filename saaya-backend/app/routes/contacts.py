from datetime import datetime, timezone
from typing import Annotated
import secrets

from bson import ObjectId
from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, Request, Response, status
from pymongo.collection import Collection
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

from app.database import get_contacts_collection, get_database
from app.auth import CurrentUser
from app.notifications import notify_invitation_safely
from app.models.contact import (
    ContactCreate,
    ContactInvitationCreate,
    ContactInvitationDecision,
    ContactResponse,
    ContactsResponse,
    OwnerId,
    serialize_contact,
)


router = APIRouter(prefix="/contacts", tags=["Trusted Circle"])
ContactsCollection = Annotated[Collection, Depends(get_contacts_collection)]


@router.get("", response_model=ContactsResponse, response_model_exclude_none=True)
def get_contacts(
    ownerId: Annotated[OwnerId, Query()],
    collection: ContactsCollection,
    user: CurrentUser,
) -> ContactsResponse:
    owner_id = str(user["_id"])
    if ownerId != owner_id:
        raise HTTPException(403, "Cannot access another user's contacts")
    documents = collection.find({"ownerId": owner_id}).sort([("createdAt", 1), ("_id", 1)])
    return ContactsResponse(contacts=[serialize_contact(document) for document in documents])


@router.post("", response_model=ContactResponse, response_model_exclude_none=True, status_code=status.HTTP_201_CREATED)
def create_contact(contact: ContactCreate, collection: ContactsCollection, user: CurrentUser) -> ContactResponse:
    owner_id = str(user["_id"])
    if contact.ownerId != owner_id:
        raise HTTPException(403, "Cannot create another user's contacts")
    document = contact.model_dump()
    document["ownerId"] = owner_id
    document["createdAt"] = datetime.now(timezone.utc)
    result = collection.insert_one(document)
    document["_id"] = result.inserted_id
    return serialize_contact(document)


@router.post("/{id}/invitation", response_model=ContactResponse, response_model_exclude_none=True)
def invite_contact(id: str, body: ContactInvitationCreate, collection: ContactsCollection,
                   user: CurrentUser, request: Request, background_tasks: BackgroundTasks,
                   database=Depends(get_database)):
    if not ObjectId.is_valid(id):
        raise HTTPException(400, "Invalid contact ID")
    query = {"_id": ObjectId(id), "ownerId": str(user["_id"])}
    contact = collection.find_one(query)
    if contact is None:
        raise HTTPException(404, "Trusted contact not found")
    recipient = database["users"].find_one({"saayaId": body.recipientSaayaId})
    if recipient is None:
        raise HTTPException(404, "Saaya account not found")
    if recipient["_id"] == user["_id"]:
        raise HTTPException(400, "Cannot invite yourself")
    if contact.get("invitationStatus") in ("PENDING", "ACCEPTED"):
        raise HTTPException(409, "This contact already has an active invitation")
    if collection.find_one({"ownerId": str(user["_id"]),
                            "invitedRecipientId": str(recipient["_id"]), "invitationStatus": "PENDING"}):
        raise HTTPException(409, "A pending invitation already exists for this account")
    try:
        saved = collection.find_one_and_update(
            {**query, "invitationStatus": contact.get("invitationStatus")},
            {"$set": {"invitedRecipientId": str(recipient["_id"]), "invitationStatus": "PENDING",
                      "invitedAt": datetime.now(timezone.utc), "invitationId": secrets.token_urlsafe(24),
                      "decidedAt": None}},
            return_document=ReturnDocument.AFTER,
        )
    except DuplicateKeyError:
        # The unique partial index handles concurrent invitations on different contacts.
        raise HTTPException(409, "A pending invitation already exists for this account") from None
    if saved is None:
        raise HTTPException(409, "Contact changed; refresh and retry")
    # Schedule only after the winning write. Duplicate/conflicting requests never
    # reach here. Delivery failure cannot undo the saved invitation.
    background_tasks.add_task(notify_invitation_safely, database, saved,
                              request.app.state.sos_notifier.firebase_app)
    return serialize_contact(saved)


@router.get("/invitations/incoming", response_model=ContactsResponse, response_model_exclude_none=True)
def incoming_invitations(collection: ContactsCollection, user: CurrentUser, response: Response):
    response.headers["Cache-Control"] = "no-store"
    documents = collection.find({"invitedRecipientId": str(user["_id"]),
                                 "invitationStatus": "PENDING"}).sort([("invitedAt", 1), ("_id", 1)])
    return ContactsResponse(contacts=[serialize_contact(doc) for doc in documents])


def decide_invitation(id, body, collection, user, decision):
    if not ObjectId.is_valid(id):
        raise HTTPException(400, "Invalid contact ID")
    query = {"_id": ObjectId(id), "invitedRecipientId": str(user["_id"])}
    if collection.find_one(query) is None:
        raise HTTPException(404, "Invitation not found")
    saved = collection.find_one_and_update(
        {**query, "invitationId": body.invitationId, "invitationStatus": "PENDING"},
        {"$set": {"invitationStatus": decision, "decidedAt": datetime.now(timezone.utc)}},
        return_document=ReturnDocument.AFTER,
    )
    if saved is None:
        raise HTTPException(409, "Invitation is no longer pending or has been replaced")
    return serialize_contact(saved)


@router.post("/{id}/invitation/accept", response_model=ContactResponse, response_model_exclude_none=True)
def accept_invitation(id: str, body: ContactInvitationDecision, collection: ContactsCollection, user: CurrentUser):
    return decide_invitation(id, body, collection, user, "ACCEPTED")


@router.post("/{id}/invitation/reject", response_model=ContactResponse, response_model_exclude_none=True)
def reject_invitation(id: str, body: ContactInvitationDecision, collection: ContactsCollection, user: CurrentUser):
    return decide_invitation(id, body, collection, user, "REJECTED")


@router.delete("/{id}", status_code=status.HTTP_204_NO_CONTENT, response_class=Response)
def delete_contact(id: str, collection: ContactsCollection, user: CurrentUser) -> Response:
    if not ObjectId.is_valid(id):
        raise HTTPException(status_code=400, detail="Invalid contact ID")
    result = collection.delete_one({"_id": ObjectId(id), "ownerId": str(user["_id"])})
    if result.deleted_count == 0:
        raise HTTPException(status_code=404, detail="Contact not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)
