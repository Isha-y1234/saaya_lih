import json
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from bson import ObjectId
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from fastapi.testclient import TestClient
from google.auth import crypt, jwt

from app import auth, main
from app.config import Settings


TEST_CLIENT_ID = "test-only.apps.googleusercontent.com"


def matches(document, query):
    for key, value in query.items():
        if key == "$or":
            if not any(matches(document, branch) for branch in value):
                return False
            continue
        if isinstance(value, dict):
            if "$gt" in value and (key not in document or document[key] <= value["$gt"]):
                return False
            if "$lte" in value and (key not in document or document[key] > value["$lte"]):
                return False
            if "$lt" in value and (key not in document or document[key] >= value["$lt"]):
                return False
        elif document.get(key) != value:
            return False
    return True


class Cursor(list):
    def limit(self, count):
        return Cursor(self[:count])

    def sort(self, fields):
        for field, direction in reversed(fields):
            super().sort(key=lambda document: document[field], reverse=direction < 0)
        return self


class Collection:
    def __init__(self):
        self.documents = []
        self.indexes = []

    def create_index(self, key, **options):
        self.indexes.append((key, options))

    def insert_one(self, document):
        document.setdefault("_id", ObjectId())
        self.documents.append(document.copy())
        return SimpleNamespace(inserted_id=document["_id"])

    def find_one(self, query):
        return next((doc.copy() for doc in self.documents if matches(doc, query)), None)

    def find(self, query):
        return Cursor(doc.copy() for doc in self.documents if matches(doc, query))

    def find_one_and_update(self, query, update, **options):
        document = next((doc for doc in self.documents if matches(doc, query)), None)
        if document is None:
            if not options.get("upsert", False):
                return None
            document = {"_id": ObjectId(), **{key: value for key, value in query.items()
                        if not key.startswith("$") and not isinstance(value, dict)},
                        **update.get("$setOnInsert", {})}
            self.documents.append(document)
        # Model Mongo's unique-token constraint for registration/reassignment tests.
        if "token" in query and any(doc is not document and doc.get("token") == query["token"] for doc in self.documents):
            self.documents.remove(document)
            from pymongo.errors import DuplicateKeyError
            raise DuplicateKeyError("test-only token uniqueness")
        document.update(update.get("$set", {}))
        return document.copy()

    def delete_one(self, query):
        document = next((doc for doc in self.documents if matches(doc, query)), None)
        if document is None:
            return SimpleNamespace(deleted_count=0)
        self.documents.remove(document)
        return SimpleNamespace(deleted_count=1)

    def delete_many(self, query):
        matching = [document for document in self.documents if matches(document, query)]
        for document in matching:
            self.documents.remove(document)
        return SimpleNamespace(deleted_count=len(matching))


class Database(dict):
    def __missing__(self, key):
        self[key] = Collection()
        return self[key]

    def command(self, command):
        assert command == "ping"
        return {"ok": 1}


@pytest.fixture
def database():
    return Database()


@pytest.fixture
def client(monkeypatch, database):
    class Mongo:
        def __getitem__(self, name):
            return database
        def close(self):
            pass
    monkeypatch.setattr(main, "MongoClient", lambda *args, **kwargs: Mongo())
    monkeypatch.setattr(main, "load_settings", lambda: Settings("mongodb://localhost:27017", "test", TEST_CLIENT_ID))
    with TestClient(main.app) as client:
        yield client


@pytest.fixture
def google_token(monkeypatch):
    # Exercise google-auth's real signature/audience/issuer/expiry verification with
    # a generated test key and a mocked certificate transport, never real credentials.
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.now(timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test-google-key")])
    cert = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
    cert = cert.serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(days=1))
    cert = cert.not_valid_after(now + timedelta(days=1)).sign(key, hashes.SHA256())
    cert_pem = cert.public_bytes(serialization.Encoding.PEM).decode()
    private_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                   serialization.NoEncryption())
    signer = crypt.RSASigner.from_string(private_pem, key_id="test-key")
    transport = lambda *args, **kwargs: SimpleNamespace(status=200, data=json.dumps({"test-key": cert_pem}).encode())
    monkeypatch.setattr(auth, "GoogleRequest", lambda: transport)

    def token(subject="google-a", **overrides):
        claims = dict(sub=subject, email=f"{subject}@example.com", name=f"User {subject}",
                      email_verified=True, aud=TEST_CLIENT_ID, iss="https://accounts.google.com",
                      iat=int(now.timestamp()), exp=int(now.timestamp()) + 3600)
        claims.update(overrides)
        return jwt.encode(signer, claims).decode()
    return token


@pytest.fixture
def sign_in(client, google_token):
    def login(subject="google-a", **overrides):
        response = client.post("/users/google", json={"idToken": google_token(subject, **overrides)})
        assert response.status_code == 200, response.text
        result = response.json()
        return result["user"]["_id"], {"Authorization": f"Bearer {result['token']}"}, result
    return login
