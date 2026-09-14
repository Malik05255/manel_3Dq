from fastapi.testclient import TestClient

from app.public_main import app


client = TestClient(app)


def test_public_parser_status_rejects_unknown_client():
    response = client.get("/v1/public/parser/status")
    assert response.status_code == 403


def test_public_parser_status_accepts_android_client():
    response = client.get(
        "/v1/public/parser/status",
        headers={"X-Manzili-Parser-Client": "android-accuracy-v5"},
    )
    assert response.status_code == 200
    body = response.json()
    assert "ready" in body
    assert "roboflow" in body
    assert body["preferred_path"] in {
        "roboflow-first+local-verifier",
        "cubicasa-tiled-consensus+accuracy-v3",
        "unavailable",
    }
