from __future__ import annotations

import re
from typing import Any

import cv2
import numpy as np

from .cubicasa_model import _trim_process_memory
from .parser import _blue_wall_mask, _decode_image, _extract_enclosed_rooms, _label_rooms_from_ocr

_AREA_HINT_RE = re.compile(r"(?:m\s*[²2]|م\s*[²2]|مساح(?:ة|ه)|area)", re.IGNORECASE)


def _bridge_door_sized_gaps(mask: np.ndarray, ratio: float) -> np.ndarray:
    """Close likely door-width breaks without filling whole corridors or courtyards."""
    h, w = mask.shape[:2]
    gap = max(9, int(round(min(h, w) * ratio)))
    horizontal = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (gap, 3)),
    )
    vertical = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (3, gap)),
    )
    return cv2.bitwise_or(mask, cv2.bitwise_or(horizontal, vertical))


def _ocr_room_hint(lines: list[dict[str, Any]]) -> int:
    hinted_positions: set[tuple[int, int]] = set()
    for line in lines:
        text = str(line.get("text") or "").strip()
        if not text or not _AREA_HINT_RE.search(text):
            continue
        cx = (float(line.get("left_pct", 0.0)) + float(line.get("right_pct", 0.0))) / 2.0
        cy = (float(line.get("top_pct", 0.0)) + float(line.get("bottom_pct", 0.0))) / 2.0
        hinted_positions.add((round(cx / 4.0), round(cy / 4.0)))
    return len(hinted_positions)


def _candidate_score(count: int, target: int, ratio: float, current_count: int) -> tuple[float, float, float]:
    if count < current_count:
        return (10_000.0, ratio, -float(count))
    # Prefer a room count close to the structural/OCR target. Lower bridge ratios win ties.
    return (abs(float(count - target)), ratio, -float(count))


def enhance_blue_room_topology(image_base64: str, result: dict[str, Any]) -> dict[str, Any]:
    """Recover enclosed spaces from the original blue CAD wall raster.

    Reader V3 may correctly recover wall vectors while producing too few rooms because a
    vector-derived room mask is thinner and loses door-sized boundary evidence. For coherent
    blue CAD/PDF plans, derive room topology from the original structural raster instead.
    This never changes wall vectors, openings, OCR, or metric evidence.
    """
    recovery = dict(result.get("geometry_recovery") or {})
    selected = str(recovery.get("selected") or "")
    walls = list(result.get("walls") or [])
    current_rooms = list(result.get("rooms") or [])

    if "blue-raster" not in selected:
        return result
    if len(walls) < 6:
        return result

    expected_floor = max(3, int(round(len(walls) / 5.0)))
    if len(current_rooms) >= expected_floor:
        return result

    try:
        image = _decode_image(image_base64)
        blue = _blue_wall_mask(image)
        coverage = float(np.count_nonzero(blue)) / max(float(blue.size), 1.0)
        if coverage < 0.0012:
            return result

        ocr_lines = list(result.get("ocr_lines") or [])
        ocr_hint = _ocr_room_hint(ocr_lines)
        structural_target = max(3, int(round(len(walls) / 3.4)))
        target = max(ocr_hint, structural_target)
        target = min(24, target)

        candidates: list[tuple[float, list[dict[str, Any]], float]] = []
        for ratio in (0.050, 0.055, 0.060, 0.065):
            bridged = _bridge_door_sized_gaps(blue, ratio)
            rooms = _extract_enclosed_rooms(bridged, confidence=78)
            if not rooms or len(rooms) > 24:
                continue
            score = _candidate_score(len(rooms), target, ratio, len(current_rooms))[0]
            candidates.append((score, rooms, ratio))
            del bridged

        if not candidates:
            return result

        _, best_rooms, best_ratio = min(
            candidates,
            key=lambda item: (
                item[0],
                item[2],
                -len(item[1]),
            ),
        )
        if len(best_rooms) < len(current_rooms) + 2:
            return result

        labeled = _label_rooms_from_ocr(best_rooms, ocr_lines)
        updated = dict(result)
        updated["rooms"] = labeled

        recovery.update({
            "room_recovery_used": True,
            "room_count_before": len(current_rooms),
            "room_count_after": len(labeled),
            "room_target": target,
            "room_bridge_ratio": best_ratio,
            "room_blue_coverage": round(coverage, 6),
        })
        updated["geometry_recovery"] = recovery

        quality = dict(updated.get("quality") or {})
        quality["room_recovery"] = "blue-raster-door-gap-topology"
        quality["room_count_before_recovery"] = len(current_rooms)
        quality["room_count_after_recovery"] = len(labeled)
        updated["quality"] = quality

        warnings = [
            item
            for item in list(updated.get("warnings") or [])
            if "No reliable enclosed room regions" not in str(item)
        ]
        warnings.append(
            "Room topology was recovered from the original blue wall raster after closing only door-sized gaps; review before 3D."
        )
        updated["warnings"] = warnings
        return updated
    finally:
        _trim_process_memory()
