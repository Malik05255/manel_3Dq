from __future__ import annotations

import math
import re
from statistics import mean
from typing import Any

import cv2
import numpy as np

from .parser_quality import architectural_wall_mask, merge_ocr_lines

_DIGIT_TRANSLATION = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٫،,", "01234567890123456789...")
_NUMBER_RE = re.compile(r"(?<!\d)(\d{1,4}(?:\.\d{1,3})?)(?!\d)")
_UNIT_RE = re.compile(r"(?:\bm\b|m2|m²|cm|mm|م(?:تر)?|سم|مم|م2|م²)", re.IGNORECASE)
_AREA_RE = re.compile(r"(?:m\s*[²2]|م\s*[²2]|مساح(?:ة|ه)|area)", re.IGNORECASE)


def _norm_digits(value: str) -> str:
    text = value.translate(_DIGIT_TRANSLATION)
    while ".." in text:
        text = text.replace("..", ".")
    return text


def _sample_points(x1: float, y1: float, x2: float, y2: float) -> tuple[np.ndarray, np.ndarray]:
    length = max(2, int(round(math.hypot(x2 - x1, y2 - y1))))
    xs = np.linspace(x1, x2, length)
    ys = np.linspace(y1, y2, length)
    return xs, ys


def _line_support(mask: np.ndarray, x1: float, y1: float, x2: float, y2: float) -> float:
    xs, ys = _sample_points(x1, y1, x2, y2)
    xi = np.clip(xs.round().astype(np.int32), 0, mask.shape[1] - 1)
    yi = np.clip(ys.round().astype(np.int32), 0, mask.shape[0] - 1)
    return float(np.count_nonzero(mask[yi, xi])) / max(len(xi), 1)


def _band_support(mask: np.ndarray, x1: float, y1: float, x2: float, y2: float, radius: int = 2) -> float:
    """Estimate whether a detected line has wall-like thickness, not only a 1px center stroke."""
    xs, ys = _sample_points(x1, y1, x2, y2)
    dx, dy = x2 - x1, y2 - y1
    length = max(math.hypot(dx, dy), 1e-6)
    nx, ny = -dy / length, dx / length
    supports: list[float] = []
    for offset in range(-radius, radius + 1):
        xi = np.clip((xs + nx * offset).round().astype(np.int32), 0, mask.shape[1] - 1)
        yi = np.clip((ys + ny * offset).round().astype(np.int32), 0, mask.shape[0] - 1)
        supports.append(float(np.count_nonzero(mask[yi, xi])) / max(len(xi), 1))
    return float(mean(supports)) if supports else 0.0


def _segment_signature(segment: dict[str, Any]) -> tuple[float, float, float, float]:
    a = segment["start"]
    b = segment["end"]
    x1, y1 = float(a["x"]), float(a["y"])
    x2, y2 = float(b["x"]), float(b["y"])
    mx, my = (x1 + x2) / 2.0, (y1 + y2) / 2.0
    length = math.hypot(x2 - x1, y2 - y1)
    angle = math.degrees(math.atan2(y2 - y1, x2 - x1)) % 180.0
    return mx, my, length, angle


def _angle_delta(a: float, b: float) -> float:
    d = abs(a - b) % 180.0
    return min(d, 180.0 - d)


def _dedupe_segments(segments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    kept: list[dict[str, Any]] = []
    for segment in sorted(segments, key=lambda item: (int(item.get("confidence", 0)), _segment_signature(item)[2]), reverse=True):
        mx, my, length, angle = _segment_signature(segment)
        duplicate = False
        for other in kept:
            omx, omy, olength, oangle = _segment_signature(other)
            if _angle_delta(angle, oangle) > 4.0:
                continue
            if math.hypot(mx - omx, my - omy) <= max(1.0, min(length, olength) * 0.10):
                ratio = min(length, olength) / max(length, olength, 1e-6)
                if ratio >= 0.55:
                    duplicate = True
                    break
        if not duplicate:
            kept.append(segment)
        if len(kept) >= 320:
            break
    return kept


def precision_wall_evidence(image: np.ndarray, confidence: int = 78) -> list[dict[str, Any]]:
    """Recover thin/old/blue/diagonal wall vectors at multiple Hough scales.

    The semantic model supplies the primary wall class. This independent pass only keeps
    candidates supported along their length and across a small perpendicular band, which
    reduces false walls caused by dimension strings, leader lines and drawing annotations.
    """
    mask = architectural_wall_mask(image)
    h, w = mask.shape[:2]
    if min(h, w) < 64:
        return []

    closed = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8), iterations=1)
    min_side = min(h, w)
    candidates: list[dict[str, Any]] = []
    configs = (
        (max(18, int(min_side * 0.018)), 16, 10),
        (max(28, int(min_side * 0.030)), 24, 16),
        (max(42, int(min_side * 0.050)), 34, 22),
    )
    for min_len, threshold, gap in configs:
        lines = cv2.HoughLinesP(
            closed,
            1,
            np.pi / 360.0,
            threshold=threshold,
            minLineLength=min_len,
            maxLineGap=gap,
        )
        if lines is None:
            continue
        for raw in lines[:700]:
            x1, y1, x2, y2 = map(float, raw[0])
            length = math.hypot(x2 - x1, y2 - y1)
            if length < min_len:
                continue
            support = _line_support(closed, x1, y1, x2, y2)
            if support < 0.58:
                continue
            band = _band_support(closed, x1, y1, x2, y2, radius=2)
            # Keep genuinely thin legacy walls, but require very strong center support.
            if band < 0.22 and support < 0.86:
                continue
            thickness_bonus = max(0.0, min(8.0, (band - 0.22) * 20.0))
            local_conf = int(round(confidence + min(10.0, max(0.0, (support - 0.58) * 28.0)) + thickness_bonus))
            candidates.append({
                "id": f"precision-wall-{len(candidates)}",
                "start": {"x": x1 / w * 100.0, "y": y1 / h * 100.0},
                "end": {"x": x2 / w * 100.0, "y": y2 / h * 100.0},
                "confidence": max(55, min(94, local_conf)),
                "kind": "precision-multiscale-wall-evidence",
                "mask_support": round(support, 4),
                "band_support": round(band, 4),
            })
    return _dedupe_segments(candidates)


def _rotation_points(box: Any, rotation: str, original_w: int, original_h: int) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    for point in box:
        xr, yr = float(point[0]), float(point[1])
        if rotation == "cw":
            x = yr
            y = original_h - 1.0 - xr
        else:
            x = original_w - 1.0 - yr
            y = xr
        points.append((x, y))
    return points


def precision_rotated_ocr(image: np.ndarray, reader: Any, seed_lines: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    """Read vertical dimension strings by rotating the page in both directions."""
    if reader is None or image.size == 0:
        return merge_ocr_lines(seed_lines), 0

    h0, w0 = image.shape[:2]
    longest = max(h0, w0)
    scale = min(1.85, max(1.0, 2800.0 / max(longest, 1)))
    if scale > 1.01:
        work = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    else:
        work = image
    h, w = work.shape[:2]
    lab = cv2.cvtColor(work, cv2.COLOR_BGR2LAB)
    l, a, b = cv2.split(lab)
    l = cv2.createCLAHE(clipLimit=2.8, tileGridSize=(8, 8)).apply(l)
    enhanced = cv2.cvtColor(cv2.merge((l, a, b)), cv2.COLOR_LAB2BGR)

    lines = list(seed_lines)
    passes = 0
    for rotation, code in (("cw", cv2.ROTATE_90_CLOCKWISE), ("ccw", cv2.ROTATE_90_COUNTERCLOCKWISE)):
        rotated = cv2.rotate(enhanced, code)
        try:
            raw = reader.readtext(rotated, detail=1, paragraph=False)
        except Exception:
            continue
        passes += 1
        for item in raw[:700]:
            if not isinstance(item, (list, tuple)) or len(item) < 3:
                continue
            box, text, score = item
            text = str(text).strip()
            try:
                conf = float(score)
            except (TypeError, ValueError):
                continue
            if not text or conf < 0.24:
                continue
            points = _rotation_points(box, rotation, w, h)
            xs = [p[0] for p in points]
            ys = [p[1] for p in points]
            lines.append({
                "text": text,
                "left_pct": min(xs) / max(w, 1) * 100.0,
                "top_pct": min(ys) / max(h, 1) * 100.0,
                "right_pct": max(xs) / max(w, 1) * 100.0,
                "bottom_pct": max(ys) / max(h, 1) * 100.0,
                "confidence": int(max(0.0, min(1.0, conf)) * 100),
                "source": f"precision-rotated-{rotation}",
            })
    return merge_ocr_lines(lines), passes


def dimension_evidence(lines: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Return plausible *linear* dimension readings; area labels are deliberately excluded."""
    evidence: list[dict[str, Any]] = []
    for line in lines:
        text = str(line.get("text") or "").strip()
        normalized = _norm_digits(text)
        if _AREA_RE.search(normalized):
            continue
        values: list[float] = []
        for match in _NUMBER_RE.finditer(normalized):
            try:
                value = float(match.group(1))
            except ValueError:
                continue
            if 0.15 <= value <= 250.0:
                values.append(value)
        if not values:
            continue
        unit_hint = bool(_UNIT_RE.search(normalized))
        if not unit_hint and all(float(v).is_integer() and v >= 1000 for v in values):
            continue
        confidence = int(line.get("confidence", 0))
        for value in values[:4]:
            evidence.append({
                "value": value,
                "text": text,
                "confidence": confidence,
                "unit_hint": unit_hint,
                "left_pct": float(line.get("left_pct", 0.0)),
                "top_pct": float(line.get("top_pct", 0.0)),
                "right_pct": float(line.get("right_pct", 0.0)),
                "bottom_pct": float(line.get("bottom_pct", 0.0)),
                "source": line.get("source") or "ocr",
            })
    return evidence


def wall_topology_score(walls: list[dict[str, Any]], tolerance: float = 1.7) -> int:
    if len(walls) < 2:
        return 0

    def point_segment_distance(px: float, py: float, wall: dict[str, Any]) -> float:
        a, b = wall.get("start") or {}, wall.get("end") or {}
        x1, y1 = float(a.get("x", 0.0)), float(a.get("y", 0.0))
        x2, y2 = float(b.get("x", 0.0)), float(b.get("y", 0.0))
        dx, dy = x2 - x1, y2 - y1
        denom = dx * dx + dy * dy
        if denom <= 1e-8:
            return math.hypot(px - x1, py - y1)
        t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / denom))
        return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))

    connected = 0
    total = 0
    for index, wall in enumerate(walls):
        for endpoint_name in ("start", "end"):
            point = wall.get(endpoint_name) or {}
            px, py = float(point.get("x", 0.0)), float(point.get("y", 0.0))
            total += 1
            nearest = min(
                (point_segment_distance(px, py, other) for j, other in enumerate(walls) if j != index),
                default=999.0,
            )
            if nearest <= tolerance:
                connected += 1
    return int(round(connected / max(total, 1) * 100.0))


def precision_quality(
    base_quality: dict[str, int],
    *,
    model_used: str,
    walls: list[dict[str, Any]],
    rooms: list[dict[str, Any]],
    precision_walls: list[dict[str, Any]],
    dimensions: list[dict[str, Any]],
) -> dict[str, int]:
    topology = wall_topology_score(walls)
    independent = min(100, len(precision_walls) * 5)
    geometry = int(round(base_quality.get("geometry", 0) * 0.70 + topology * 0.20 + independent * 0.10))

    if dimensions:
        conf = [int(item.get("confidence", 0)) for item in dimensions]
        positioned = len({(round(float(item.get("left_pct", 0.0)) / 8), round(float(item.get("top_pct", 0.0)) / 8)) for item in dimensions})
        scale = int(round(min(100, positioned * 14) * 0.60 + (mean(conf[:20]) if conf else 0.0) * 0.40))
    else:
        scale = 0

    ocr = int(base_quality.get("ocr", 0))
    overall = int(round(geometry * 0.60 + ocr * 0.20 + scale * 0.20))
    overall = min(overall, int(base_quality.get("overall_verified", 0)) + 8)

    if len(walls) < 4:
        overall = min(overall, 45)
    if not rooms:
        overall = min(overall, 68)
    if len(dimensions) < 2:
        overall = min(overall, 76)
    if topology < 28:
        overall = min(overall, 72)
    if "fallback" in model_used:
        overall = min(overall, 74)
    overall = max(0, min(98, overall))

    result = dict(base_quality)
    result.update({
        "geometry": max(0, min(100, geometry)),
        "scale_evidence": max(0, min(100, scale)),
        "overall_verified": overall,
        "wall_topology": topology,
        "independent_wall_evidence": len(precision_walls),
        "dimension_evidence": len(dimensions),
    })
    return result
