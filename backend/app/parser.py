from __future__ import annotations

import base64
import os
from functools import lru_cache
from typing import Any

import cv2
import numpy as np

from .cubicasa_model import CLASS_NAMES, MODEL_LICENSE, MODEL_NAME, load_cubicasa_runtime, model_status


def _decode_image(image_base64: str) -> np.ndarray:
    raw = image_base64.split(",", 1)[-1]
    data = base64.b64decode(raw, validate=True)
    array = np.frombuffer(data, dtype=np.uint8)
    image = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("invalid image")
    if image.shape[0] * image.shape[1] > 24_000_000:
        raise ValueError("image is too large")
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
            if arr.shape[0] > 1 and arr.shape[0] < arr.shape[-1]:
                arr = np.argmax(arr, axis=0).astype(np.float32)
            else:
                arr = arr[0] if arr.shape[0] <= arr.shape[-1] else arr[..., 0]
        arr = cv2.resize(arr.astype(np.float32), (image.shape[1], image.shape[0]), interpolation=cv2.INTER_NEAREST)
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


def _extract_lines(mask: np.ndarray, confidence: int) -> list[dict[str, Any]]:
    h, w = mask.shape[:2]
    cleaned = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    min_len = max(24, int(min(w, h) * 0.055))
    lines = cv2.HoughLinesP(cleaned, 1, np.pi / 180.0, threshold=32, minLineLength=min_len, maxLineGap=14)
    if lines is None:
        return []
    out: list[dict[str, Any]] = []
    seen: set[tuple[int, int, int, int]] = set()
    for item in lines[:400]:
        x1, y1, x2, y2 = map(int, item[0])
        length = float(np.hypot(x2 - x1, y2 - y1))
        if length < min_len:
            continue
        if (x2, y2) < (x1, y1):
            x1, y1, x2, y2 = x2, y2, x1, y1
        key = (round(x1 / 8), round(y1 / 8), round(x2 / 8), round(y2 / 8))
        if key in seen:
            continue
        seen.add(key)
        out.append({
            "id": f"remote-wall-{len(out)}",
            "start": {"x": x1 / max(w, 1) * 100.0, "y": y1 / max(h, 1) * 100.0},
            "end": {"x": x2 / max(w, 1) * 100.0, "y": y2 / max(h, 1) * 100.0},
            "confidence": confidence,
            "kind": "remote-segmentation-evidence",
        })
        if len(out) >= 220:
            break
    return out


def _recover_sparse_walls(image: np.ndarray, walls: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], bool]:
    if len(walls) >= 3:
        return walls, False
    recovered = _extract_lines(_fallback_wall_mask(image), confidence=68)
    if len(recovered) >= 3 and len(recovered) > len(walls):
        return recovered, True
    return walls, False


def _extract_openings(mask: np.ndarray, kind: str, confidence: int) -> list[dict[str, Any]]:
    h, w = mask.shape[:2]
    cleaned = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    contours, _ = cv2.findContours(cleaned, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    min_area = max(8.0, float(w * h) * 0.000015)
    max_area = float(w * h) * 0.04
    out: list[dict[str, Any]] = []
    for contour in contours:
        area = float(cv2.contourArea(contour))
        if area < min_area or area > max_area:
            continue
        rect = cv2.minAreaRect(contour)
        (cx, cy), (rw, rh), angle = rect
        major = max(rw, rh)
        minor = min(rw, rh)
        if major < 2.0:
            continue
        width_pct = major / max(w, h, 1) * 100.0
        out.append({
            "id": f"remote-{kind}-{len(out)}",
            "type": kind,
            "x": float(cx) / max(w, 1) * 100.0,
            "y": float(cy) / max(h, 1) * 100.0,
            "width": max(0.25, width_pct),
            "rotation_deg": float(angle + (90.0 if rw < rh else 0.0)),
            "confidence": confidence,
            "source": "cubicasa-segmentation",
            "shape_ratio": float(major / max(minor, 1.0)),
        })
        if len(out) >= 80:
            break
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
    runtime = load_cubicasa_runtime()
    openings: list[dict[str, Any]] = []
    coverage: dict[str, float] = {}
    warnings: list[str] = []

    if runtime is not None:
        prediction = runtime.predict(image)
        wall_mask = ((prediction == 1) * 255).astype(np.uint8)
        door_mask = ((prediction == 2) * 255).astype(np.uint8)
        window_mask = ((prediction == 3) * 255).astype(np.uint8)
        walls = _extract_lines(wall_mask, confidence=90)
        openings = _extract_openings(door_mask, "door", 88) + _extract_openings(window_mask, "window", 88)
        total = max(float(prediction.size), 1.0)
        coverage = {name: round(float(np.count_nonzero(prediction == idx)) / total, 5) for idx, name in enumerate(CLASS_NAMES)}
        model_used = "cubicasa-unet-resnet34"
        model_meta = {"name": MODEL_NAME, "license": MODEL_LICENSE, "classes": list(CLASS_NAMES)}
        base_confidence = 90
    else:
        session = _onnx_session()
        mask = _mask_from_onnx(image, session) if session is not None else None
        if mask is not None:
            walls = _extract_lines(mask, confidence=82)
            model_used = "onnx"
            model_meta = {"name": "configured-onnx", "license": "operator-supplied"}
            base_confidence = 80
        else:
            mask = _fallback_wall_mask(image)
            walls = _extract_lines(mask, confidence=68)
            model_used = "opencv-fallback"
            model_meta = model_status()
            base_confidence = 56
            warnings.append("Real segmentation weights are not available; deterministic OpenCV evidence was used.")

    if model_used != "opencv-fallback":
        walls, recovered = _recover_sparse_walls(image, walls)
        if recovered:
            model_used = f"{model_used}+opencv-recovery"
            base_confidence = min(base_confidence, 68)
            warnings.append("Segmentation returned insufficient wall geometry; deterministic OpenCV recovery supplied reviewable wall evidence.")

    ocr_lines = _ocr(image)
    confidence = min(97, base_confidence + min(len(walls), 14) // 3 + (2 if ocr_lines else 0)) if walls else 0
    return {
        "model_used": model_used,
        "model": model_meta,
        "arabic_ocr": "easyocr" if _easy_reader() is not None else "unavailable",
        "confidence": confidence,
        "walls": walls,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "class_coverage": coverage,
        "warnings": warnings,
    }
