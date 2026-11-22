import os
import logging
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv


logger = logging.getLogger(__name__)
BACKEND_ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Settings:
    mongodb_uri: str
    mongodb_database: str
    google_web_client_id: str
    firebase_service_account_file: str = ""
    livekit_url: str = ""
    livekit_api_key: str = field(default="", repr=False)
    livekit_api_secret: str = field(default="", repr=False)


def load_settings() -> Settings:
    load_dotenv(BACKEND_ROOT / ".env")
    uri = os.getenv("MONGODB_URI", "").strip()
    database = os.getenv("MONGODB_DATABASE", "").strip()
    google_client_id = os.getenv("GOOGLE_WEB_CLIENT_ID", "").strip()
    missing = [name for name, value in (("MONGODB_URI", uri), ("MONGODB_DATABASE", database),
                                        ("GOOGLE_WEB_CLIENT_ID", google_client_id)) if not value]
    if missing:
        message = "Missing required configuration: " + ", ".join(missing)
        logger.error(message)
        raise RuntimeError(message)
    if not uri.startswith(("mongodb://", "mongodb+srv://")):
        message = "MONGODB_URI must use mongodb:// or mongodb+srv://"
        logger.error(message)
        raise RuntimeError(message)
    if not google_client_id.endswith(".apps.googleusercontent.com"):
        logger.error("GOOGLE_WEB_CLIENT_ID must be configured with the Web OAuth client ID")
        raise RuntimeError("GOOGLE_WEB_CLIENT_ID must be configured with the Web OAuth client ID")
    credential_file = os.getenv("FIREBASE_SERVICE_ACCOUNT_FILE", "").strip()
    if credential_file:
        path = Path(credential_file).expanduser()
        # Match dotenv resolution, independent of the process's working directory.
        credential_file = str(path if path.is_absolute() else BACKEND_ROOT / path)
    return Settings(mongodb_uri=uri, mongodb_database=database, google_web_client_id=google_client_id,
                    firebase_service_account_file=credential_file,
                    livekit_url=os.getenv("LIVEKIT_URL", "").strip(),
                    livekit_api_key=os.getenv("LIVEKIT_API_KEY", "").strip(),
                    livekit_api_secret=os.getenv("LIVEKIT_API_SECRET", "").strip())
