from __future__ import annotations

import base64
import os
import re
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


def _blue_wall_mask(image: np.ndarray) -> np.ndarray:
    """Detect common blue/purple architectural wall strokes without selecting gray paper/text."""
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    hsv_blue = cv2.inRange(hsv, np.array([85, 45, 35], dtype=np.uint8), np.array([150, 255, 255], dtype=np.uint8))
    b, g, r = cv2.split(image)
    dominant = (
        (b.astype(np.int16) >= r.astype(np.int16) + 18)
        & (b.astype(np.int16) >= g.astype(np.int16) + 6)
        & (b >= 65)
    ).astype(np.uint8) * 255
    blue = cv2.bitwise_or(hsv_blue, dominant)
    return cv2.morphologyEx(blue, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def _fallback_wall_mask(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    _, dark = cv2.threshold(gray, 120, 255, cv2.THRESH_BINARY_INV)
    blue = _blue_wall_mask(image)
    binary = cv2.bitwise_or(dark, blue)
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


def _seal_room_boundaries(wall_mask: np.ndarray) -> np.ndarray:
    """Temporarily bridge door-sized gaps for room counting without changing returned wall geometry."""
    h, w = wall_mask.shape[:2]
    base = ((wall_mask > 0) * 255).astype(np.uint8)
    gap = max(7, int(round(min(w, h) * 0.045)))
    gap = min(gap, max(11, int(round(min(w, h) * 0.075))))
    horizontal_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (gap, 3))
    vertical_kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (3, gap))
    horizontal = cv2.morphologyEx(base, cv2.MORPH_CLOSE, horizontal_kernel)
    vertical = cv2.morphologyEx(base, cv2.MORPH_CLOSE, vertical_kernel)
    sealed = cv2.bitwise_or(base, cv2.bitwise_or(horizontal, vertical))
    sealed = cv2.morphologyEx(sealed, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8), iterations=1)
    return cv2.dilate(sealed, np.ones((3, 3), np.uint8), iterations=1)


def _extract_enclosed_rooms(wall_mask: np.ndarray, confidence: int) -> list[dict[str, Any]]:
    h, w = wall_mask.shape[:2]
    if h < 16 or w < 16:
        return []
    sealed = _seal_room_boundaries(wall_mask)
    free = cv2.bitwise_not(sealed)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(free, connectivity=8)
    total = float(w * h)
    min_area = max(180.0, total * 0.0015)
    max_area = total * 0.48
    rooms: list[dict[str, Any]] = []

    for label in range(1, count):
        x, y, rw, rh, area = map(int, stats[label])
        if area < min_area or area > max_area:
            continue
        if x <= 1 or y <= 1 or x + rw >= w - 1 or y + rh >= h - 1:
            continue
        if rw < max(8, int(w * 0.018)) or rh < max(8, int(h * 0.018)):
            continue
        component = np.zeros((h, w), dtype=np.uint8)
        component[labels == label] = 255
        contours, _ = cv2.findContours(component, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not contours:
            continue
        contour = max(contours, key=cv2.contourArea)
        perimeter = cv2.arcLength(contour, True)
        approx = cv2.approxPolyDP(contour, max(1.5, perimeter * 0.018), True)
        points = approx.reshape(-1, 2) if len(approx) >= 3 else contour.reshape(-1, 2)
        if len(points) < 3:
            continue
        if len(points) > 16:
            rect = cv2.boxPoints(cv2.minAreaRect(contour)).astype(np.int32)
            points = rect
        polygon = [
            {"x": float(px) / max(w, 1) * 100.0, "y": float(py) / max(h, 1) * 100.0}
            for px, py in points
        ]
        rooms.append({
            "id": f"remote-room-{len(rooms)}",
            "name": f"مساحة مكتشفة {len(rooms) + 1}",
            "type": "unknown",
            "x": x / max(w, 1) * 100.0,
            "y": y / max(h, 1) * 100.0,
            "width": rw / max(w, 1) * 100.0,
            "height": rh / max(h, 1) * 100.0,
            "area_m2": 0.0,
            "confidence": confidence,
            "polygon": polygon,
        })
        if len(rooms) >= 60:
            break
    return rooms


def _recover_sparse_walls(image: np.ndarray, walls: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], bool, np.ndarray | None]:
    if len(walls) >= 3:
        return walls, False, None
    fallback_mask = _fallback_wall_mask(image)
    recovered = _extract_lines(fallback_mask, confidence=72)
    if len(recovered) >= 3 and len(recovered) > len(walls):
        return recovered, True, fallback_mask
    return walls, False, None


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
    h0, w0 = image.shape[:2]
    max_side = max(h0, w0)
    if max_side < 2200:
        scale = 2200.0 / max(max_side, 1)
        work = cv2.resize(image, (max(1, int(round(w0 * scale))), max(1, int(round(h0 * scale)))), interpolation=cv2.INTER_CUBIC)
    else:
        work = image
    h, w = work.shape[:2]
    result = reader.readtext(work, detail=1, paragraph=False)
    out: list[dict[str, Any]] = []
    for item in result[:700]:
        box, text, score = item
        if not text or score < 0.22:
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


_DIGIT_MAP = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٫,", "01234567890123456789..")


def _normalize_digits(text: str) -> str:
    return text.translate(_DIGIT_MAP)


def _area_from_text(text: str) -> float | None:
    normalized = _normalize_digits(text).lower()
    match = re.search(r"(?<!\d)(\d{1,4}(?:\.\d{1,3})?)\s*(?:m\s*[²2]|م\s*[²2])", normalized)
    if not match:
        return None
    try:
        value = float(match.group(1))
    except ValueError:
        return None
    return value if 0.2 <= value <= 2000.0 else None


def _room_type(text: str) -> str:
    t = text.lower()
    rules = [
        (("مجلس", "majlis"), "majlis"),
        (("صالة", "معيشة", "living"), "living"),
        (("مطبخ", "kitchen"), "kitchen"),
        (("حمام", "دورة مياه", "مغسلة", "bath", "wc"), "bath"),
        (("درج", "سلم", "stair"), "stairs"),
        (("مخزن", "مستودع", "store"), "storage"),
        (("غرفة", "نوم", "bed"), "bedroom"),
        (("ممر", "corridor", "hall"), "corridor"),
    ]
    for keys, value in rules:
        if any(key in t for key in keys):
            return value
    return "unknown"


def _label_rooms_from_ocr(rooms: list[dict[str, Any]], lines: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not rooms or not lines:
        return rooms
    for room in rooms:
        x1 = float(room.get("x", 0.0)) - 1.0
        y1 = float(room.get("y", 0.0)) - 1.0
        x2 = x1 + float(room.get("width", 0.0)) + 2.0
        y2 = y1 + float(room.get("height", 0.0)) + 2.0
        inside: list[dict[str, Any]] = []
        for line in lines:
            cx = (float(line.get("left_pct", 0.0)) + float(line.get("right_pct", 0.0))) / 2.0
            cy = (float(line.get("top_pct", 0.0)) + float(line.get("bottom_pct", 0.0))) / 2.0
            if x1 <= cx <= x2 and y1 <= cy <= y2:
                inside.append(line)
        if not inside:
            continue

        area_candidates = [(line, _area_from_text(str(line.get("text", "")))) for line in inside]
        area_candidates = [(line, area) for line, area in area_candidates if area is not None]
        if area_candidates:
            line, area = max(area_candidates, key=lambda item: float(item[0].get("confidence", 0)))
            room["area_m2"] = float(area)
            room["confidence"] = max(int(room.get("confidence", 0)), min(96, int(line.get("confidence", 0)) + 6))

        name_candidates: list[tuple[float, str, dict[str, Any]]] = []
        for line in inside:
            text = str(line.get("text", "")).strip()
            if not text:
                continue
            letters = sum(ch.isalpha() for ch in text)
            if letters < 2:
                continue
            score = float(line.get("confidence", 0)) + min(20.0, letters * 1.5)
            if _room_type(text) != "unknown":
                score += 18.0
            name_candidates.append((score, text, line))
        if name_candidates:
            _, text, line = max(name_candidates, key=lambda item: item[0])
            room["name"] = text[:80]
            detected_type = _room_type(text)
            if detected_type != "unknown":
                room["type"] = detected_type
            room["confidence"] = max(int(room.get("confidence", 0)), min(96, int(line.get("confidence", 0)) + 4))
    return rooms


def parse_floorplan(image_base64: str) -> dict[str, Any]:
    image = _decode_image(image_base64)
    runtime = load_cubicasa_runtime()
    openings: list[dict[str, Any]] = []
    coverage: dict[str, float] = {}
    warnings: list[str] = []
    room_mask: np.ndarray | None = None

    if runtime is not None:
        prediction = runtime.predict(image)
        wall_mask = ((prediction == 1) * 255).astype(np.uint8)
        room_mask = wall_mask
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
            room_mask = mask
            walls = _extract_lines(mask, confidence=82)
            model_used = "onnx"
            model_meta = {"name": "configured-onnx", "license": "operator-supplied"}
            base_confidence = 80
        else:
            mask = _fallback_wall_mask(image)
            room_mask = mask
            walls = _extract_lines(mask, confidence=72)
            model_used = "opencv-blue-aware-fallback"
            model_meta = model_status()
            base_confidence = 62
            warnings.append("Real segmentation weights are not available; blue-aware deterministic OpenCV evidence was used.")

    if model_used != "opencv-blue-aware-fallback":
        walls, recovered, recovered_mask = _recover_sparse_walls(image, walls)
        if recovered:
            room_mask = recovered_mask
            model_used = f"{model_used}+opencv-blue-recovery"
            base_confidence = min(base_confidence, 72)
            warnings.append("Segmentation returned insufficient wall geometry; blue-aware OpenCV recovery supplied reviewable wall evidence.")

    rooms = _extract_enclosed_rooms(room_mask, confidence=max(62, base_confidence - 6)) if room_mask is not None and len(walls) >= 3 else []
    ocr_lines = _ocr(image)
    rooms = _label_rooms_from_ocr(rooms, ocr_lines)
    if walls and not rooms:
        warnings.append("Walls were detected but no closed room regions were reliable enough; review the wall overlay before 3D.")

    evidence_bonus = min(len(walls), 14) // 3 + min(len(rooms), 10) // 3 + (3 if ocr_lines else 0)
    confidence = min(98, base_confidence + evidence_bonus) if walls else 0
    return {
        "model_used": model_used,
        "model": model_meta,
        "arabic_ocr": "easyocr" if _easy_reader() is not None else "unavailable",
        "confidence": confidence,
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "class_coverage": coverage,
        "warnings": warnings,
    }
