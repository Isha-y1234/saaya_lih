"""Persisted incident chat; MongoDB polling supports multiple ASGI workers without a broker."""
import asyncio
from datetime import datetime, timezone

from bson import ObjectId
from fastapi import APIRouter, Depends, HTTPException, Query, Response, WebSocket, WebSocketDisconnect
from fastapi.security import HTTPAuthorizationCredentials
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from pymongo.errors import PyMongoError

from app.auth import CurrentUser, get_current_user
from app.database import get_database
from app.live_safety import authorized_incident, authorized_live_incident

router = APIRouter(tags=["Incident chat"])


class SendMessage(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)
    text: str = Field(min_length=1, max_length=2000)


def serialize(document):
    return {"id": str(document["_id"]), "incidentId": document["incidentId"],
            "senderId": document["senderId"], "text": document["text"],
            "createdAt": document["createdAt"].isoformat()}


def authorized_chat_incident(database, id, user_id):
    # Preserve legacy/history access; active SOS viewers use the same current
    # membership checks as LiveKit, without expanding evidence/report/AI access.
    try:
        return authorized_incident(database, id, user_id)
    except HTTPException as error:
        if error.status_code != 404:
            raise
        return authorized_live_incident(database, id, user_id)


def history(database, id, before=None, after=None):
    query = {"incidentId": id}
    if before or after:
        value = before or after
        if not ObjectId.is_valid(value):
            raise HTTPException(400, "Invalid message cursor")
        query["_id"] = {"$lt" if before else "$gt": ObjectId(value)}
    cursor = database["incident_messages"].find(query).sort([("_id", 1 if after else -1)])
    # Limit on the server, never materialize the complete incident history.
    documents = list(cursor.limit(100))
    if not after:
        documents.reverse()
    return [serialize(document) for document in documents]


@router.get("/incidents/{id}/chat/messages")
def get_history(id: str, user: CurrentUser, response: Response,
                before: str | None = Query(default=None), database=Depends(get_database)):
    incident = authorized_chat_incident(database, id, str(user["_id"]))
    response.headers["Cache-Control"] = "no-store"
    messages = history(database, id, before=before)
    return {"messages": messages, "readOnly": incident["status"] != "ACTIVE",
            "nextBefore": messages[0]["id"] if len(messages) == 100 else None}


def context(socket, id):
    authorization = socket.headers.get("authorization", "")
    scheme, _, token = authorization.partition(" ")
    credentials = HTTPAuthorizationCredentials(scheme=scheme, credentials=token) if scheme.lower() == "bearer" else None
    database = socket.app.state.database
    user = get_current_user(credentials, database)
    return database, user, authorized_chat_incident(database, id, str(user["_id"]))


def snapshot(socket, id, after):
    database, _, incident = context(socket, id)
    return {"type": "history" if after is None else "messages", "messages": history(database, id, after=after),
            "readOnly": incident["status"] != "ACTIVE"}


def save(socket, id, raw):
    body = SendMessage.model_validate_json(raw)
    database, user, incident = context(socket, id)
    # This conditional write is the acceptance point, ordered against ending the SOS.
    active = database["incidents"].find_one_and_update(
        {"_id": incident["_id"], "status": "ACTIVE"},
        {"$set": {"chatAcceptedAt": datetime.now(timezone.utc)}})
    if active is None:
        return {"type": "error", "detail": "Incident ended. Chat is read-only.", "readOnly": True}
    document = {"incidentId": id, "senderId": str(user["_id"]), "text": body.text,
                "createdAt": datetime.now(timezone.utc)}
    database["incident_messages"].insert_one(document)
    return {"type": "sent"}


@router.websocket("/incidents/{id}/chat/ws")
async def chat(socket: WebSocket, id: str, after: str | None = None):
    try:
        if after is not None and not ObjectId.is_valid(after):
            raise HTTPException(400, "Invalid message cursor")
        await asyncio.to_thread(context, socket, id)
    except HTTPException as error:
        await socket.close(code=4401 if error.status_code == 401 else 4404)
        return
    except PyMongoError:
        await socket.close(code=1011)
        return
    await socket.accept()
    try:
        while True:
            frame = await asyncio.to_thread(snapshot, socket, id, after)
            await socket.send_json(frame)
            if frame["messages"]:
                after = frame["messages"][-1]["id"]
            # Keep one receive alive: cancelling receive on each poll can lose frames.
            if 'receive' not in locals():
                receive = asyncio.create_task(socket.receive_text())
            done, _ = await asyncio.wait({receive}, timeout=0.5)
            if done:
                raw = receive.result()
                del receive
                if len(raw) > 16000:
                    await socket.close(code=1009)
                    return
                try:
                    result = await asyncio.to_thread(save, socket, id, raw)
                    await socket.send_json(result)
                except ValidationError:
                    await socket.send_json({"type": "error", "detail": "Enter 1–2000 characters of text."})
    except HTTPException as error:
        await socket.close(code=4401 if error.status_code == 401 else 4404)
    except KeyError:
        await socket.close(code=1003)
    except (WebSocketDisconnect, RuntimeError):
        pass
    except PyMongoError:
        await socket.close(code=1011)
    finally:
        if 'receive' in locals():
            receive.cancel()
            from contextlib import suppress
            with suppress(asyncio.CancelledError, WebSocketDisconnect):
                await receive

