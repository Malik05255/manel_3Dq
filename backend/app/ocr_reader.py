from __future__ import annotations

import base64
import json
import os
import urllib.request
from functools import lru_cache
from pathlib import Path
from typing import Any

import cv2
import numpy as np

BACKEND_ROOT = Path(__file__).resolve().parents[1]


def _portable_tessdata_dir(root: Path) -> Path | None:
    candidates = (
        root / "usr/share/tesseract-ocr/5/tessdata",
        root / "usr/share/tesseract-ocr/4.00/tessdata",
        root / "usr/share/tessdata",
    )
    for candidate in candidates:
        if (candidate / "ara.traineddata").is_file() and (candidate / "eng.traineddata").is_file():
            return candidate
    return None


def _configure_portable_tesseract(pytesseract_module: Any) -> None:
    root = Path(os.getenv("TESSERACT_PORTABLE_ROOT", str(BACKEND_ROOT / ".portable-tesseract"))).resolve()
    binary = root / "usr/bin/tesseract"
    if not binary.is_file():
        return
    pytesseract_module.pytesseract.tesseract_cmd = str(binary)
    lib_dirs = [
        root / "usr/lib/x86_64-linux-gnu",
        root / "lib/x86_64-linux-gnu",
        root / "usr/lib",
        root / "lib",
    ]
    existing = os.environ.get("LD_LIBRARY_PATH", "")
    portable = ":".join(str(path) for path in lib_dirs if path.is_dir())
    os.environ["LD_LIBRARY_PATH"] = ":".join(part for part in (portable, existing) if part)
    tessdata = _portable_tessdata_dir(root)
    if tessdata is not None:
        os.environ["TESSDATA_PREFIX"] = str(tessdata)


def _remote_endpoint() -> str:
    value = os.getenv("OCR_PROVIDER_URL", "").strip().rstrip("/")
    if not value:
        return ""
    return value if value.endswith("/v1/ocr") else f"{value}/v1/ocr"


class RemoteOCRReaderAdapter:
    """Use the dedicated OCR service while preserving the EasyOCR-compatible surface."""

    def __init__(self, endpoint: str, token: str) -> None:
        self.endpoint = endpoint
        self.token = token

    def readtext(self, image: np.ndarray, detail: int = 1, paragraph: bool = False) -> list[Any]:
        if image.size == 0:
            return []
        original_h, original_w = image.shape[:2]
        work = image
        scale = 1.0
        longest = max(original_h, original_w)
        max_remote = max(1200, min(2800, int(os.getenv("OCR_PROVIDER_MAX_DIM", "2400"))))
        if longest > max_remote:
            scale = max_remote / max(longest, 1)
            work = cv2.resize(
                image,
                (max(1, int(round(original_w * scale))), max(1, int(round(original_h * scale)))),
                interpolation=cv2.INTER_AREA,
            )

        ok, encoded = cv2.imencode(".png", work, [cv2.IMWRITE_PNG_COMPRESSION, 5])
        if not ok:
            return []
        payload = json.dumps(
            {"image_base64": base64.b64encode(encoded.tobytes()).decode("ascii")},
            separators=(",", ":"),
        ).encode("utf-8")
        headers = {"Content-Type": "application/json", "X-Manzili-OCR-Contract": "v1"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(self.endpoint, data=payload, headers=headers, method="POST")
        timeout = max(10.0, min(120.0, float(os.getenv("OCR_PROVIDER_TIMEOUT", "75"))))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = json.loads(response.read().decode("utf-8"))
        except Exception:
            return []

        result: list[Any] = []
        inverse = 1.0 / max(scale, 1e-9)
        for item in body.get("results", [])[:900]:
            if not isinstance(item, dict):
                continue
            text = str(item.get("text", "")).strip()
            box = item.get("box")
            try:
                score = float(item.get("score", 0.0))
                normalized_box = [[float(point[0]) * inverse, float(point[1]) * inverse] for point in box]
            except Exception:
                continue
            if text:
                result.append((normalized_box, text, max(0.0, min(1.0, score))))
        return result


class TesseractReaderAdapter:
    """Expose pytesseract through the small EasyOCR-compatible readtext surface we use."""

    def __init__(self, pytesseract_module: Any) -> None:
        self.pytesseract = pytesseract_module

    def readtext(self, image: np.ndarray, detail: int = 1, paragraph: bool = False) -> list[Any]:
        output = self.pytesseract.image_to_data(
            image,
            lang=os.getenv("TESSERACT_LANG", "ara+eng"),
            config=os.getenv("TESSERACT_CONFIG", "--psm 11"),
            output_type=self.pytesseract.Output.DICT,
        )
        result: list[Any] = []
        count = len(output.get("text", []))
        for index in range(count):
            text = str(output["text"][index] or "").strip()
            if not text:
                continue
            try:
                confidence = float(output["conf"][index])
                left = float(output["left"][index])
                top = float(output["top"][index])
                width = float(output["width"][index])
                height = float(output["height"][index])
            except (TypeError, ValueError, KeyError, IndexError):
                continue
            if confidence < 0.0 or width <= 0.0 or height <= 0.0:
                continue
            box = [
                [left, top],
                [left + width, top],
                [left + width, top + height],
                [left, top + height],
            ]
            score = max(0.0, min(1.0, confidence / 100.0))
            result.append((box, text, score))
        return result


@lru_cache(maxsize=1)
def cloud_ocr_reader() -> Any | None:
    endpoint = _remote_endpoint()
    if endpoint:
        return RemoteOCRReaderAdapter(endpoint, os.getenv("OCR_PROVIDER_TOKEN", "").strip())

    try:
        import pytesseract

        _configure_portable_tesseract(pytesseract)
        languages = set(pytesseract.get_languages(config=""))
        if not {"ara", "eng"}.issubset(languages):
            return None
        return TesseractReaderAdapter(pytesseract)
    except Exception:
        return None


def cloud_ocr_engine_name() -> str:
    reader = cloud_ocr_reader()
    if reader is None:
        return "unavailable"
    if isinstance(reader, RemoteOCRReaderAdapter):
        return "remote-tesseract-ara-eng"
    if isinstance(reader, TesseractReaderAdapter):
        return "tesseract-ara-eng"
    return "unknown"
