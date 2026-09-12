from __future__ import annotations

import base64
import os
from functools import lru_cache
from typing import Any

import cv2
import numpy as np


def _decode_image(image_base64: str) -> np.ndarray:
    raw = image_base64.split(",", 1)[-1]
    data = base64.b64decode(raw, validate=True)
    array = np.frombuffer(data, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("invalid image")
    return image


@lru_cache(maxsize=1)
def _onnx_session():
    path = os.getenv("FLOORPLAN_ONNX_MODEL", "").strip()
    if not path or not os.path.exists(path):
        return None
    try:
        import onnxruntime as ort
        return ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    except Exception:
        return None


@lru_cache(maxsize=1)
def _easy_reader():
    try:
        import easyocr
        return easyocr.Reader(["ar", "en"], gpu=False, verbose=False)
    except Exception:
        return None


def _mask_from_onnx(image: np.ndarray, session: Any) -> np.ndarray | None:
    try:
        inp = session.get_inputs()[0]
        shape = inp.shape
        target_h = int(shape[2]) if len(shape) >= 4 and isinstance(shape[2], int) else 512
        target_w = int(shape[3]) if len(shape) >= 4 and isinstance(shape[3], int) else 512
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (target_w, target_h), interpolation=cv2.INTER_AREA)
        tensor = resized.astype(np.float32) / 255.0
        tensor = np.transpose(tensor, (2, 0, 1))[None, ...]
        out = session.run(None, {inp.name: tensor})[0]
        arr = np.asarray(out)
        if arr.ndim == 4:
            arr = arr[0]
        if arr.ndim == 3:
            arr = arr[0] if arr.shape[0] <= arr.shape[-1] else arr[..., 0]
        arr = cv2.resize(arr.astype(np.float32), (image.shape[1], image.shape[0]), interpolation=cv2.INTER_LINEAR)
        return ((arr > 0.5) * 255).astype(np.uint8)
    except Exception:
        return None


def _fallback_wall_mask(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    _, binary = cv2.threshold(gray, 120, 255, cv2.THRESH_BINARY_INV)
    h = max(9, image.shape[1] // 45)
    v = max(9, image.shape[0] // 45)
    hk = cv2.getStructuringElement(cv2.MORPH_RECT, (h, 2))
    vk = cv2.getStructuringElement(cv2.MORPH_RECT, (2, v))
    horizontal = cv2.morphologyEx(binary, cv2.MORPH_OPEN, hk)
    vertical = cv2.morphologyEx(binary, cv2.MORPH_OPEN, vk)
    return cv2.bitwise_or(horizontal, vertical)


def _extract_lines(mask: np.ndarray) -> list[dict[str, Any]]:
    h, w = mask.shape[:2]
    min_len = max(24, int(min(w, h) * 0.07))
    lines = cv2.HoughLinesP(mask, 1, np.pi / 180.0, threshold=35, minLineLength=min_len, maxLineGap=12)
    if lines is None:
        return []
    out: list[dict[str, Any]] = []
    for i, item in enumerate(lines[:240]):
        x1, y1, x2, y2 = map(int, item[0])
        length = float(np.hypot(x2 - x1, y2 - y1))
        if length < min_len:
            continue
        out.append({
            "id": f"remote-wall-{i}",
            "start": {"x": x1 / max(w, 1) * 100.0, "y": y1 / max(h, 1) * 100.0},
            "end": {"x": x2 / max(w, 1) * 100.0, "y": y2 / max(h, 1) * 100.0},
            "confidence": 76,
            "kind": "remote-segmentation-evidence",
        })
    return out


def _ocr(image: np.ndarray) -> list[dict[str, Any]]:
    reader = _easy_reader()
    if reader is None:
        return []
    h, w = image.shape[:2]
    result = reader.readtext(image, detail=1, paragraph=False)
    out: list[dict[str, Any]] = []
    for item in result[:500]:
        box, text, score = item
        if not text or score < 0.25:
            continue
        xs = [float(p[0]) for p in box]
        ys = [float(p[1]) for p in box]
        out.append({
            "text": str(text).strip(),
            "left_pct": min(xs) / max(w, 1) * 100.0,
            "top_pct": min(ys) / max(h, 1) * 100.0,
            "right_pct": max(xs) / max(w, 1) * 100.0,
            "bottom_pct": max(ys) / max(h, 1) * 100.0,
            "confidence": int(max(0.0, min(1.0, float(score))) * 100),
        })
    return out


def parse_floorplan(image_base64: str) -> dict[str, Any]:
    image = _decode_image(image_base64)
    session = _onnx_session()
    mask = _mask_from_onnx(image, session) if session is not None else None
    model_used = mask is not None
    if mask is None:
        mask = _fallback_wall_mask(image)
    walls = _extract_lines(mask)
    ocr_lines = _ocr(image)
    confidence = min(92, 54 + min(len(walls), 20) * 2 + (8 if model_used else 0)) if walls else 0
    return {
        "model_used": "onnx" if model_used else "opencv-fallback",
        "arabic_ocr": "easyocr" if _easy_reader() is not None else "unavailable",
        "confidence": confidence,
        "walls": walls,
        "ocr_lines": ocr_lines,
        "warnings": [] if model_used else ["ONNX model not configured; deterministic OpenCV evidence was used."],
    }
