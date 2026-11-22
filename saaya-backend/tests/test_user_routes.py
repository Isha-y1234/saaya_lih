import pytest

from app.main import app


def test_user_routes_registered():
    paths = app.openapi()["paths"]
    assert "post" in paths["/users/google"]
    assert "get" in paths["/users/me"]
    assert {"post", "delete"} <= paths["/users/session"].keys()
    assert "get" in paths["/health"]


@pytest.mark.parametrize("method", ["post", "delete"])
def test_session_revocation_preserves_existing_android_contract(client, sign_in, method):
    owner, headers, _ = sign_in()
    assert client.get("/users/me", headers=headers).json()["_id"] == owner
    response = getattr(client, method)("/users/session", headers=headers)
    assert response.status_code == 204
    assert response.content == b""
    assert client.get("/users/me", headers=headers).status_code == 401
