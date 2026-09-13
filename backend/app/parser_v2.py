from __future__ import annotations

import base64
from typing import Any

import cv2
import numpy as np

from .parser import _easy_reader, _label_rooms_from_ocr, parse_floorplan as legacy_parse_floorplan
from .parser_accuracy import (
    dimension_evidence,
    precision_quality,
    precision_rotated_ocr,
    precision_wall_evidence,
)
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
    """Accuracy-first parser with independent geometry and OCR consensus."""
    result = legacy_parse_floorplan(image_base64)
    image = _decode(image_base64)
    reader = _easy_reader()

    seed_ocr = list(result.get("ocr_lines") or [])
    ocr_lines, ocr_meta = adaptive_ocr(image, reader, seed_lines=seed_ocr)
    ocr_lines, rotated_passes = precision_rotated_ocr(image, reader, ocr_lines)
    ocr_lines = merge_ocr_lines(ocr_lines)
    dimensions = dimension_evidence(ocr_lines)

    wall_mask = architectural_wall_mask(image)
    model_used = str(result.get("model_used") or "unknown")
    curve_confidence = 82 if "cubicasa" in model_used else 73
    curve_walls = extract_curve_segments(wall_mask, confidence=curve_confidence)
    precision_confidence = 82 if "cubicasa" in model_used else 76
    precision_walls = precision_wall_evidence(image, confidence=precision_confidence)
    walls = merge_wall_evidence(list(result.get("walls") or []) + precision_walls + curve_walls)

    openings = assign_openings_to_walls(list(result.get("openings") or []), walls)
    rooms = _label_rooms_from_ocr(list(result.get("rooms") or []), ocr_lines)

    base_confidence = int(result.get("confidence") or 0)
    quality = verification_scores(
        model_used=model_used,
        walls=walls,
        rooms=rooms,
        openings=openings,
        ocr_lines=ocr_lines,
        base_confidence=base_confidence,
    )
    quality = precision_quality(
        quality,
        model_used=model_used,
        walls=walls,
        rooms=rooms,
        precision_walls=precision_walls,
        dimensions=dimensions,
    )

    warnings = list(result.get("warnings") or [])
    if ocr_meta.get("adaptive_retry"):
        warnings.append("Adaptive OCR automatically zoomed and re-read weak regions to recover small dimensions and room labels.")
    if rotated_passes:
        warnings.append("Vertical dimension text was re-read in both 90-degree orientations before confidence calibration.")
    if precision_walls:
        warnings.append(f"Independent multiscale wall recovery supplied {len(precision_walls)} supported wall segment(s) for consensus.")
    if curve_walls:
        warnings.append(f"Curve-aware geometry recovery added {len(curve_walls)} diagonal/polyline wall segment(s) for review.")
    orphaned = sum(1 for item in openings if not item.get("wallId"))
    if orphaned:
        warnings.append(f"{orphaned} detected opening(s) could not be attached to a verified wall and must stay reviewable.")
    if len(dimensions) < 2:
        warnings.append("Fewer than two plausible dimension readings were verified; metric scale confidence is intentionally capped.")
    if quality.get("wall_topology", 0) < 28 and walls:
        warnings.append("Wall topology is weak or fragmented; 3D should remain blocked until junctions are reviewed.")

    ocr_meta = dict(ocr_meta)
    ocr_meta["rotated_passes"] = rotated_passes
    ocr_meta["dimension_evidence"] = len(dimensions)

    result.update({
        "model_used": f"{model_used}+accuracy-v3",
        "confidence": quality["overall_verified"],
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "dimension_evidence": dimensions,
        "ocr_meta": ocr_meta,
        "quality": quality,
        "warnings": list(dict.fromkeys(warnings)),
    })
    return result
