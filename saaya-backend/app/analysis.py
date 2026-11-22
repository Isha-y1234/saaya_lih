"""Metadata-only analysis orchestration. Media never passes through this process."""
import hashlib
import json
import os
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse
from uuid import uuid4

import requests
from bson import ObjectId
from fastapi import HTTPException
from pymongo.errors import DuplicateKeyError

VISION = "@cf/meta/llama-3.2-11b-vision-instruct"
AUDIO = "@cf/openai/whisper"
TEXT = "@cf/meta/llama-3.2-3b-instruct"


def now():
    return datetime.now(timezone.utc)


def limit(name, default, low, high):
    try:
        return max(low, min(high, int(os.getenv(name, str(default)))))
    except ValueError:
        return default


def indexes(db):
    db["analysis_jobs"].create_index([("incidentId", 1), ("fingerprint", 1)], unique=True)
    db["analysis_jobs"].create_index([("incidentId", 1), ("requestId", 1)], unique=True)
    db["analysis_jobs"].create_index([("incidentId", 1), ("createdAt", -1)])
    db["analysis_assets"].create_index([("incidentId", 1), ("requestId", 1)], unique=True)


def atomic_allow(db, key, amount, ceiling, owner=None):
    """One Mongo document CAS: reservations survive failures; never refund uncertain calls."""
    collection = db["analysis_usage"]
    day = now().strftime("%Y-%m-%d")
    identity = os.getenv("CLOUDFLARE_ACCOUNT_ID", "unconfigured") + ":" + day
    try:
        collection.insert_one({"_id": identity, "used": 0, "entries": {}, "owners": {}})
    except DuplicateKeyError:
        pass
    for _ in range(12):
        doc = collection.find_one({"_id": identity})
        if doc.get("blocked"):
            return False
        if key in doc["entries"]:
            return True
        owners = doc["owners"].copy()
        if doc["used"] + amount > ceiling or (owner and owners.get(owner, 0) + amount > limit("AI_USER_DAILY_NEURONS", 3500, 1, 9000)):
            return False
        entries = {**doc["entries"], key: amount}
        if owner:
            owners[owner] = owners.get(owner, 0) + amount
        if collection.find_one_and_update({"_id": identity, "used": doc["used"], "entries": doc["entries"]},
                {"$set": {"used": doc["used"] + amount, "entries": entries, "owners": owners}}):
            return True
    return False


def rate_limit(db, user):
    key = f"rate:{user}:{now().strftime('%Y-%m-%dT%H:%M')}"
    col = db["analysis_rates"]
    try:
        col.insert_one({"_id": key, "count": 0})
    except DuplicateKeyError:
        pass
    for _ in range(8):
        doc = col.find_one({"_id": key})
        if doc["count"] >= 12:
            raise HTTPException(429, "Analysis request limit reached. Retry in one minute.")
        if col.find_one_and_update({"_id": key, "count": doc["count"]}, {"$set": {"count": doc["count"] + 1}}):
            return
    raise HTTPException(429, "Analysis busy. Retry later.")


def public(doc):
    return {"id": str(doc["_id"]), **{k: doc.get(k) for k in
            ("incidentId", "state", "reason", "createdAt", "updatedAt", "report", "reservedNeurons")}}


def create(db, incident, user, body):
    assets = []
    for identity in body.assetIds:
        if not ObjectId.is_valid(identity):
            raise HTTPException(422, "Invalid analysis asset")
        asset = db["analysis_assets"].find_one({"_id": ObjectId(identity), "incidentId": str(incident["_id"]), "state": "COMPLETE"})
        if not asset:
            raise HTTPException(404, "Analysis asset unavailable")
        assets.append(asset)
    identity = {"assets": sorted(body.assetIds), "notes": body.notes, "version": 1}
    if body.verificationOnly:
        identity["verificationOnly"] = True
    canonical = json.dumps(identity, sort_keys=True)
    fingerprint = hashlib.sha256(canonical.encode()).hexdigest()
    query = {"incidentId": str(incident["_id"]), "fingerprint": fingerprint}
    previous = db["analysis_jobs"].find_one({"incidentId": query["incidentId"], "requestId": str(body.requestId)})
    if previous and previous["fingerprint"] != fingerprint:
        raise HTTPException(409, "Job retry must use the same evidence and notes")
    existing = db["analysis_jobs"].find_one(query)
    if existing:
        return existing
    rate_limit(db, user)
    # Freeze chat at creation: retries use the same context, never silently change the report.
    chat = list(db["incident_messages"].find({"incidentId": query["incidentId"]}).sort([("_id", -1)]).limit(30))
    chat.reverse()
    if body.verificationOnly:
        chat = []
    stages = [{"assetId": str(a["_id"]), "kind": a["kind"], "state": "PENDING"} for a in assets]
    if not body.verificationOnly:
        stages.append({"kind": "report", "state": "PENDING"})
    doc = {"_id": ObjectId(), **query, "requestId": str(body.requestId), "ownerId": incident["ownerId"],
           "requestedBy": user, "verificationOnly": body.verificationOnly, "consentedAt": now(), "createdAt": now(), "updatedAt": now(),
           "state": "QUEUED", "reason": None, "stages": stages, "revision": 0, "reservedNeurons": 0,
           "notes": body.notes, "chat": [{"reference": str(c["_id"]), "timestamp": c["createdAt"].isoformat(),
                    "text": c["text"][:1000], "category": "participant_statement"} for c in chat]}
    try:
        db["analysis_jobs"].insert_one(doc)
    except DuplicateKeyError:
        existing = db["analysis_jobs"].find_one(query)
        if not existing:
            raise HTTPException(409, "Job retry must use the same evidence and notes") from None
        return existing
    return doc


def cloud_configuration():
    endpoint = os.getenv("AI_WORKER_URL", "")
    parsed = urlparse(endpoint)
    if parsed.scheme != "https" or not parsed.hostname or not parsed.hostname.endswith(".workers.dev") or parsed.query or parsed.fragment:
        raise HTTPException(503, "Cloud analysis is not configured")
    token = os.getenv("CLOUDFLARE_API_TOKEN", "")
    secret = os.getenv("AI_WORKER_SECRET", "")
    account = os.getenv("CLOUDFLARE_ACCOUNT_ID", "")
    if not token or len(secret) < 32 or len(account) != 32 or os.getenv("AI_FREE_PLAN_CONFIRMED") != "true":
        raise HTTPException(503, "Cloud analysis free-plan setup is incomplete")
    return endpoint, token, secret, account


def invoke(payload, *, activity_local=False):
    endpoint, token, secret, account = cloud_configuration()
    if activity_local:
        endpoint = endpoint.rstrip("/") + "/activity-local"
    # No redirects/retries: an ambiguous inference must not be executed twice.
    try:
        response = requests.post(endpoint, json=payload, headers={"Authorization": "Bearer " + secret,
            "X-Cf-Token": token, "X-Cf-Account": account}, timeout=(5, 65), allow_redirects=False)
        if activity_local and response.status_code in (401, 403):
            return {"status": "unavailable", "reason": "The Cloudflare Worker rejected the server credentials."}
        if activity_local and response.status_code in (429, 503):
            return {"status": "retry", "reason": "The Cloudflare Worker is busy or unavailable. Retry later."}
        if response.status_code != 200 or len(response.content) > 64000:
            return {"status": "unavailable"}
        return response.json()
    except (requests.RequestException, ValueError):
        return {"status": "uncertain"}


def advance(db, storage, doc):
    """One bounded stage per authenticated request; no Render daemon or media processing."""
    if doc["state"] in ("COMPLETE", "PARTIAL"):
        return doc
    if doc.get("retryAfter") and doc["retryAfter"] > now():
        return doc
    if doc["state"] == "RUNNING" and doc.get("leaseUntil", now()) > now():
        return doc
    lease = str(uuid4())
    claimed = db["analysis_jobs"].find_one_and_update({"_id": doc["_id"], "revision": doc["revision"]},
        {"$set": {"revision": doc["revision"] + 1, "lease": lease, "leaseUntil": now() + timedelta(seconds=90), "state": "RUNNING"}})
    if not claimed:
        return db["analysis_jobs"].find_one({"_id": doc["_id"]})
    stages = doc["stages"]
    selected = next((s for s in stages if s["state"] in ("PENDING", "INFLIGHT")), None)
    reason = None
    state = "QUEUED"
    reserved = doc["reservedNeurons"]
    if selected is None:
        state = "COMPLETE"
    elif selected["state"] == "INFLIGHT":
        selected.update(state="UNAVAILABLE", reason="Interrupted call; outcome unknown. Not repeated to prevent duplicate inference.")
    else:
        kind = selected["kind"]
        cost = {"frame": 700, "audio": 100, "report": 1200}[kind]
        payload = {"kind": kind, "model": {"frame": VISION, "audio": AUDIO, "report": TEXT}[kind], "verificationOnly": bool(doc.get("verificationOnly"))}
        try:
            if kind != "report":
                asset = db["analysis_assets"].find_one({"_id": ObjectId(selected["assetId"]), "incidentId": doc["incidentId"], "state": "COMPLETE"})
                if not asset:
                    raise HTTPException(404, "Analysis asset unavailable")
                if kind == "audio" and os.getenv("AI_WHISPER_WAV_VERIFIED") != "true" and not (doc.get("verificationOnly") and os.getenv("AI_ALLOW_AUDIO_VERIFICATION") == "true"):
                    selected.update(state="UNAVAILABLE", reason="WAV compatibility with the exact Whisper model has not been verified.")
                if selected["state"] == "PENDING":
                    payload.update(url=storage.get_url(asset), size=asset["fileSize"], sha256=asset["sha256"], versionId=asset["versionId"])
                selected.update(reference=asset.get("evidenceId") or str(asset["_id"]), offsetMs=asset["offsetMs"], timestamp=asset["capturedAt"].isoformat() if asset.get("capturedAt") else None)
            else:
                context_stages = [{k: s.get(k) for k in ("kind", "state", "reference", "timestamp", "offsetMs", "reason")} |
                                  {"unverifiedResult": json.dumps(s.get("result"), ensure_ascii=False)[:900]} for s in stages[:-1]]
                context_chat = [{**c, "text": c["text"][:200]} for c in doc["chat"]]
                payload["context"] = json.dumps({"stages": context_stages, "chat": context_chat, "notes": doc["notes"],
                    "limitations": "Bounded chat and AI outputs may be truncated; unavailable stages are not evidence of an event."}, ensure_ascii=False)
                while len(payload["context"]) > 16000 and context_chat:
                    context_chat.pop(0)
                    payload["context"] = json.dumps({"stages": context_stages, "chat": context_chat, "notes": doc["notes"]}, ensure_ascii=False)
            if selected["state"] == "PENDING":
                # Validate configuration before reserving or marking the call uncertain.
                cloud_configuration()
                key = f"{doc['_id']}:{stages.index(selected)}"
                if not atomic_allow(db, key, cost, limit("AI_DAILY_NEURONS", 8000, 1, 9000), doc["ownerId"]):
                    state, reason = "PAUSED", "Daily analysis budget exhausted. Retry after 00:00 UTC."
                else:
                    day = now().strftime("%Y-%m-%d")
                    if day not in selected.get("reservedDays", []):
                        reserved += cost
                        selected["reservedDays"] = selected.get("reservedDays", []) + [day]
                    selected["state"] = "INFLIGHT"
                    db["analysis_jobs"].find_one_and_update({"_id": doc["_id"], "lease": lease},
                        {"$set": {"stages": stages, "reservedNeurons": reserved}})
                    result = invoke(payload)
                    if result.get("status") in ("quota", "retry"):
                        selected["state"] = "PENDING"
                        state, reason = "PAUSED", result["reason"]
                        if result["status"] == "quota":
                            identity = os.getenv("CLOUDFLARE_ACCOUNT_ID", "unconfigured") + ":" + day
                            db["analysis_usage"].find_one_and_update({"_id": identity}, {"$set": {"blocked": True}})
                    else:
                        selected.update(state="COMPLETE" if result.get("status") == "ok" else "UNAVAILABLE",
                            result=result.get("result"), usage=result.get("usage"), reason=result.get("reason", "AI output is unverified and may be inaccurate."))
        except HTTPException as error:
            state, reason = "PAUSED", error.detail
    report = doc.get("report")
    if all(s["state"] not in ("PENDING", "INFLIGHT") for s in stages):
        state = "PARTIAL" if any(s["state"] == "UNAVAILABLE" for s in stages) else "COMPLETE"
        timeline = []
        for stage in (stages if doc.get("verificationOnly") else stages[:-1]):
            item = {**stage, "category": "ai_transcript" if stage["kind"] == "audio" else "ai_visual_observation", "confidence": "unverified"}
            if item.get("timestamp"):
                item["timestamp"] = (datetime.fromisoformat(item["timestamp"]) + timedelta(milliseconds=item.get("offsetMs", 0))).isoformat()
            timeline.append(item)
        timeline.extend(doc["chat"])
        timeline.sort(key=lambda s: s.get("timestamp") or "")
        report = {"timeline": timeline, "participantStatements": doc["chat"], "notes": doc["notes"],
                  "coverage": "Only the selected derived assets were analyzed. Unsupplied audio/frames are unavailable; absence of an observation is not evidence that an event did not occur.",
                  "interpretation": None if doc.get("verificationOnly") else stages[-1].get("result"), "verificationOnly": bool(doc.get("verificationOnly")), "limitations": ["AI-generated, unverified. Sampling can miss events.",
                  "Capture timestamps are device-reported. Offsets refer to each original recording.",
                  "Frame timestamps are requested native decoder sampling positions, not exact frame presentation times.",
                  "No person identification, accusation, legal conclusion or automatic SOS action is permitted."],
                  "confidence": "unverified", "unavailable": [s for s in stages if s["state"] == "UNAVAILABLE"]}
    db["analysis_jobs"].find_one_and_update({"_id": doc["_id"], "lease": lease}, {"$set": {
        "stages": stages, "state": state, "reason": reason, "report": report, "reservedNeurons": reserved,
        "retryAfter": now() + timedelta(seconds=30) if state == "PAUSED" else None, "updatedAt": now()}})
    return db["analysis_jobs"].find_one({"_id": doc["_id"]})

