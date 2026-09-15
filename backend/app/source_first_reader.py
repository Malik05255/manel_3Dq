from __future__ import annotations

import math
import re
from statistics import mean
from typing import Any

import cv2
import numpy as np

from .blue_detail_vectorizer import vectorize_blue_detail_walls
from .blue_wall_cleanup import clean_normalized_blue_walls
from .cubicasa_model import CLASS_NAMES, MODEL_LICENSE, MODEL_NAME, _trim_process_memory, load_cubicasa_runtime, model_status
from .ocr_reader import cloud_ocr_engine_name, cloud_ocr_reader
from .parser import _blue_wall_mask, _decode_image, _extract_enclosed_rooms, _extract_openings, _label_rooms_from_ocr
from .parser_accuracy import precision_rotated_ocr, wall_topology_score
from .parser_quality import adaptive_ocr, assign_openings_to_walls, merge_ocr_lines, verification_scores
from .parser_v3 import _axis_centerlines, _metric_dimensions, _recover_wall_geometry, _wall_mask_from_vectors
from .source_vectorizer import vectorize_source_walls

_AREA_HINT_RE = re.compile(r"(?:m\s*[²2]|م\s*[²2]|مساح(?:ة|ه)|area)", re.IGNORECASE)


def _mean_source_support(walls: list[dict[str, Any]]) -> float:
    values = [float(wall.get("image_support", 0.0)) for wall in walls if float(wall.get("image_support", 0.0)) > 0.0]
    return float(mean(values)) if values else 0.0


def _room_hint_count(lines: list[dict[str, Any]]) -> int:
    buckets: set[tuple[int, int]] = set()
    for line in lines:
        text = str(line.get("text") or "").strip()
        if not text or not _AREA_HINT_RE.search(text):
            continue
        cx = (float(line.get("left_pct", 0.0)) + float(line.get("right_pct", 0.0))) / 2.0
        cy = (float(line.get("top_pct", 0.0)) + float(line.get("bottom_pct", 0.0))) / 2.0
        buckets.add((round(cx / 3.5), round(cy / 3.5)))
    return len(buckets)


def _blue_extent(mask: np.ndarray) -> tuple[int, int, int, int] | None:
    ys, xs = np.where(mask > 0)
    if xs.size < 16 or ys.size < 16:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def _bridge_blue_room_gaps(mask: np.ndarray, gap_px: int) -> np.ndarray:
    """Close only door-sized axis gaps to obtain room regions.

    This mask is used for room topology only. Returned wall vectors retain the original
    door gaps, so room recovery cannot silently turn an opening into a wall.
    """
    gap_px = max(3, int(gap_px))
    horizontal = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (gap_px, 3)),
    )
    vertical = cv2.morphologyEx(
        mask,
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (3, gap_px)),
    )
    return cv2.bitwise_or(mask, cv2.bitwise_or(horizontal, vertical))


def _source_rooms(
    image: np.ndarray,
    walls: list[dict[str, Any]],
    ocr_lines: list[dict[str, Any]],
    *,
    blue_source: bool,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Recover rooms from source geometry without changing the authoritative walls."""
    if len(walls) < 3:
        return [], {"used": False, "reason": "insufficient-walls"}

    if not blue_source:
        mask = _wall_mask_from_vectors(walls, image.shape)
        rooms = _extract_enclosed_rooms(mask, confidence=84)
        return _label_rooms_from_ocr(rooms, ocr_lines), {
            "used": bool(rooms),
            "mode": "source-vector-mask",
            "candidate_count": 1,
        }

    blue = _blue_wall_mask(image)
    extent = _blue_extent(blue)
    if extent is None:
        mask = _wall_mask_from_vectors(walls, image.shape)
        rooms = _extract_enclosed_rooms(mask, confidence=82)
        return _label_rooms_from_ocr(rooms, ocr_lines), {
            "used": bool(rooms),
            "mode": "source-vector-mask-fallback",
            "candidate_count": 1,
        }

    x0, y0, x1, y1 = extent
    footprint_min = max(1, min(x1 - x0 + 1, y1 - y0 + 1))
    target = _room_hint_count(ocr_lines)
    if target <= 0:
        target = max(3, min(24, int(round(len(walls) / 3.5))))

    candidates: list[tuple[tuple[float, float, float], list[dict[str, Any]], int]] = []
    # Door openings in normal residential plans are typically a few percent of the
    # footprint's short side. Test a bounded set and choose the least-invasive candidate
    # closest to OCR/structural room evidence instead of hard-coding one aggressive close.
    for ratio in (0.028, 0.034, 0.040, 0.046, 0.052):
        gap_px = max(5, int(round(footprint_min * ratio)))
        bridged = _bridge_blue_room_gaps(blue, gap_px)
        rooms = _extract_enclosed_rooms(bridged, confidence=86)
        del bridged
        if not rooms or len(rooms) > 28:
            continue
        # Prefer count agreement, then smaller gap, then a slightly richer room graph.
        score = (abs(float(len(rooms) - target)), ratio, -float(len(rooms)))
        candidates.append((score, rooms, gap_px))

    if not candidates:
        mask = _wall_mask_from_vectors(walls, image.shape)
        rooms = _extract_enclosed_rooms(mask, confidence=80)
        return _label_rooms_from_ocr(rooms, ocr_lines), {
            "used": bool(rooms),
            "mode": "source-vector-mask-fallback",
            "candidate_count": 0,
            "target": target,
        }

    _, rooms, gap_px = min(candidates, key=lambda item: item[0])
    return _label_rooms_from_ocr(rooms, ocr_lines), {
        "used": True,
        "mode": "blue-source-adaptive-door-gap-topology",
        "candidate_count": len(candidates),
        "target": target,
        "gap_px": gap_px,
        "room_count": len(rooms),
    }


def _select_source_geometry(
    image: np.ndarray,
    semantic_walls: list[dict[str, Any]],
    semantic_wall_mask: np.ndarray,
) -> tuple[list[dict[str, Any]], np.ndarray, dict[str, Any]]:
    """Make source pixels the geometry authority whenever they are strong enough.

    CubiCasa still supplies semantic classes/openings, but its wall coordinates are not
    allowed to replace a coherent source-derived graph. This prevents the old regression
    where a merely non-empty semantic result overrode the actual CAD wall positions.
    """
    coarse, coarse_meta = vectorize_source_walls(image)
    source_mode = str(coarse_meta.get("mode") or "unavailable")
    coarse_topology = wall_topology_score(coarse)
    coarse_support = _mean_source_support(coarse)

    selected = coarse
    selected_kind = "source-coarse"
    detail_meta: dict[str, Any] = {}
    cleanup_meta: dict[str, Any] = {}

    if source_mode == "blue-source":
        detailed, detail_meta = vectorize_blue_detail_walls(image)
        if bool(detail_meta.get("usable")):
            cleaned, cleanup_meta = clean_normalized_blue_walls(_blue_wall_mask(image), detailed)
            if len(cleaned) >= 5:
                detailed = cleaned
            detail_topology = wall_topology_score(detailed)
            detail_support = _mean_source_support(detailed)
            count_ok = len(detailed) <= max(90, int(max(len(coarse), 8) * 3.5))
            topology_ok = detail_topology + 12 >= coarse_topology
            support_ok = detail_support >= 0.78
            if len(detailed) >= 5 and count_ok and topology_ok and support_ok:
                selected = detailed
                selected_kind = "source-detail"

    selected_topology = wall_topology_score(selected)
    selected_support = _mean_source_support(selected)
    pixel_alignment = int(round(max(0.0, min(1.0, selected_support)) * 100.0))

    # Do not use the requested 95% as a fabricated confidence. This gate means that the
    # graph has high direct pixel support; end-to-end accuracy is still measured by tests.
    source_authoritative = bool(coarse_meta.get("authoritative")) and len(selected) >= 5
    if source_mode == "blue-source":
        source_authoritative = source_authoritative and pixel_alignment >= 82
    else:
        source_authoritative = source_authoritative and pixel_alignment >= 72

    if source_authoritative:
        return selected, _wall_mask_from_vectors(selected, image.shape), {
            "used": True,
            "selected": f"source-first-{source_mode}-{selected_kind}",
            "source_authoritative": True,
            "source_mode": source_mode,
            "source_wall_count": len(selected),
            "source_topology": selected_topology,
            "source_pixel_alignment": pixel_alignment,
            "semantic_walls": len(semantic_walls),
            "semantic_topology": wall_topology_score(semantic_walls),
            "source_vectorizer": coarse_meta,
            "source_detail": detail_meta,
            "source_cleanup": cleanup_meta,
        }

    # Weak/monochrome source evidence falls back to the already-tested semantic recovery.
    fallback_walls, fallback_mask, fallback_meta = _recover_wall_geometry(
        image,
        semantic_walls,
        semantic_wall_mask,
    )
    meta = dict(fallback_meta)
    meta.update({
        "source_authoritative": False,
        "source_mode": source_mode,
        "source_wall_count": len(selected),
        "source_topology": selected_topology,
        "source_pixel_alignment": pixel_alignment,
        "source_vectorizer": coarse_meta,
        "source_detail": detail_meta,
        "source_cleanup": cleanup_meta,
    })
    return fallback_walls, fallback_mask, meta


def parse_floorplan(image_base64: str) -> dict[str, Any]:
    runtime = load_cubicasa_runtime()
    if runtime is None:
        raise ValueError("CubiCasa segmentation reader is not configured")

    image = _decode_image(image_base64)
    prediction = runtime.predict(image)
    wall_mask = ((prediction == 1) * 255).astype(np.uint8)
    door_mask = ((prediction == 2) * 255).astype(np.uint8)
    window_mask = ((prediction == 3) * 255).astype(np.uint8)

    # OCR runs before room extraction so room-count/name evidence can guide only the
    # topology mask. It never changes source wall coordinates.
    reader = cloud_ocr_reader()
    ocr_lines, ocr_meta = adaptive_ocr(image, reader)
    ocr_lines, rotated_passes = precision_rotated_ocr(image, reader, ocr_lines)
    ocr_lines = merge_ocr_lines(ocr_lines)

    semantic_walls = _axis_centerlines(wall_mask)
    walls, room_mask, geometry = _select_source_geometry(image, semantic_walls, wall_mask)
    source_authoritative = bool(geometry.get("source_authoritative"))
    source_mode = str(geometry.get("source_mode") or "")

    if source_authoritative:
        rooms, room_recovery = _source_rooms(
            image,
            walls,
            ocr_lines,
            blue_source=source_mode == "blue-source",
        )
    else:
        room_confidence = 80 if geometry.get("used") else 88
        rooms = _extract_enclosed_rooms(room_mask, confidence=room_confidence) if len(walls) >= 3 else []
        rooms = _label_rooms_from_ocr(rooms, ocr_lines)
        room_recovery = {"used": False, "mode": "semantic-fallback"}

    openings = _extract_openings(door_mask, "door", 90) + _extract_openings(window_mask, "window", 90)
    openings = assign_openings_to_walls(openings, walls)
    dimensions = _metric_dimensions(ocr_lines)

    total = max(float(prediction.size), 1.0)
    coverage = {
        name: round(float(np.count_nonzero(prediction == index)) / total, 5)
        for index, name in enumerate(CLASS_NAMES)
    }
    del prediction, wall_mask, room_mask, door_mask, window_mask
    _trim_process_memory()

    topology = wall_topology_score(walls)
    source_alignment = int(geometry.get("source_pixel_alignment") or 0)
    base_confidence = 94 if source_authoritative and rooms else (88 if walls and rooms else 76 if walls else 0)
    quality = verification_scores(
        model_used="hai-source-first-v4",
        walls=walls,
        rooms=rooms,
        openings=openings,
        ocr_lines=ocr_lines,
        base_confidence=base_confidence,
    )
    quality["wall_topology"] = topology
    quality["dimension_evidence"] = len(dimensions)
    quality["geometry_source"] = str(geometry.get("selected") or "unknown")
    quality["source_authoritative"] = 1 if source_authoritative else 0
    quality["source_alignment"] = source_alignment
    quality["semantic_wall_count"] = len(semantic_walls)
    quality["source_wall_count"] = int(geometry.get("source_wall_count") or 0)

    # A 95 target is a release/benchmark gate, not a number painted onto each result.
    # Expose whether this page's direct source-pixel support reaches that threshold.
    quality["source_95_gate"] = 1 if source_authoritative and source_alignment >= 95 else 0

    warnings: list[str] = []
    if source_authoritative:
        warnings.append("Source pixels are the geometry authority; AI segmentation is used for semantic/opening evidence and cannot replace wall coordinates.")
        if source_alignment < 95:
            warnings.append("Direct wall-to-source pixel support is below the 95% target; review the overlay before 3D.")
    else:
        warnings.append("Source-first geometry was not strong enough to become authoritative; semantic fallback was used and requires review.")
    if not rooms:
        warnings.append("No reliable enclosed room regions were recovered; manual review is required.")
    if topology < 25 and walls:
        warnings.append("Wall topology is fragmented; 3D should remain blocked until reviewed.")
    if len(dimensions) < 2:
        warnings.append("Metric scale evidence is weak; verify one known dimension before relying on room sizes.")

    ocr_meta = dict(ocr_meta)
    ocr_meta["engine"] = cloud_ocr_engine_name()
    ocr_meta["rotated_passes"] = rotated_passes

    geometry = dict(geometry)
    geometry["room_recovery"] = room_recovery

    return {
        "model_used": "hai-source-first-v4+cubicasa-openings+ocr",
        "model": {
            "name": MODEL_NAME,
            "license": MODEL_LICENSE,
            "classes": list(CLASS_NAMES),
            "runtime": model_status(),
        },
        "confidence": int(quality.get("overall_verified", 0)),
        "walls": walls,
        "rooms": rooms,
        "openings": openings,
        "ocr_lines": ocr_lines,
        "metric": {"dimensions": dimensions},
        "quality": quality,
        "class_coverage": coverage,
        "geometry_recovery": geometry,
        "ocr_meta": ocr_meta,
        "warnings": warnings,
    }
