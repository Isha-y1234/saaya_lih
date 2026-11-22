import secrets
from datetime import datetime, timezone

from bson import ObjectId
from fastapi import APIRouter, Depends, HTTPException, Response
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

from app.auth import CurrentUser
from app.database import get_database
from app.models.device import NotificationLinkCreate


router = APIRouter(prefix="/notification-links", tags=["FCM recipient links"])


@router.get("/code")
def get_recipient_code(user: CurrentUser, database=Depends(get_database)):
    user_id = str(user["_id"])
    try:
        record = database["notification_codes"].find_one_and_update(
            {"userId": user_id}, {"$setOnInsert": {"code": secrets.token_urlsafe(24),
                                                   "createdAt": datetime.now(timezone.utc)}},
            upsert=True, return_document=ReturnDocument.AFTER,
        )
    except DuplicateKeyError:
        record = database["notification_codes"].find_one({"userId": user_id})
        if record is None:
            raise
    return {"recipientCode": record["code"]}


@router.get("")
def list_links(user: CurrentUser, database=Depends(get_database)):
    owner = str(user["_id"])
    contacts = database["trusted_contacts"].find({"ownerId": owner})
    linked = [str(c["_id"]) for c in contacts if database["notification_links"].find_one(
        {"ownerId": owner, "contactId": c["_id"]}) is not None]
    return {"contactIds": linked}


@router.post("")
def link_recipient(body: NotificationLinkCreate, user: CurrentUser, database=Depends(get_database)):
    owner = str(user["_id"])
    contact_id = ObjectId(body.contactId)
    if database["trusted_contacts"].find_one({"_id": contact_id, "ownerId": owner}) is None:
        raise HTTPException(404, "Trusted contact not found")
    recipient = database["notification_codes"].find_one({"code": body.recipientCode})
    if recipient is None or recipient["userId"] == owner:
        raise HTTPException(400, "Use a valid code shared by your trusted contact")
    database["notification_links"].find_one_and_update(
        {"ownerId": owner, "contactId": contact_id},
        {"$set": {"recipientId": recipient["userId"], "updatedAt": datetime.now(timezone.utc)}},
        upsert=True, return_document=ReturnDocument.AFTER,
    )
    return {"contactId": body.contactId, "linked": True}


@router.delete("/{contact_id}", status_code=204, response_class=Response)
def remove_link(contact_id: str, user: CurrentUser, database=Depends(get_database)):
    if not ObjectId.is_valid(contact_id):
        raise HTTPException(400, "Invalid contact ID")
    database["notification_links"].delete_one({"ownerId": str(user["_id"]), "contactId": ObjectId(contact_id)})
    return Response(status_code=204)
