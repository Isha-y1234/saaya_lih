from contextlib import asynccontextmanager
import logging
import asyncio
import secrets
from contextlib import suppress

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pymongo import MongoClient
from pymongo.errors import PyMongoError

from app.config import load_settings
from app.routes.incident_chat import router as chat_router
from app.routes.evidence import router as evidence_router
from app.evidence import EvidenceStorage
from app.routes.analysis import router as analysis_router
from app.routes.activity import indexes as activity_indexes, router as activity_router
from app.analysis import indexes as analysis_indexes
from app.routes.reports import router as reports_router
from app.routes.contacts import router
from app.routes.users import router as users_router
from app.routes.incidents import router as incidents_router
from app.routes.devices import router as devices_router
from app.routes.notification_links import router as notification_links_router
from app.routes.incident_alerts import router as incident_alerts_router
from app.notifications import SosNotifier, fcm_readiness, initialize_fcm
from app.live_safety import LiveSafetyService
from app.routes.live_safety import router as live_safety_router
from app.saaya_ids import initialize_saaya_ids


logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    try:
        client = MongoClient(
            settings.mongodb_uri,
            connect=False,
            tz_aware=True,
            serverSelectionTimeoutMS=5000,
            connectTimeoutMS=5000,
            socketTimeoutMS=5000,
        )
    except (PyMongoError, ValueError):
        logger.error("MongoDB client initialization failed; check MONGODB_URI")
        raise RuntimeError("MongoDB client initialization failed; check MONGODB_URI") from None
    try:
        app.state.database = client[settings.mongodb_database]
        app.state.settings = settings
        app.state.evidence_storage = EvidenceStorage()
        analysis_indexes(app.state.database)
        activity_indexes(app.state.database)
        app.state.database["incident_reports"].create_index([("incidentId", 1), ("createdAt", -1)])
        app.state.database["evidence"].create_index([("incidentId", 1), ("requestId", 1)], unique=True)
        app.state.database["evidence"].create_index([("incidentId", 1), ("state", 1), ("_id", -1)])
        app.state.sos_notifier = SosNotifier(initialize_fcm(settings))
        app.state.fcm_readiness = fcm_readiness(settings, app.state.sos_notifier.firebase_app)
        app.state.live_safety = LiveSafetyService(settings)
        app.state.database["users"].create_index("googleSub", unique=True)
        initialize_saaya_ids(app.state.database)
        app.state.database["trusted_contacts"].create_index(
            [("ownerId", 1), ("invitedRecipientId", 1)], unique=True,
            partialFilterExpression={"invitationStatus": "PENDING"},
            name="one_pending_invitation_per_pair",
        )
        app.state.database["trusted_contacts"].create_index(
            [("invitedRecipientId", 1), ("invitationStatus", 1)])
        for contact in app.state.database["trusted_contacts"].find(
                {"invitationStatus": "PENDING", "invitationId": None}):
            app.state.database["trusted_contacts"].find_one_and_update(
                {"_id": contact["_id"], "invitationStatus": "PENDING", "invitationId": None},
                {"$set": {"invitationId": secrets.token_urlsafe(24)}})
        app.state.database["auth_sessions"].create_index("tokenHash", unique=True)
        app.state.database["auth_sessions"].create_index("expiresAt", expireAfterSeconds=0)
        app.state.database["incidents"].create_index(
            "ownerId", unique=True, partialFilterExpression={"status": "ACTIVE"}, name="one_active_incident_per_user"
        )
        app.state.database["incidents"].create_index([("ownerId", 1), ("createdAt", -1)])
        app.state.database["device_tokens"].create_index("token", unique=True)
        app.state.database["device_tokens"].create_index("userId")
        app.state.database["notification_codes"].create_index("userId", unique=True)
        app.state.database["notification_codes"].create_index("code", unique=True)
        app.state.database["notification_links"].create_index([("ownerId", 1), ("contactId", 1)], unique=True)
        app.state.database["incident_notification_recipients"].create_index([("incidentId", 1), ("recipientId", 1)], unique=True)
        app.state.database["incident_messages"].create_index([("incidentId", 1), ("_id", 1)])
        app.state.database["livekit_sessions"].create_index("incidentId", unique=True)
        app.state.database["livekit_participants"].create_index([("incidentId", 1), ("identity", 1)], unique=True)
        async def reconcile_live_sessions():
            while True:
                try:
                    await asyncio.to_thread(app.state.live_safety.reconcile, app.state.database)
                except Exception:
                    logger.warning("Live safety reconciliation will retry")
                await asyncio.sleep(10)
        cleanup = asyncio.create_task(reconcile_live_sessions())
        try:
            yield
        finally:
            cleanup.cancel()
            with suppress(asyncio.CancelledError):
                await cleanup
    except (PyMongoError, ValueError):
        message = "MongoDB startup failed; check database configuration, network access, permissions and indexes"
        logger.error(message)
        raise RuntimeError(message) from None
    finally:
        client.close()


app = FastAPI(title="Saaya Trusted Circle", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=r"http://(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?",
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE", "PATCH"],
    allow_headers=["Content-Type", "Authorization"],
)
app.include_router(chat_router)
app.include_router(evidence_router)
app.include_router(analysis_router)
app.include_router(activity_router)
app.include_router(reports_router)
app.include_router(router)
app.include_router(users_router)
app.include_router(incidents_router)
app.include_router(devices_router)
app.include_router(notification_links_router)
app.include_router(incident_alerts_router)
app.include_router(live_safety_router)


@app.exception_handler(PyMongoError)
async def database_error_handler(request: Request, error: PyMongoError) -> JSONResponse:
    # Never expose connection strings, database internals, or driver error messages.
    return JSONResponse(status_code=503, content={"detail": "Database temporarily unavailable"})


@app.get("/health", tags=["Health"])
def health(request: Request) -> dict[str, str]:
    request.app.state.database.command("ping")
    return {"status": "ok"}


@app.get("/health/fcm", tags=["Health"])
def fcm_health(request: Request) -> dict[str, str | bool]:
    # Keep /health database-based: optional FCM failure must not take SOS offline.
    return request.app.state.fcm_readiness


@app.get("/health/live", tags=["Health"])
def live_health(request: Request):
    return {"status": request.app.state.live_safety.status, "deliveryVerified": False}
