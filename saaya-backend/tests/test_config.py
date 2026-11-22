import pytest

from app.config import load_settings


def test_google_web_client_id_is_required_without_exposing_secrets(monkeypatch):
    monkeypatch.setattr("app.config.load_dotenv", lambda *args, **kwargs: None)
    monkeypatch.setenv("MONGODB_URI", "mongodb://localhost:27017")
    monkeypatch.setenv("MONGODB_DATABASE", "test")
    monkeypatch.delenv("GOOGLE_WEB_CLIENT_ID", raising=False)
    with pytest.raises(RuntimeError, match="GOOGLE_WEB_CLIENT_ID"):
        load_settings()
    monkeypatch.setenv("GOOGLE_WEB_CLIENT_ID", "test-only.apps.googleusercontent.com")
    assert load_settings().google_web_client_id == "test-only.apps.googleusercontent.com"


@pytest.mark.parametrize("missing", ["MONGODB_URI", "MONGODB_DATABASE", "GOOGLE_WEB_CLIENT_ID"])
def test_missing_startup_configuration_reports_only_variable_names(monkeypatch, caplog, missing):
    monkeypatch.setattr("app.config.load_dotenv", lambda *args, **kwargs: None)
    monkeypatch.setenv("MONGODB_URI", "mongodb://test-only-user:test-only-password@localhost:27017")
    monkeypatch.setenv("MONGODB_DATABASE", "test")
    monkeypatch.setenv("GOOGLE_WEB_CLIENT_ID", "test-only.apps.googleusercontent.com")
    monkeypatch.delenv(missing, raising=False)
    with pytest.raises(RuntimeError, match=missing) as error:
        load_settings()
    assert "test-only-password" not in str(error.value) + caplog.text


def test_relative_admin_path_is_resolved_from_backend_not_working_directory(monkeypatch, tmp_path):
    from app.config import BACKEND_ROOT
    monkeypatch.setattr("app.config.load_dotenv", lambda *args, **kwargs: None)
    monkeypatch.setenv("MONGODB_URI", "mongodb://localhost:27017")
    monkeypatch.setenv("MONGODB_DATABASE", "test")
    monkeypatch.setenv("GOOGLE_WEB_CLIENT_ID", "test-only.apps.googleusercontent.com")
    monkeypatch.setenv("FIREBASE_SERVICE_ACCOUNT_FILE", "private-config/credential.json")
    monkeypatch.chdir(tmp_path)
    assert load_settings().firebase_service_account_file == str(BACKEND_ROOT / "private-config/credential.json")
    absolute = str(tmp_path / "credential.json")
    monkeypatch.setenv("FIREBASE_SERVICE_ACCOUNT_FILE", absolute)
    assert load_settings().firebase_service_account_file == absolute


def test_invalid_uri_reports_format_without_echoing_credential(monkeypatch, caplog):
    monkeypatch.setattr("app.config.load_dotenv", lambda *args, **kwargs: None)
    monkeypatch.setenv("MONGODB_URI", "https://test-only-password@invalid")
    monkeypatch.setenv("MONGODB_DATABASE", "test")
    monkeypatch.setenv("GOOGLE_WEB_CLIENT_ID", "test-only.apps.googleusercontent.com")
    with pytest.raises(RuntimeError, match="MONGODB_URI") as error:
        load_settings()
    assert "test-only-password" not in str(error.value) + caplog.text
