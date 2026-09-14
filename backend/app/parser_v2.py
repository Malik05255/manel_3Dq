from __future__ import annotations

import base64
from typing import Any

import cv2
import numpy as np

from .ocr_reader import cloud_ocr_engine_name, cloud_ocr_reader
from .parser import _label_rooms_from_ocr, parse_floorplan as legacy_parse_floorplan
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
from .source_vectorizer import vectorize_source_walls
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


def _dict_items(source: dict[str, Any], key: str) -> list[dict[str, Any]]:
    value = source.get(key)
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _bounded_confidence(value: Any, ceiling: int = 100) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return 0
    return max(0, min(ceiling, number))


def parse_floorplan(
    image_base64: str,
    *,
    external_evidence: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Read the source plan first, then use cloud/model outputs as supporting evidence.

    The previous hybrid path merged wall coordinates from several readers into one graph.
    That can create a Frankenstein floor plan even when every individual reader is only
    slightly wrong. V2.2 reverses the authority: source pixels are vectorised first and,
    when they yield enough structural walls, those coordinates become the final wall graph.
    Legacy/Roboflow/local outputs still help with rooms, labels, openings and confidence.
    """
    local = legacy_parse_floorplan(image_base64)
    roboflow = roboflow_floorplan(image_base64)
    external = external_evidence if isinstance(external_evidence, dict) else {}
    external_used = bool(external)

    image = _decode(image_base64)
    reader = cloud_ocr_reader()

    external_ocr = _dict_items(external, "ocr_lines")
    seed_ocr = external_ocr + _dict_items(local, "ocr_lines")
    ocr_lines, ocr_meta = adaptive_ocr(image, reader, seed_lines=seed_ocr)
    ocr_lines, rotated_passes = precision_rotated_ocr(image, reader, ocr_lines)
    ocr_lines = merge_ocr_lines(ocr_lines)
    dimensions = dimension_evidence(ocr_lines)

    local_model = str(local.get("model_used") or "unknown")
    external_model = str(
        external.get("model_used")
        or external.get("reader_path")
        or "legacy-cloud-evidence"
    )
    roboflow_used = bool(roboflow.get("used"))
    model_parts: list[str] = []
    if roboflow_used:
        model_parts.append(str(roboflow.get("model_used") or "roboflow"))
    if external_used:
        model_parts.append(f"evidence:{external_model}")
    model_parts.append(f"local:{local_model}")
    model_used = "+".join(model_parts)

    # Independent source-pixel vectorisation is now the first wall authority.
    source_walls, source_meta = vectorize_source_walls(image)
    source_authoritative = bool(source_meta.get("authoritative"))

    precision_confidence = 86 if roboflow_used else (83 if external_used or "cubicasa" in local_model else 76)
    precision_walls = precision_wall_evidence(image, confidence=precision_confidence)

    external_walls = _dict_items(external, "walls")
    rejected_walls: list[dict[str, Any]] = []
    curve_walls: list[dict[str, Any]] = []
    if source_authoritative:
        # Crucial rule: do not merge cloud/model coordinates into a source wall graph.
        # They are evidence only. This removes the long crossing lines and duplicate wall
        # edges seen in real HAI uploads while preserving genuine source geometry.
        walls = source_walls
        suppressed = (
            _dict_items(roboflow, "walls")
            + external_walls
            + _dict_items(local, "walls")
        )
        for item in suppressed[:240]:
            copy = dict(item)
            copy["rejection_reason"] = "source-first-authority"
            rejected_walls.append(copy)
    else:
        # Fallback for sketches or unusual monochrome plans where direct vectorisation is
        # too sparse. Even here, every candidate must survive source-image support gates.
        wall_mask = architectural_wall_mask(image)
        curve_confidence = 84 if roboflow_used else (82 if external_used or "cubicasa" in local_model else 73)
        curve_walls = extract_curve_segments(wall_mask, confidence=curve_confidence)
        walls = merge_wall_evidence(
            source_walls
            + _dict_items(roboflow, "walls")
            + external_walls
            + _dict_items(local, "walls")
            + precision_walls
            + curve_walls
        )
        walls, rejected_by_support = validate_wall_image_support(image, walls)
        rejected_walls.extend(rejected_by_support)

    primary_rooms = _dict_items(roboflow, "rooms")
    external_rooms = _dict_items(external, "rooms")
    local_rooms = _dict_items(local, "rooms")
    if roboflow_used:
        verifier_rooms = (
            merge_room_evidence(external_rooms, local_rooms)
            if external_rooms
            else local_rooms
        )
        rooms = merge_room_evidence(primary_rooms, verifier_rooms)
    elif external_rooms:
        rooms = merge_room_evidence(external_rooms, local_rooms)
    else:
        rooms = local_rooms
    rooms = _label_rooms_from_ocr(rooms, ocr_lines)

    opening_seed = (
        _dict_items(roboflow, "openings")
        + _dict_items(external, "openings")
        + _dict_items(local, "openings")
    )
    openings = assign_openings_to_walls(opening_seed, walls)

    base_confidence = _bounded_confidence(local.get("confidence"), 88)
    if external_used:
        base_confidence = max(base_confidence, _bounded_confidence(external.get("confidence"), 84))
    if roboflow_used:
        rf_confidences = [
            _bounded_confidence(item.get("confidence"), 92)
            for item in _dict_items(roboflow, "walls")
            + _dict_items(roboflow, "rooms")
            + _dict_items(roboflow, "openings")
            if _bounded_confidence(item.get("confidence"), 92) > 0
        ]
        if rf_confidences:
            base_confidence = max(base_confidence, min(90, int(sum(rf_confidences) / len(rf_confidences))))
    if source_authoritative:
        source_support = [float(item.get("image_support", 0.0)) for item in walls]
        if source_support:
            source_confidence = int(round(min(94.0, 76.0 + (sum(source_support) / len(source_support)) * 18.0)))
            base_confidence = max(base_confidence, source_confidence)

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

    warnings = (
        list(local.get("warnings") or [])
        + list(external.get("warnings") or [])
        + list(roboflow.get("warnings") or [])
    )
    if source_authoritative:
        warnings.append(
            f"Source-first wall vectorisation is authoritative ({source_meta.get('mode')}, {len(walls)} wall segment(s)); "
            "cloud/model wall coordinates were suppressed instead of merged."
        )
    if external_used:
        warnings.append(
            "Legacy cloud parsing was used only as secondary evidence for rooms/text/openings; it cannot override source wall coordinates."
        )
    if rejected_walls:
        warnings.append(
            f"HAI suppressed {len(rejected_walls)} non-authoritative or unsupported wall candidate(s) before review/3D."
        )
    if roboflow_used:
        warnings.append(
            f"Roboflow Universe supplied detector evidence for this page ({len(primary_rooms)} room region(s), "
            f"{len(_dict_items(roboflow, 'walls'))} wall candidate(s))."
        )
    if ocr_meta.get("adaptive_retry"):
        warnings.append("Adaptive OCR automatically zoomed and re-read weak regions to recover small dimensions and room labels.")
    if rotated_passes:
        warnings.append("Vertical dimension text was re-read in both 90-degree orientations before confidence calibration.")
    if precision_walls:
        warnings.append(f"Independent multiscale wall recovery supplied {len(precision_walls)} verification segment(s).")
    if curve_walls:
        warnings.append(f"Fallback curve recovery supplied {len(curve_walls)} diagonal/polyline candidate(s) for review.")
    orphaned = sum(1 for item in openings if not item.get("wallId"))
    if orphaned:
        warnings.append(f"{orphaned} detected opening(s) could not be attached to a verified wall and must stay reviewable.")
    if len(dimensions) < 2:
        warnings.append("Fewer than two plausible dimension readings were verified; metric scale confidence is intentionally capped.")
    if quality.get("wall_topology", 0) < 28 and walls:
        warnings.append("Wall topology is weak or fragmented; 3D should remain blocked until junctions are reviewed.")

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
    native_ocr_engine = cloud_ocr_engine_name()
    ocr_engine = "legacy-cloud-evidence" if native_ocr_engine == "unavailable" and external_ocr else native_ocr_engine
    ocr_meta["engine"] = ocr_engine
    ocr_meta["rotated_passes"] = rotated_passes
    ocr_meta["dimension_evidence"] = len(dimensions)
    ocr_meta["room_label_count"] = label_count
    ocr_meta["external_seed_lines"] = len(external_ocr)

    result = dict(external) if external_used else dict(local)
    result.update({
        "model_used": f"{model_used}+source-vector-v1+accuracy-v5+wall-support-v1+{ocr_engine}",
        "confidence": int(quality["overall_verified"]),
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "dimension_evidence": dimensions,
        "ocr_meta": ocr_meta,
        "source_vector_meta": source_meta,
        "wall_validation_meta": {
            "authority": "source-pixels" if source_authoritative else "hybrid-fallback",
            "kept": len(walls),
            "rejected": len(rejected_walls),
            "rejected_ids": [str(item.get("id") or "") for item in rejected_walls[:24]],
        },
        "external_evidence_meta": {
            "used": external_used,
            "model": external_model if external_used else "none",
            "rooms": len(external_rooms),
            "walls": len(external_walls),
            "ocr_lines": len(external_ocr),
        },
        "roboflow_meta": {
            "used": roboflow_used,
            "models": list(roboflow.get("models") or []),
            "rooms": len(primary_rooms),
            "walls": len(_dict_items(roboflow, "walls")),
            "openings": len(_dict_items(roboflow, "openings")),
        },
        "quality": quality,
        "warnings": list(dict.fromkeys(str(item) for item in warnings if str(item).strip())),
    })
    return result
