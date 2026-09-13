from __future__ import annotations

import base64
from typing import Any

import cv2
import numpy as np

from .parser import _easy_reader, _label_rooms_from_ocr, parse_floorplan as legacy_parse_floorplan
from .parser_quality import (
    adaptive_ocr,
    architectural_wall_mask,
    assign_openings_to_walls,
    extract_curve_segments,
    merge_ocr_lines,
    merge_wall_evidence,
    verification_scores,
)


def _decode(image_base64: str) -> np.ndarray:
    raw = image_base64.split(",", 1)[-1]
    data = base64.b64decode(raw, validate=True)
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("invalid image")
    return image


def parse_floorplan(image_base64: str) -> dict[str, Any]:
    """Second-stage parser that retries weak scans instead of trusting one model pass."""
    result = legacy_parse_floorplan(image_base64)
    image = _decode(image_base64)

    seed_ocr = list(result.get("ocr_lines") or [])
    ocr_lines, ocr_meta = adaptive_ocr(image, _easy_reader(), seed_lines=seed_ocr)
    ocr_lines = merge_ocr_lines(ocr_lines)

    wall_mask = architectural_wall_mask(image)
    base_confidence = int(result.get("confidence") or 0)
    curve_confidence = 80 if "cubicasa" in str(result.get("model_used", "")) else 72
    curve_walls = extract_curve_segments(wall_mask, confidence=curve_confidence)
    walls = merge_wall_evidence(list(result.get("walls") or []) + curve_walls)

    openings = assign_openings_to_walls(list(result.get("openings") or []), walls)
    rooms = _label_rooms_from_ocr(list(result.get("rooms") or []), ocr_lines)

    model_used = str(result.get("model_used") or "unknown")
    quality = verification_scores(
        model_used=model_used,
        walls=walls,
        rooms=rooms,
        openings=openings,
        ocr_lines=ocr_lines,
        base_confidence=base_confidence,
    )

    warnings = list(result.get("warnings") or [])
    if ocr_meta.get("adaptive_retry"):
        warnings.append("Adaptive OCR automatically zoomed and re-read weak regions to recover small dimensions and room labels.")
    if curve_walls:
        warnings.append(f"Curve-aware geometry recovery added {len(curve_walls)} diagonal/polyline wall segment(s) for review.")
    orphaned = sum(1 for item in openings if not item.get("wallId"))
    if orphaned:
        warnings.append(f"{orphaned} detected opening(s) could not be attached to a verified wall and must stay reviewable.")

    result.update({
        "model_used": f"{model_used}+adaptive-v2",
        "confidence": quality["overall_verified"],
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "ocr_meta": ocr_meta,
        "quality": quality,
        "warnings": list(dict.fromkeys(warnings)),
    })
    return result
