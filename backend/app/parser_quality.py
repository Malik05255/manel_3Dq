from __future__ import annotations

import math
import re
from statistics import mean
from typing import Any

import cv2
import numpy as np

_NUMERIC_RE = re.compile(r"\d|[٠-٩۰-۹]")


def _normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def _clahe_color(image: np.ndarray) -> np.ndarray:
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.4, tileGridSize=(8, 8))
    l = clahe.apply(l)
    merged = cv2.merge((l, a, b))
    enhanced = cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)
    blur = cv2.GaussianBlur(enhanced, (0, 0), 1.0)
    return cv2.addWeighted(enhanced, 1.45, blur, -0.45, 0)


def _threshold_color(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 35, 11)
    return cv2.cvtColor(binary, cv2.COLOR_GRAY2BGR)


def _resize_for_ocr(image: np.ndarray) -> np.ndarray:
    h, w = image.shape[:2]
    longest = max(h, w)
    target = min(3200, max(2200, longest))
    if longest == target:
        return image
    scale = target / max(longest, 1)
    interpolation = cv2.INTER_CUBIC if scale > 1.0 else cv2.INTER_AREA
    return cv2.resize(image, (max(1, int(round(w * scale))), max(1, int(round(h * scale)))), interpolation=interpolation)


def _read_pass(reader: Any, image: np.ndarray, label: str, *, full_w: int, full_h: int, source_x: int = 0, source_y: int = 0, source_w: int | None = None, source_h: int | None = None) -> list[dict[str, Any]]:
    if reader is None or image.size == 0:
        return []
    source_w = source_w or image.shape[1]
    source_h = source_h or image.shape[0]
    read_h, read_w = image.shape[:2]
    try:
        raw = reader.readtext(image, detail=1, paragraph=False)
    except Exception:
        return []
    out: list[dict[str, Any]] = []
    for item in raw[:900]:
        if not isinstance(item, (list, tuple)) or len(item) < 3:
            continue
        box, text, score = item
        text = str(text).strip()
        try:
            confidence = float(score)
        except (TypeError, ValueError):
            continue
        if not text or confidence < 0.18:
            continue
        try:
            xs = [float(p[0]) for p in box]
            ys = [float(p[1]) for p in box]
        except Exception:
            continue
        if not xs or not ys:
            continue
        left = source_x + min(xs) / max(read_w, 1) * source_w
        right = source_x + max(xs) / max(read_w, 1) * source_w
        top = source_y + min(ys) / max(read_h, 1) * source_h
        bottom = source_y + max(ys) / max(read_h, 1) * source_h
        out.append({"text": text, "left_pct": left / max(full_w, 1) * 100.0, "top_pct": top / max(full_h, 1) * 100.0, "right_pct": right / max(full_w, 1) * 100.0, "bottom_pct": bottom / max(full_h, 1) * 100.0, "confidence": int(max(0.0, min(1.0, confidence)) * 100), "source": label})
    return out


def merge_ocr_lines(lines: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    for line in sorted(lines, key=lambda item: int(item.get("confidence", 0)), reverse=True):
        text = _normalize_text(str(line.get("text", "")))
        if not text:
            continue
        cx = (float(line.get("left_pct", 0.0)) + float(line.get("right_pct", 0.0))) / 2.0
        cy = (float(line.get("top_pct", 0.0)) + float(line.get("bottom_pct", 0.0))) / 2.0
        duplicate = False
        for kept in merged:
            if _normalize_text(str(kept.get("text", ""))) != text:
                continue
            kx = (float(kept.get("left_pct", 0.0)) + float(kept.get("right_pct", 0.0))) / 2.0
            ky = (float(kept.get("top_pct", 0.0)) + float(kept.get("bottom_pct", 0.0))) / 2.0
            if abs(cx - kx) <= 1.8 and abs(cy - ky) <= 1.8:
                duplicate = True
                break
        if not duplicate:
            merged.append(line)
        if len(merged) >= 800:
            break
    return merged


def adaptive_ocr(image: np.ndarray, reader: Any, seed_lines: list[dict[str, Any]] | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    seed_lines = list(seed_lines or [])
    if reader is None:
        merged = merge_ocr_lines(seed_lines)
        return merged, {"engine": "unavailable", "adaptive_retry": False, "passes": 0, "line_count": len(merged), "numeric_line_count": sum(1 for item in merged if _NUMERIC_RE.search(str(item.get("text", ""))))}

    work = _resize_for_ocr(image)
    h, w = work.shape[:2]
    enhanced = _clahe_color(work)
    lines = seed_lines + _read_pass(reader, enhanced, "adaptive-full-clahe", full_w=w, full_h=h)
    merged = merge_ocr_lines(lines)
    numeric_count = sum(1 for item in merged if _NUMERIC_RE.search(str(item.get("text", ""))))
    confidence_values = [int(item.get("confidence", 0)) for item in merged]
    average_confidence = mean(confidence_values) if confidence_values else 0.0

    weak = len(merged) < 10 or numeric_count < 3 or average_confidence < 55.0
    passes = 1
    if weak:
        tile_w = int(round(w * 0.58))
        tile_h = int(round(h * 0.58))
        starts_x = [0, max(0, w - tile_w)]
        starts_y = [0, max(0, h - tile_h)]
        for row, y0 in enumerate(starts_y):
            for col, x0 in enumerate(starts_x):
                x1 = min(w, x0 + tile_w)
                y1 = min(h, y0 + tile_h)
                crop = enhanced[y0:y1, x0:x1]
                if crop.size == 0:
                    continue
                zoom = cv2.resize(crop, None, fx=1.65, fy=1.65, interpolation=cv2.INTER_CUBIC)
                lines += _read_pass(reader, zoom, f"adaptive-tile-{row}-{col}", full_w=w, full_h=h, source_x=x0, source_y=y0, source_w=x1 - x0, source_h=y1 - y0)
                passes += 1
        merged = merge_ocr_lines(lines)
        numeric_count = sum(1 for item in merged if _NUMERIC_RE.search(str(item.get("text", ""))))

    if len(merged) < 6 or numeric_count < 2:
        threshold = _threshold_color(work)
        lines += _read_pass(reader, threshold, "adaptive-threshold", full_w=w, full_h=h)
        passes += 1
        merged = merge_ocr_lines(lines)
        numeric_count = sum(1 for item in merged if _NUMERIC_RE.search(str(item.get("text", ""))))

    return merged, {"engine": "easyocr-adaptive-v2", "adaptive_retry": weak, "passes": passes, "line_count": len(merged), "numeric_line_count": numeric_count, "working_width": w, "working_height": h}


def architectural_wall_mask(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.createCLAHE(clipLimit=2.2, tileGridSize=(8, 8)).apply(gray)
    dark = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 41, 12)
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    blue = cv2.inRange(hsv, np.array([82, 35, 28], dtype=np.uint8), np.array([155, 255, 255], dtype=np.uint8))
    combined = cv2.bitwise_or(dark, blue)
    h, w = combined.shape[:2]
    min_axis = max(12, int(round(min(h, w) * 0.018)))
    horizontal = cv2.morphologyEx(combined, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (min_axis, 2)))
    vertical = cv2.morphologyEx(combined, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (2, min_axis)))
    preserved = cv2.morphologyEx(combined, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8), iterations=1)
    preserved = cv2.morphologyEx(preserved, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8), iterations=1)
    return cv2.bitwise_or(cv2.bitwise_or(horizontal, vertical), preserved)


def _segment_angle_deg(x1: float, y1: float, x2: float, y2: float) -> float:
    value = abs(math.degrees(math.atan2(y2 - y1, x2 - x1))) % 180.0
    return min(value, 180.0 - value)


def extract_curve_segments(mask: np.ndarray, confidence: int = 76) -> list[dict[str, Any]]:
    h, w = mask.shape[:2]
    if h < 32 or w < 32:
        return []
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    min_len = max(16.0, min(w, h) * 0.022)
    out: list[dict[str, Any]] = []
    for contour in sorted(contours, key=cv2.contourArea, reverse=True)[:120]:
        if cv2.contourArea(contour) < max(20.0, w * h * 0.00002):
            continue
        perimeter = cv2.arcLength(contour, True)
        if perimeter < min_len * 2.0:
            continue
        approx = cv2.approxPolyDP(contour, max(1.5, perimeter * 0.006), True).reshape(-1, 2)
        if len(approx) < 3 or len(approx) > 64:
            continue
        for index in range(len(approx)):
            x1, y1 = map(float, approx[index])
            x2, y2 = map(float, approx[(index + 1) % len(approx)])
            length = math.hypot(x2 - x1, y2 - y1)
            if length < min_len:
                continue
            angle = _segment_angle_deg(x1, y1, x2, y2)
            if min(angle, abs(90.0 - angle)) < 8.0:
                continue
            out.append({"id": f"remote-curve-{len(out)}", "start": {"x": x1 / w * 100.0, "y": y1 / h * 100.0}, "end": {"x": x2 / w * 100.0, "y": y2 / h * 100.0}, "confidence": confidence, "kind": "remote-curve-polyline-evidence"})
            if len(out) >= 96:
                return out
    return out


def _endpoints_close(a: dict[str, Any], b: dict[str, Any], tolerance: float = 1.15) -> bool:
    a1, a2 = a.get("start", {}), a.get("end", {})
    b1, b2 = b.get("start", {}), b.get("end", {})
    def distance(p: dict[str, Any], q: dict[str, Any]) -> float:
        return math.hypot(float(p.get("x", 0.0)) - float(q.get("x", 0.0)), float(p.get("y", 0.0)) - float(q.get("y", 0.0)))
    return (distance(a1, b1) <= tolerance and distance(a2, b2) <= tolerance) or (distance(a1, b2) <= tolerance and distance(a2, b1) <= tolerance)


def merge_wall_evidence(walls: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    for wall in sorted(walls, key=lambda item: int(item.get("confidence", 0)), reverse=True):
        if not isinstance(wall, dict) or not isinstance(wall.get("start"), dict) or not isinstance(wall.get("end"), dict):
            continue
        if any(_endpoints_close(wall, kept) for kept in merged):
            continue
        merged.append(wall)
        if len(merged) >= 260:
            break
    for index, wall in enumerate(merged):
        wall["id"] = str(wall.get("id") or f"remote-wall-v2-{index}")
    return merged


def _distance_point_to_segment(px: float, py: float, wall: dict[str, Any]) -> float:
    a = wall.get("start") or {}
    b = wall.get("end") or {}
    x1, y1 = float(a.get("x", 0.0)), float(a.get("y", 0.0))
    x2, y2 = float(b.get("x", 0.0)), float(b.get("y", 0.0))
    dx, dy = x2 - x1, y2 - y1
    denom = dx * dx + dy * dy
    if denom <= 1e-9:
        return math.hypot(px - x1, py - y1)
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / denom))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def assign_openings_to_walls(openings: list[dict[str, Any]], walls: list[dict[str, Any]]) -> list[dict[str, Any]]:
    if not openings or not walls:
        return openings
    out: list[dict[str, Any]] = []
    for raw in openings:
        opening = dict(raw)
        px, py = float(opening.get("x", 0.0)), float(opening.get("y", 0.0))
        nearest = min(walls, key=lambda wall: _distance_point_to_segment(px, py, wall))
        distance = _distance_point_to_segment(px, py, nearest)
        if distance <= 4.25:
            opening["wallId"] = str(nearest.get("id"))
            opening["wall_distance_pct"] = round(distance, 3)
            geometric_confidence = max(55, min(96, int(round(100 - distance * 9.0))))
            opening["confidence"] = min(int(opening.get("confidence", 80)), geometric_confidence)
        out.append(opening)
    return out


def verification_scores(*, model_used: str, walls: list[dict[str, Any]], rooms: list[dict[str, Any]], openings: list[dict[str, Any]], ocr_lines: list[dict[str, Any]], base_confidence: int) -> dict[str, int]:
    wall_conf = [int(item.get("confidence", 0)) for item in walls]
    room_conf = [int(item.get("confidence", 0)) for item in rooms]
    geometry_mean = mean(wall_conf + room_conf) if wall_conf or room_conf else 0.0
    wall_coverage = min(100.0, len(walls) * 5.0)
    room_coverage = min(100.0, len(rooms) * 12.0)
    mapped_openings = sum(1 for item in openings if item.get("wallId"))
    opening_score = 0.0 if not openings else mapped_openings / max(len(openings), 1) * 100.0
    geometry = int(round(geometry_mean * 0.52 + wall_coverage * 0.28 + room_coverage * 0.15 + opening_score * 0.05))
    ocr_conf = [int(item.get("confidence", 0)) for item in ocr_lines]
    numeric_count = sum(1 for item in ocr_lines if _NUMERIC_RE.search(str(item.get("text", ""))))
    ocr = int(round((mean(ocr_conf[:40]) if ocr_conf else 0.0) * 0.72 + min(100, len(ocr_lines) * 4) * 0.18 + min(100, numeric_count * 14) * 0.10))
    scale = min(92, numeric_count * 13 + (16 if numeric_count >= 2 else 0))
    overall = int(round(geometry * 0.56 + ocr * 0.24 + scale * 0.20))
    overall = min(overall, max(0, base_confidence + 5))
    if len(walls) < 4:
        overall = min(overall, 48)
    if not rooms:
        overall = min(overall, 72)
    if numeric_count == 0:
        overall = min(overall, 80)
    if "fallback" in model_used:
        overall = min(overall, 76)
    if openings and mapped_openings * 2 < len(openings):
        overall = min(overall, 84)
    overall = max(0, min(96, overall))
    return {"geometry": max(0, min(100, geometry)), "ocr": max(0, min(100, ocr)), "scale_evidence": max(0, min(100, scale)), "overall_verified": overall, "mapped_openings": mapped_openings, "numeric_lines": numeric_count}
