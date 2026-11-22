from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from pymongo.errors import ConfigurationError, ServerSelectionTimeoutError

from app import main, notifications
from app.config import Settings


@pytest.mark.parametrize("configured,initialized,status", [
    (False, False, "disabled"), (True, False, "initialization_failed"), (True, True, "initialized"),
])
def test_readiness_distinguishes_configuration_from_delivery(monkeypatch, client, configured, initialized, status):
    # Exercise startup again to verify the endpoint reports the actual initialized state.
    monkeypatch.setattr(main, "load_settings", lambda: Settings(
        "mongodb://localhost:27017", "test", "test-only.apps.googleusercontent.com",
        "test-private-path.json" if configured else ""))
    monkeypatch.setattr(main, "initialize_fcm", lambda settings: object() if initialized else None)
    with TestClient(main.app) as active:
        assert active.get("/health/fcm").json() == {"status": status, "deliveryVerified": False}
        assert active.get("/health").json() == {"status": "ok"}


@pytest.mark.parametrize("failure", [FileNotFoundError, PermissionError])
def test_admin_file_failures_are_sanitized(monkeypatch, caplog, failure):
    def missing(name):
        raise ValueError("missing app")
    def unreadable(path):
        raise failure("test-private-path-and-key")
    monkeypatch.setattr(notifications.firebase_admin, "get_app", missing)
    monkeypatch.setattr(notifications.credentials, "Certificate", unreadable)
    assert notifications.initialize_fcm(SimpleNamespace(firebase_service_account_file="test-private.json")) is None
    assert "test-private" not in caplog.text
    assert "FCM initialization failed" in caplog.text


def test_database_client_configuration_error_is_sanitized(monkeypatch, client, caplog):
    def invalid(*args, **kwargs):
        raise ConfigurationError("test-only-database-password")
    monkeypatch.setattr(main, "MongoClient", invalid)
    with pytest.raises(RuntimeError, match="MongoDB client initialization failed") as error:
        with TestClient(main.app):
            pass
    assert "test-only-database-password" not in str(error.value) + caplog.text


def test_database_index_startup_error_is_sanitized(monkeypatch, client, database, caplog):
    def unavailable(*args, **kwargs):
        raise ServerSelectionTimeoutError("test-only-database-password")
    monkeypatch.setattr(database["users"], "create_index", unavailable)
    with pytest.raises(RuntimeError, match="MongoDB startup failed") as error:
        with TestClient(main.app):
            pass
    assert "test-only-database-password" not in str(error.value) + caplog.text


def test_database_health_failure_is_503_even_when_fcm_disabled(monkeypatch, client, database):
    def unavailable(*args, **kwargs):
        raise ServerSelectionTimeoutError("test-only-database-password")
    monkeypatch.setattr(database, "command", unavailable)
    assert client.get("/health").status_code == 503
    assert client.get("/health/fcm").json() == {"status": "disabled", "deliveryVerified": False}


def test_wrong_firebase_project_is_rejected_without_exposing_credentials(monkeypatch, caplog):
    def missing(name):
        raise ValueError("missing app")
    monkeypatch.setattr(notifications.firebase_admin, "get_app", missing)
    monkeypatch.setattr(notifications.credentials, "Certificate", lambda path: SimpleNamespace(
        project_id="test-private-wrong-project"))
    def unexpected(*args, **kwargs):
        pytest.fail("A mismatched project must never initialize Firebase")
    monkeypatch.setattr(notifications.firebase_admin, "initialize_app", unexpected)
    settings = SimpleNamespace(firebase_service_account_file="test-private-file.json")
    firebase_app = notifications.initialize_fcm(settings)
    assert firebase_app is None
    assert notifications.fcm_readiness(settings, firebase_app)["status"] == "initialization_failed"
    assert "project does not match" in caplog.text
    assert "test-private" not in caplog.text


def test_fcm_routes_are_documented_and_require_bearer_auth(client):
    paths = client.get("/openapi.json").json()["paths"]
    for path, methods in {
        "/devices/token": ("post", "delete"),
        "/notification-links": ("get", "post"),
        "/notification-links/code": ("get",),
        "/notification-links/{contact_id}": ("delete",),
        "/incident-alerts/{id}": ("get",),
    }.items():
        for method in methods:
            assert paths[path][method]["security"] == [{"HTTPBearer": []}]


@pytest.mark.parametrize("failure", [ValueError, RuntimeError])
def test_invalid_admin_credentials_are_nonfatal_and_sanitized(monkeypatch, caplog, failure):
    def missing(name):
        raise ValueError("missing app")
    def invalid(path):
        raise failure("test-private-key-material")
    monkeypatch.setattr(notifications.firebase_admin, "get_app", missing)
    monkeypatch.setattr(notifications.credentials, "Certificate", invalid)
    settings = SimpleNamespace(firebase_service_account_file="test-private-file.json")
    assert notifications.initialize_fcm(settings) is None
    assert "test-private" not in caplog.text
