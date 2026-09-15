from __future__ import annotations

import json

import cv2
import numpy as np

from app import ocr_reader


def test_remote_endpoint_normalizes_base_url(monkeypatch):
    monkeypatch.setenv("OCR_PROVIDER_URL", "https://example.invalid/")
    assert ocr_reader._remote_endpoint() == "https://example.invalid/v1/ocr"
    monkeypatch.setenv("OCR_PROVIDER_URL", "https://example.invalid/v1/ocr")
    assert ocr_reader._remote_endpoint() == "https://example.invalid/v1/ocr"


def test_remote_reader_is_preferred_without_loading_tesseract(monkeypatch):
    monkeypatch.setenv("OCR_PROVIDER_URL", "https://ocr.invalid")
    monkeypatch.setenv("OCR_PROVIDER_TOKEN", "secret")
    ocr_reader.cloud_ocr_reader.cache_clear()
    reader = ocr_reader.cloud_ocr_reader()
    assert isinstance(reader, ocr_reader.RemoteOCRReaderAdapter)
    assert ocr_reader.cloud_ocr_engine_name() == "remote-tesseract-ara-eng"
    ocr_reader.cloud_ocr_reader.cache_clear()


def test_remote_reader_rescales_boxes(monkeypatch):
    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def read(self):
            return json.dumps({
                "engine": "tesseract-ara-eng",
                "results": [{"box": [[10, 20], [30, 20], [30, 40], [10, 40]], "text": "3.50", "score": 0.91}],
            }).encode("utf-8")

    captured = {}

    def fake_urlopen(request, timeout):
        captured["authorization"] = request.headers.get("Authorization")
        captured["timeout"] = timeout
        return FakeResponse()

    monkeypatch.setattr(ocr_reader.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setenv("OCR_PROVIDER_MAX_DIM", "1200")
    reader = ocr_reader.RemoteOCRReaderAdapter("https://ocr.invalid/v1/ocr", "secret")
    image = np.full((1000, 2400, 3), 255, dtype=np.uint8)
    result = reader.readtext(image)
    assert len(result) == 1
    box, text, score = result[0]
    # 2400 -> 1200 gives a 0.5 upload scale, so returned coordinates map back x2.
    assert box[0] == [20.0, 40.0]
    assert box[2] == [60.0, 80.0]
    assert text == "3.50"
    assert score == 0.91
    assert captured["authorization"] == "Bearer secret"


def test_png_payload_is_decodable(monkeypatch):
    class FakeResponse:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def read(self):
            return b'{"results":[]}'

    def fake_urlopen(request, timeout):
        payload = json.loads(request.data.decode("utf-8"))
        import base64
        raw = base64.b64decode(payload["image_base64"])
        decoded = cv2.imdecode(np.frombuffer(raw, dtype=np.uint8), cv2.IMREAD_COLOR)
        assert decoded is not None
        assert decoded.shape[:2] == (80, 120)
        return FakeResponse()

    monkeypatch.setattr(ocr_reader.urllib.request, "urlopen", fake_urlopen)
    reader = ocr_reader.RemoteOCRReaderAdapter("https://ocr.invalid/v1/ocr", "")
    reader.readtext(np.full((80, 120, 3), 255, dtype=np.uint8))
