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
from .roboflow_parser import merge_room_evidence, roboflow_floorplan
from .wall_support import validate_wall_image_support


_ROOM_WORDS = (
    "غرفة",
    "نوم",
    "مجلس",
    "صالة",
    "مطبخ",
    "حمام",
    "مغسلة",
    "دورة مياه",
    "مخزن",
    "درج",
    "سلم",
    "room",
    "bedroom",
    "living",
    "kitchen",
    "bath",
    "wc",
    "majlis",
    "stairs",
)


def _decode(image_base64: str) -> np.ndarray:
    raw = image_base64.split(",", 1)[-1]
    data = base64.b64decode(raw, validate=True)
    image = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("invalid image")
    return image


def _room_label_count(lines: list[dict[str, Any]]) -> int:
    count = 0
    for line in lines:
        text = str(line.get("text", "")).strip().lower()
        if text and any(word in text for word in _ROOM_WORDS):
            count += 1
    return count


def parse_floorplan(image_base64: str) -> dict[str, Any]:
    """Roboflow-first parser with local evidence, source-image wall validation and conservative confidence."""
    local = legacy_parse_floorplan(image_base64)
    roboflow = roboflow_floorplan(image_base64)
    image = _decode(image_base64)
    reader = _easy_reader()

    seed_ocr = list(local.get("ocr_lines") or [])
    ocr_lines, ocr_meta = adaptive_ocr(image, reader, seed_lines=seed_ocr)
    ocr_lines, rotated_passes = precision_rotated_ocr(image, reader, ocr_lines)
    ocr_lines = merge_ocr_lines(ocr_lines)
    dimensions = dimension_evidence(ocr_lines)

    local_model = str(local.get("model_used") or "unknown")
    roboflow_used = bool(roboflow.get("used"))
    model_used = (
        f"{roboflow.get('model_used')}+verifier:{local_model}"
        if roboflow_used
        else local_model
    )

    wall_mask = architectural_wall_mask(image)
    curve_confidence = 84 if roboflow_used else (82 if "cubicasa" in local_model else 73)
    curve_walls = extract_curve_segments(wall_mask, confidence=curve_confidence)
    precision_confidence = 84 if roboflow_used else (82 if "cubicasa" in local_model else 76)
    precision_walls = precision_wall_evidence(image, confidence=precision_confidence)

    # Roboflow evidence is ordered first and normally has the highest supported confidence.
    # Local detectors remain in the consensus so a provider outage never destroys the parser.
    walls = merge_wall_evidence(
        list(roboflow.get("walls") or [])
        + list(local.get("walls") or [])
        + precision_walls
        + curve_walls
    )
    walls, rejected_walls = validate_wall_image_support(image, walls)

    primary_rooms = list(roboflow.get("rooms") or [])
    verifier_rooms = list(local.get("rooms") or [])
    rooms = merge_room_evidence(primary_rooms, verifier_rooms) if roboflow_used else verifier_rooms
    rooms = _label_rooms_from_ocr(rooms, ocr_lines)

    opening_seed = list(roboflow.get("openings") or []) + list(local.get("openings") or [])
    openings = assign_openings_to_walls(opening_seed, walls)

    base_confidence = int(local.get("confidence") or 0)
    if roboflow_used:
        rf_confidences = [
            int(item.get("confidence", 0))
            for item in list(roboflow.get("walls") or [])
            + list(roboflow.get("rooms") or [])
            + list(roboflow.get("openings") or [])
            if int(item.get("confidence", 0)) > 0
        ]
        if rf_confidences:
            base_confidence = min(90, int(sum(rf_confidences) / len(rf_confidences)))

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

    warnings = list(local.get("warnings") or []) + list(roboflow.get("warnings") or [])
    if rejected_walls:
        warnings.append(
            f"Source-image validation rejected {len(rejected_walls)} unsupported long wall segment(s) before review/3D."
        )
    if roboflow_used:
        warnings.append(
            f"Roboflow Universe is the primary floor-plan detector for this page ({len(primary_rooms)} room region(s), "
            f"{len(roboflow.get('walls') or [])} wall segment(s)); local parsing is verifier/fallback evidence."
        )
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

    # A plan containing many readable room labels cannot honestly be reported as one room.
    # Instead of presenting a plausible-looking 55-90%, force the result into review territory.
    label_count = _room_label_count(ocr_lines)
    if label_count >= 4 and len(rooms) <= max(1, label_count // 3):
        quality["overall_verified"] = min(int(quality.get("overall_verified", 0)), 35)
        quality["geometry"] = min(int(quality.get("geometry", 0)), 45)
        warnings.append(
            f"Consistency gate rejected the room count: OCR found {label_count} room/function label(s) "
            f"but geometry produced only {len(rooms)} room region(s). Automatic approval and 3D must stay blocked."
        )

    if roboflow_used and primary_rooms and len(rooms) < max(2, int(len(primary_rooms) * 0.65)):
        quality["overall_verified"] = min(int(quality.get("overall_verified", 0)), 42)
        warnings.append("Room fusion lost too much Roboflow primary evidence; result was downgraded for manual review.")

    ocr_meta = dict(ocr_meta)
    ocr_meta["rotated_passes"] = rotated_passes
    ocr_meta["dimension_evidence"] = len(dimensions)
    ocr_meta["room_label_count"] = label_count

    result = dict(local)
    result.update({
        "model_used": f"{model_used}+accuracy-v4+wall-support-v1",
        "confidence": int(quality["overall_verified"]),
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "dimension_evidence": dimensions,
        "ocr_meta": ocr_meta,
        "wall_validation_meta": {
            "kept": len(walls),
            "rejected": len(rejected_walls),
            "rejected_ids": [str(item.get("id") or "") for item in rejected_walls[:24]],
        },
        "roboflow_meta": {
            "used": roboflow_used,
            "models": list(roboflow.get("models") or []),
            "rooms": len(primary_rooms),
            "walls": len(roboflow.get("walls") or []),
            "openings": len(roboflow.get("openings") or []),
        },
        "quality": quality,
        "warnings": list(dict.fromkeys(warnings)),
    })
    return result
