"""Optional media service. Never include SDK errors, keys or join tokens in logs."""
import asyncio
import hashlib
import logging
import secrets
import time
import aiohttp
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from bson import ObjectId
from fastapi import HTTPException
from livekit import api
from pymongo import ReturnDocument
from pymongo.errors import DuplicateKeyError

logger = logging.getLogger(__name__)
TOKEN_TTL_SECONDS = 120


def may_view(database, incident, user_id):
    """An explicit current recipient-code link, never a phone/email or push payload."""
    if incident["ownerId"] == user_id:
        return True
    for link in database["notification_links"].find({"ownerId": incident["ownerId"], "recipientId": user_id}):
        if database["trusted_contacts"].find_one({"_id": link["contactId"], "ownerId": incident["ownerId"]}):
            return True
    return False


def may_join_live(database, incident, user_id):
    """Live-only access: legacy links or current membership accepted before this SOS."""
    if may_view(database, incident, user_id):
        return True
    from app.notifications import membership_accepted_before
    return incident["status"] == "ACTIVE" and any(
        membership_accepted_before(contact, incident.get("startedAt"))
        for contact in database["trusted_contacts"].find(
            {"ownerId": incident["ownerId"], "invitedRecipientId": user_id}))


def authorized_live_incident(database, incident_id, user_id):
    if not ObjectId.is_valid(incident_id):
        raise HTTPException(400, "Invalid incident ID")
    incident = database["incidents"].find_one({"_id": ObjectId(incident_id)})
    if incident is None or not may_join_live(database, incident, user_id):
        raise HTTPException(404, "Incident not found")
    return incident


def authorized_incident(database, incident_id, user_id):
    if not ObjectId.is_valid(incident_id):
        raise HTTPException(400, "Invalid incident ID")
    incident = database["incidents"].find_one({"_id": ObjectId(incident_id)})
    if incident is None or not may_view(database, incident, user_id):
        raise HTTPException(404, "Incident not found")
    return incident


class LiveSafetyService:
    def __init__(self, settings):
        self.settings = settings
        values = (settings.livekit_url, settings.livekit_api_key, settings.livekit_api_secret)
        try:
            parsed = urlparse(settings.livekit_url)
        except ValueError:
            self.status = "invalid_configuration"
            return
        # Self-hosted LiveKit cannot revoke cached/refreshed tokens on incident end.
        # Refuse unsafe configurations rather than claiming immediate revocation.
        self.status = ("disabled" if not any(values) else "configured" if all(values) and
                       parsed.scheme == "wss" and (parsed.hostname or "").endswith(".livekit.cloud") and
                       not parsed.username and not parsed.password and not parsed.query and
                       not parsed.fragment and parsed.path in ("", "/") else "invalid_configuration")

    def _client(self):
        return api.LiveKitAPI(self.settings.livekit_url, self.settings.livekit_api_key,
                              self.settings.livekit_api_secret,
                              timeout=aiohttp.ClientTimeout(total=5), failover=False)

    def require_enabled(self):
        if self.status != "configured":
            raise HTTPException(503, "Live safety is unavailable. SOS and calling remain available.")

    def ensure_room(self, database, incident):
        self.require_enabled()
        if database["incidents"].find_one({"_id": incident["_id"], "status": "ACTIVE"}) is None:
            raise HTTPException(409, "This incident has ended")
        try:
            session = database["livekit_sessions"].find_one_and_update(
                {"incidentId": incident["_id"]},
                {"$setOnInsert": {"roomName": "safety-" + secrets.token_hex(16), "status": "OPEN",
                                  "createdAt": datetime.now(timezone.utc)}},
                upsert=True, return_document=ReturnDocument.AFTER)
        except DuplicateKeyError:
            session = database["livekit_sessions"].find_one({"incidentId": incident["_id"]})
        if session is None or session["status"] != "OPEN":
            raise HTTPException(409, "This live safety session has ended")
        async def create():
            async with self._client() as client:
                await client.room.create_room(api.CreateRoomRequest(
                    name=session["roomName"], empty_timeout=300, departure_timeout=60))
        try:
            asyncio.run(create())
        except Exception:
            logger.warning("Live safety room unavailable; SOS is unaffected")
            raise HTTPException(503, "Could not connect to live safety. Please retry.") from None
        # Close a room created concurrently with incident termination; never return a token.
        if database["incidents"].find_one({"_id": incident["_id"], "status": "ACTIVE"}) is None:
            self.close(database, incident)
            raise HTTPException(409, "This incident has ended")
        database["incidents"].find_one_and_update(
            {"_id": incident["_id"], "status": "ACTIVE"}, {"$set": {"livekitRoomName": session["roomName"]}})
        return session

    def existing_room(self, database, incident):
        self.require_enabled()
        room = database["livekit_sessions"].find_one({"incidentId": incident["_id"], "status": "OPEN"})
        if incident["status"] != "ACTIVE" or room is None:
            raise HTTPException(409, "This live safety session is no longer active")
        async def exists():
            async with self._client() as client:
                rooms = await client.room.list_rooms(api.ListRoomsRequest(names=[room["roomName"]]))
                return any(item.name == room["roomName"] for item in rooms.rooms)
        try:
            active = asyncio.run(exists())
        except Exception:
            raise HTTPException(503, "Could not verify live session. Please retry.") from None
        if not active:
            raise HTTPException(409, "This live safety session is no longer active")
        return room

    def join(self, database, incident, user_id, session_hash, existing_only=False):
        room = self.existing_room(database, incident) if existing_only else self.ensure_room(database, incident)
        publisher = incident["ownerId"] == user_id
        identity = hashlib.sha256((str(incident["_id"]) + ":" + session_hash).encode()).hexdigest()
        # Record every issued identity before signing, including clients that never connect.
        database["livekit_participants"].find_one_and_update(
            {"incidentId": incident["_id"], "identity": identity},
            {"$set": {"userId": user_id, "sessionHash": session_hash, "revoked": False}},
            upsert=True, return_document=ReturnDocument.AFTER)
        current = database["incidents"].find_one({"_id": incident["_id"], "status": "ACTIVE"})
        session = database["auth_sessions"].find_one({"tokenHash": session_hash, "userId": ObjectId(user_id),
                                                     "expiresAt": {"$gt": datetime.now(timezone.utc)}})
        live_session = database["livekit_sessions"].find_one({"incidentId": incident["_id"], "status": "OPEN"})
        if current is None or session is None or live_session is None or not may_join_live(database, current, user_id):
            self.reconcile(database)
            raise HTTPException(409, "Live safety access is no longer available")
        token = (api.AccessToken(self.settings.livekit_api_key, self.settings.livekit_api_secret)
                 .with_identity(identity).with_ttl(timedelta(seconds=TOKEN_TTL_SECONDS))
                 .with_grants(api.VideoGrants(room_join=True, room=room["roomName"],
                     can_publish=True, can_subscribe=True, can_publish_data=False,
                     can_publish_sources=["camera", "microphone"] if publisher else ["microphone"],
                     can_update_own_metadata=False)).to_jwt())
        return {"incidentId": str(incident["_id"]), "url": self.settings.livekit_url, "token": token,
                "role": "publisher" if publisher else "viewer", "expiresIn": TOKEN_TTL_SECONDS}

    def enable_viewer_audio(self, database, incident, user_id, session_hash):
        """Upgrade connected participants in place, including tokens issued before audio support."""
        self.require_enabled()
        if incident["status"] != "ACTIVE" or incident["ownerId"] == user_id:
            raise HTTPException(409, "Viewer audio is unavailable")
        room = database["livekit_sessions"].find_one({"incidentId": incident["_id"], "status": "OPEN"})
        identity = hashlib.sha256((str(incident["_id"]) + ":" + session_hash).encode()).hexdigest()
        viewer = database["livekit_participants"].find_one(
            {"incidentId": incident["_id"], "identity": identity, "userId": user_id,
             "sessionHash": session_hash, "revoked": False})
        if room is None or viewer is None:
            raise HTTPException(409, "Join the live session before enabling audio")
        publishers = []
        for participant in database["livekit_participants"].find(
                {"incidentId": incident["_id"], "userId": incident["ownerId"], "revoked": False}):
            if database["auth_sessions"].find_one({"tokenHash": participant["sessionHash"],
                    "userId": ObjectId(incident["ownerId"]), "expiresAt": {"$gt": datetime.now(timezone.utc)}}):
                publishers.append(participant)

        async def update():
            async with self._client() as client:
                # Let already connected senders hear viewer audio without touching their tracks.
                for publisher in publishers:
                    try:
                        await client.room.update_participant(api.UpdateParticipantRequest(
                            room=room["roomName"], identity=publisher["identity"],
                            permission=api.ParticipantPermission(can_publish=True, can_subscribe=True,
                                can_publish_data=False, can_update_metadata=False,
                                can_publish_sources=[api.TrackSource.CAMERA, api.TrackSource.MICROPHONE])))
                    except api.TwirpError as error:
                        if error.code != "not_found":
                            raise
                await client.room.update_participant(api.UpdateParticipantRequest(
                    room=room["roomName"], identity=identity,
                    permission=api.ParticipantPermission(can_publish=True, can_subscribe=True,
                        can_publish_data=False, can_update_metadata=False,
                        can_publish_sources=[api.TrackSource.MICROPHONE])))
        try:
            asyncio.run(update())
        except Exception:
            logger.warning("Live safety microphone permission update unavailable")
            raise HTTPException(503, "Could not enable microphone. Please retry.") from None
        # Recheck after the remote operation so termination/revocation cannot restore access.
        current = database["incidents"].find_one({"_id": incident["_id"], "status": "ACTIVE"})
        session = database["auth_sessions"].find_one({"tokenHash": session_hash,
            "userId": ObjectId(user_id), "expiresAt": {"$gt": datetime.now(timezone.utc)}})
        if current is None or session is None or not may_join_live(database, current, user_id):
            self.reconcile(database)
            raise HTTPException(409, "Live safety access is no longer available")

    def _revoke(self, room_name, identity):
        async def remove():
            async with self._client() as client:
                await client.room.remove_participant(api.RoomParticipantIdentity(
                    room=room_name, identity=identity, revoke_token_ts=int(time.time()) + 60))
        asyncio.run(remove())

    def close(self, database, incident):
        session = database["livekit_sessions"].find_one({"incidentId": incident["_id"]})
        if session is None:
            return
        database["livekit_sessions"].find_one_and_update({"_id": session["_id"]}, {"$set": {"status": "CLOSE_PENDING"}})
        try:
            self.require_enabled()
            # DeleteRoom alone disconnects clients but does not revoke unconsumed tokens.
            revoked_all = True
            for participant in database["livekit_participants"].find({"incidentId": incident["_id"]}):
                try:
                    self._revoke(session["roomName"], participant["identity"])
                except Exception:
                    revoked_all = False
            async def delete():
                async with self._client() as client:
                    try:
                        await client.room.delete_room(api.DeleteRoomRequest(room=session["roomName"]))
                    except api.TwirpError as error:
                        if error.code != "not_found":
                            raise
            asyncio.run(delete())
            if not revoked_all:
                logger.warning("Live safety room disconnected; token revocation will retry")
                return
            database["livekit_sessions"].find_one_and_update({"_id": session["_id"]},
                {"$set": {"status": "CLOSED", "closedAt": datetime.now(timezone.utc)}})
        except Exception:
            logger.warning("Live safety close pending; automatic retry scheduled")

    def reconcile(self, database):
        if self.status != "configured":
            return
        for status in ("OPEN", "CLOSE_PENDING"):
            for room in database["livekit_sessions"].find({"status": status}):
                incident = database["incidents"].find_one({"_id": room["incidentId"]})
                if incident is None or incident["status"] != "ACTIVE" or status == "CLOSE_PENDING":
                    self.close(database, {"_id": room["incidentId"]})
                    continue
                for participant in database["livekit_participants"].find({"incidentId": incident["_id"], "revoked": False}):
                    session = database["auth_sessions"].find_one({"tokenHash": participant["sessionHash"],
                        "userId": ObjectId(participant["userId"]), "expiresAt": {"$gt": datetime.now(timezone.utc)}})
                    if session is None or not may_join_live(database, incident, participant["userId"]):
                        try:
                            self._revoke(room["roomName"], participant["identity"])
                            database["livekit_participants"].find_one_and_update({"_id": participant["_id"]},
                                {"$set": {"revoked": True}})
                        except Exception:
                            logger.warning("Live safety participant revocation pending")


def prepare_live_safely(database, incident, service):
    if service.status != "configured":
        return
    try:
        service.ensure_room(database, incident)
    except Exception:
        logger.warning("Live safety setup failed; SOS and notifications are unaffected")


def close_live_safely(database, incident, service):
    try:
        service.close(database, incident)
    except Exception:
        logger.warning("Live safety cleanup pending; incident remains ended")
