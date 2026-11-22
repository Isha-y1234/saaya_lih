import logging
from datetime import datetime, timedelta, timezone

import firebase_admin
from bson import ObjectId
from firebase_admin import credentials, messaging
from firebase_admin.exceptions import InvalidArgumentError


logger = logging.getLogger(__name__)
FIREBASE_PROJECT_ID = "saaya-1d484"


def initialize_fcm(settings):
    if not settings.firebase_service_account_file:
        logger.warning("FCM is disabled: FIREBASE_SERVICE_ACCOUNT_FILE is not configured")
        return None
    try:
        try:
            return firebase_admin.get_app("saaya-fcm")
        except ValueError:
            credential = credentials.Certificate(settings.firebase_service_account_file)
            if credential.project_id != FIREBASE_PROJECT_ID:
                logger.warning("FCM initialization failed: credential project does not match the Android Firebase project")
                return None
            app = firebase_admin.initialize_app(credential,
                                                 {"httpTimeout": 10}, name="saaya-fcm")
            logger.info("FCM initialized; remote authorization and delivery remain unverified")
            return app
    except FileNotFoundError:
        logger.warning("FCM initialization failed: credential file not found")
        return None
    except PermissionError:
        logger.warning("FCM initialization failed: credential file is not readable")
        return None
    except Exception:
        # Never log exception text, credential paths, private keys, or token values.
        logger.warning("FCM initialization failed; incident creation remains available")
        return None


def fcm_readiness(settings, firebase_app):
    """Initialization is local configuration evidence, never proof of FCM delivery."""
    status = ("disabled" if not settings.firebase_service_account_file
              else "initialization_failed" if firebase_app is None else "initialized")
    return {"status": status, "deliveryVerified": False}


def membership_accepted_before(contact, started_at):
    """Fail closed without a recorded acceptance; BSON millisecond ties do not qualify."""
    decided_at = contact.get("decidedAt")
    if (contact.get("invitationStatus") != "ACCEPTED" or not contact.get("invitationId") or
            not isinstance(decided_at, datetime) or not isinstance(started_at, datetime) or
            decided_at.utcoffset() is None or started_at.utcoffset() is None):
        return False
    decided_at = decided_at.astimezone(timezone.utc).replace(microsecond=decided_at.microsecond // 1000 * 1000)
    started_at = started_at.astimezone(timezone.utc).replace(microsecond=started_at.microsecond // 1000 * 1000)
    return decided_at < started_at


def resolve_recipients(database, owner_id: str, started_at=None):
    recipients = {}
    now = datetime.now(timezone.utc)
    for contact in database["trusted_contacts"].find({"ownerId": owner_id}):
        targets = {}
        link = database["notification_links"].find_one({"ownerId": owner_id, "contactId": contact["_id"]})
        if link is not None and link["recipientId"] != owner_id:
            # Preserve explicit legacy links as a separate source, never as acceptance.
            targets[link["recipientId"]] = {"contactIds": [contact["_id"]], "memberships": []}
        recipient = contact.get("invitedRecipientId")
        if (isinstance(recipient, str) and ObjectId.is_valid(recipient) and recipient != owner_id and
                membership_accepted_before(contact, started_at)):
            target = targets.setdefault(recipient, {"contactIds": [], "memberships": []})
            target["memberships"].append({"contactId": contact["_id"], "invitationId": contact["invitationId"],
                                          "decidedAt": contact["decidedAt"]})
        for recipient, grants in targets.items():
            entry = recipients.setdefault(recipient, {"tokens": [], "contactIds": [], "memberships": []})
            entry["contactIds"].extend(grants["contactIds"])
            entry["memberships"].extend(grants["memberships"])
            for device in database["device_tokens"].find({"userId": recipient, "notificationsEnabled": True}):
                if database["auth_sessions"].find_one({"tokenHash": device["sessionHash"], "userId": ObjectId(recipient),
                                                       "expiresAt": {"$gt": now}}) is None:
                    continue
                if device["token"] not in entry["tokens"]:
                    entry["tokens"].append(device["token"])
    return {user: entry for user, entry in recipients.items() if entry["tokens"]}


class SosNotifier:
    def __init__(self, firebase_app):
        self.firebase_app = firebase_app

    def send(self, database, incident):
        if self.firebase_app is None:
            return
        for recipient, entry in resolve_recipients(database, incident["ownerId"], incident.get("startedAt")).items():
            database["incident_notification_recipients"].find_one_and_update(
                {"incidentId": incident["_id"], "recipientId": recipient},
                {"$set": {"ownerId": incident["ownerId"], "contactIds": entry["contactIds"],
                          "memberships": entry["memberships"]}},
                upsert=True,
            )
            for offset in range(0, len(entry["tokens"]), 500):
                tokens = entry["tokens"][offset:offset + 500]
                # Data-only allows Android to reject old-account alerts even while backgrounded.
                message = messaging.MulticastMessage(tokens=tokens, data={
                    "type": "SOS", "incidentId": str(incident["_id"]), "recipientId": recipient,
                    "title": "Saaya SOS", "body": "A Trusted Circle contact needs help. Tap to view their incident.",
                }, android=messaging.AndroidConfig(priority="high", ttl=timedelta(minutes=10)))
                try:
                    result = messaging.send_each_for_multicast(message, app=self.firebase_app)
                except Exception:
                    logger.warning("An FCM batch failed; continuing with other recipients")
                    continue
                for token, delivery in zip(tokens, result.responses):
                    if isinstance(delivery.exception, messaging.UnregisteredError):
                        database["device_tokens"].delete_one({"token": token, "userId": recipient})
                    elif isinstance(delivery.exception, (messaging.SenderIdMismatchError, InvalidArgumentError)):
                        # Generated payload is bounded and known-valid; quarantine invalid/mismatched tokens.
                        database["device_tokens"].find_one_and_update(
                            {"token": token, "userId": recipient}, {"$set": {"notificationsEnabled": False}})
                    elif delivery.exception is not None:
                        logger.warning("An FCM delivery failed; incident remains active")


def notify_sos_safely(database, incident, notifier):
    try:
        notifier.send(database, incident)
    except Exception:
        logger.warning("SOS notification delivery failed; incident creation and calling are unaffected")


def notify_invitation_safely(database, contact, firebase_app):
    """Best-effort invitation delivery using the existing Firebase app/device policy.

    This does not resolve SOS recipients or create incident authorization grants.
    """
    try:
        if firebase_app is None:
            return
        recipient = contact["invitedRecipientId"]
        now = datetime.now(timezone.utc)
        tokens = []
        for device in database["device_tokens"].find({"userId": recipient, "notificationsEnabled": True}):
            if database["auth_sessions"].find_one({"tokenHash": device["sessionHash"],
                    "userId": ObjectId(recipient), "expiresAt": {"$gt": now}}) is None:
                continue
            if device["token"] not in tokens:
                tokens.append(device["token"])
        for offset in range(0, len(tokens), 500):
            # Drop queued invitations that were deleted, answered or replaced.
            if database["trusted_contacts"].find_one({"_id": contact["_id"],
                    "invitationId": contact["invitationId"], "invitedRecipientId": recipient,
                    "invitationStatus": "PENDING"}) is None:
                return
            batch = tokens[offset:offset + 500]
            # Deliberately distinct from SOS: no incidentId, link, phone, name,
            # title/body or media credentials. Clients fetch details with auth.
            message = messaging.MulticastMessage(tokens=batch, data={
                "type": "TRUSTED_CIRCLE_INVITATION", "invitationId": contact["invitationId"],
            }, android=messaging.AndroidConfig(priority="high", ttl=timedelta(minutes=10)))
            try:
                result = messaging.send_each_for_multicast(message, app=firebase_app)
            except Exception:
                logger.warning("Invitation FCM batch failed; pending invitation is preserved")
                continue
            # Match the established SOS cleanup policy without changing its path.
            for token, delivery in zip(batch, result.responses):
                if isinstance(delivery.exception, messaging.UnregisteredError):
                    database["device_tokens"].delete_one({"token": token, "userId": recipient})
                elif isinstance(delivery.exception, (messaging.SenderIdMismatchError, InvalidArgumentError)):
                    database["device_tokens"].find_one_and_update(
                        {"token": token, "userId": recipient}, {"$set": {"notificationsEnabled": False}})
                elif delivery.exception is not None:
                    logger.warning("Invitation FCM delivery failed; pending invitation is preserved")
    except Exception:
        # Do not log tokens, payloads, credential paths or provider exception text.
        logger.warning("Invitation notification failed; pending invitation is preserved")
