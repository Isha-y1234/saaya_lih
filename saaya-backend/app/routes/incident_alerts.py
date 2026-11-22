from fastapi import APIRouter, Depends, HTTPException

from app.auth import CurrentUser
from app.database import get_database
from app.models.incident import IncidentResponse, serialize_incident
from app.routes.incidents import incident_id
from app.notifications import membership_accepted_before


router = APIRouter(prefix="/incident-alerts", tags=["Read-only SOS alerts"])


@router.get("/{id}", response_model=IncidentResponse)
def read_alert(id: str, user: CurrentUser, database=Depends(get_database)):
    object_id = incident_id(id)
    user_id = str(user["_id"])
    incident = database["incidents"].find_one({"_id": object_id})
    if incident is None:
        raise HTTPException(404, "Incident not found")
    if incident["ownerId"] == user_id:
        return serialize_incident(incident)
    snapshot = database["incident_notification_recipients"].find_one({"incidentId": object_id, "recipientId": user_id})
    if snapshot is not None:
        for contact_id in snapshot["contactIds"]:
            contact = database["trusted_contacts"].find_one({"_id": contact_id, "ownerId": incident["ownerId"]})
            link = database["notification_links"].find_one({"contactId": contact_id, "ownerId": incident["ownerId"], "recipientId": user_id})
            if contact is not None and link is not None:
                return serialize_incident(incident)
        # Membership grants cover only this active SOS summary. Do not add links
        # or change the shared chat/evidence/reports/LiveKit authorization helper.
        if incident["status"] == "ACTIVE":
            for grant in snapshot.get("memberships", []):
                contact = database["trusted_contacts"].find_one({"_id": grant["contactId"],
                    "ownerId": incident["ownerId"], "invitedRecipientId": user_id})
                if (contact is not None and contact.get("invitationId") == grant["invitationId"] and
                        contact.get("decidedAt") == grant["decidedAt"] and
                        membership_accepted_before(contact, incident.get("startedAt"))):
                    return serialize_incident(incident)
    raise HTTPException(404, "Incident not found")
