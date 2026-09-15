from __future__ import annotations

import math
from statistics import mean
from typing import Any

import cv2
import numpy as np

from .cubicasa_model import CLASS_NAMES, MODEL_LICENSE, MODEL_NAME, _trim_process_memory, load_cubicasa_runtime, model_status
from .ocr_reader import cloud_ocr_engine_name, cloud_ocr_reader
from .parser import _decode_image, _extract_enclosed_rooms, _extract_openings, _label_rooms_from_ocr
from .parser_accuracy import dimension_evidence, precision_rotated_ocr, precision_wall_evidence, wall_topology_score
from .parser_quality import adaptive_ocr, assign_openings_to_walls, merge_ocr_lines, verification_scores


def _segment_support(mask: np.ndarray, a: tuple[float, float], b: tuple[float, float]) -> float:
    x1, y1 = a
    x2, y2 = b
    length = max(2, int(round(math.hypot(x2 - x1, y2 - y1))))
    xs = np.linspace(x1, x2, length)
    ys = np.linspace(y1, y2, length)
    xi = np.clip(xs.round().astype(np.int32), 0, mask.shape[1] - 1)
    yi = np.clip(ys.round().astype(np.int32), 0, mask.shape[0] - 1)
    return float(np.count_nonzero(mask[yi, xi])) / max(len(xi), 1)


def _axis_centerlines(mask: np.ndarray) -> list[dict[str, Any]]:
    """Turn thick semantic wall regions into one centerline per wall band."""
    h, w = mask.shape[:2]
    binary = ((mask > 0) * 255).astype(np.uint8)
    min_axis = max(12, int(round(min(h, w) * 0.028)))
    results: list[dict[str, Any]] = []

    specs = (
        ("horizontal", cv2.getStructuringElement(cv2.MORPH_RECT, (min_axis, 3)), True),
        ("vertical", cv2.getStructuringElement(cv2.MORPH_RECT, (3, min_axis)), False),
    )
    for kind, kernel, horizontal in specs:
        opened = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
        opened = cv2.morphologyEx(opened, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
        contours, _ = cv2.findContours(opened, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            x, y, rw, rh = cv2.boundingRect(contour)
            if horizontal:
                if rw < min_axis or rw < rh * 1.8:
                    continue
                y0 = y + rh / 2.0
                a, b = (float(x), y0), (float(x + rw - 1), y0)
            else:
                if rh < min_axis or rh < rw * 1.8:
                    continue
                x0 = x + rw / 2.0
                a, b = (x0, float(y)), (x0, float(y + rh - 1))
            support = _segment_support(binary, a, b)
            if support < 0.62:
                continue
            results.append({
                "id": f"seg-wall-{len(results)}",
                "start": {"x": a[0] / max(w, 1) * 100.0, "y": a[1] / max(h, 1) * 100.0},
                "end": {"x": b[0] / max(w, 1) * 100.0, "y": b[1] / max(h, 1) * 100.0},
                "confidence": min(96, int(round(86 + support * 10))),
                "kind": f"cubicasa-{kind}-centerline",
                "mask_support": round(support, 4),
            })

    lines = cv2.HoughLinesP(binary, 1, np.pi / 360.0, threshold=26, minLineLength=min_axis, maxLineGap=12)
    if lines is not None:
        for raw in lines[:300]:
            x1, y1, x2, y2 = map(float, raw[0])
            dx, dy = x2 - x1, y2 - y1
            length = math.hypot(dx, dy)
            if length < min_axis:
                continue
            angle = abs(math.degrees(math.atan2(dy, dx))) % 180.0
            axis_delta = min(angle, abs(90.0 - angle), abs(180.0 - angle))
            if axis_delta < 11.0:
                continue
            support = _segment_support(binary, (x1, y1), (x2, y2))
            if support < 0.78:
                continue
            results.append({
                "id": f"seg-wall-{len(results)}",
                "start": {"x": x1 / max(w, 1) * 100.0, "y": y1 / max(h, 1) * 100.0},
                "end": {"x": x2 / max(w, 1) * 100.0, "y": y2 / max(h, 1) * 100.0},
                "confidence": min(94, int(round(82 + support * 12))),
                "kind": "cubicasa-diagonal-centerline",
                "mask_support": round(support, 4),
            })

    return _merge_collinear(results)


def _merge_collinear(walls: list[dict[str, Any]]) -> list[dict[str, Any]]:
    kept: list[dict[str, Any]] = []
    for wall in sorted(walls, key=lambda item: int(item.get("confidence", 0)), reverse=True):
        a = wall["start"]
        b = wall["end"]
        x1, y1 = float(a["x"]), float(a["y"])
        x2, y2 = float(b["x"]), float(b["y"])
        length = math.hypot(x2 - x1, y2 - y1)
        angle = math.degrees(math.atan2(y2 - y1, x2 - x1)) % 180.0
        duplicate = False
        for other in kept:
            oa, ob = other["start"], other["end"]
            ox1, oy1 = float(oa["x"]), float(oa["y"])
            ox2, oy2 = float(ob["x"]), float(ob["y"])
            olength = math.hypot(ox2 - ox1, oy2 - oy1)
            oangle = math.degrees(math.atan2(oy2 - oy1, ox2 - ox1)) % 180.0
            delta = abs(angle - oangle) % 180.0
            delta = min(delta, 180.0 - delta)
            if delta > 4.0:
                continue
            mx, my = (x1 + x2) / 2.0, (y1 + y2) / 2.0
            omx, omy = (ox1 + ox2) / 2.0, (oy1 + oy2) / 2.0
            if math.hypot(mx - omx, my - omy) <= max(0.8, min(length, olength) * 0.10):
                ratio = min(length, olength) / max(length, olength, 1e-6)
                if ratio >= 0.55:
                    duplicate = True
                    break
        if not duplicate:
            kept.append(dict(wall))
        if len(kept) >= 180:
            break
    for index, item in enumerate(kept):
        item["id"] = f"seg-wall-{index}"
    return kept


def _precision_recovery_walls(image: np.ndarray) -> list[dict[str, Any]]:
    """Keep strong raster wall evidence while rejecting most thin dimension/leader lines."""
    recovered: list[dict[str, Any]] = []
    for wall in precision_wall_evidence(image, confidence=76):
        support = float(wall.get("mask_support", 0.0))
        band = float(wall.get("band_support", 0.0))
        if not ((band >= 0.34 and support >= 0.58) or (band >= 0.24 and support >= 0.94)):
            continue
        candidate = dict(wall)
        candidate["kind"] = "precision-raster-wall-recovery"
        candidate["confidence"] = min(int(candidate.get("confidence", 76)), 88)
        recovered.append(candidate)
    return _merge_collinear(recovered)


def _wall_mask_from_vectors(walls: list[dict[str, Any]], shape: tuple[int, ...]) -> np.ndarray:
    h, w = shape[:2]
    mask = np.zeros((h, w), dtype=np.uint8)
    thickness = max(3, int(round(min(h, w) * 0.006)))
    for wall in walls:
        a, b = wall.get("start") or {}, wall.get("end") or {}
        x1 = int(round(float(a.get("x", 0.0)) / 100.0 * w))
        y1 = int(round(float(a.get("y", 0.0)) / 100.0 * h))
        x2 = int(round(float(b.get("x", 0.0)) / 100.0 * w))
        y2 = int(round(float(b.get("y", 0.0)) / 100.0 * h))
        cv2.line(mask, (x1, y1), (x2, y2), 255, thickness=thickness, lineType=cv2.LINE_AA)
    return cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8), iterations=1)


def _wall_candidate_score(walls: list[dict[str, Any]]) -> float:
    if not walls:
        return 0.0
    topology = wall_topology_score(walls)
    confidence = mean(int(item.get("confidence", 0)) for item in walls)
    return topology * 3.0 + min(len(walls), 40) * 1.6 + confidence * 0.08


def _recover_wall_geometry(
    image: np.ndarray,
    semantic_walls: list[dict[str, Any]],
    semantic_wall_mask: np.ndarray,
) -> tuple[list[dict[str, Any]], np.ndarray, dict[str, Any]]:
    """Recover obvious plan linework only when semantic wall geometry is sparse/fragmented."""
    semantic_topology = wall_topology_score(semantic_walls)
    if len(semantic_walls) >= 4 and semantic_topology >= 45:
        return semantic_walls, semantic_wall_mask, {
            "used": False,
            "selected": "cubicasa-semantic",
            "semantic_walls": len(semantic_walls),
            "semantic_topology": semantic_topology,
            "precision_walls": 0,
            "precision_topology": 0,
        }

    precision = _precision_recovery_walls(image)
    combined = _merge_collinear(list(semantic_walls) + precision)
    precision_topology = wall_topology_score(precision)
    combined_topology = wall_topology_score(combined)

    candidates: list[tuple[str, list[dict[str, Any]], float]] = [
        ("cubicasa-semantic", semantic_walls, _wall_candidate_score(semantic_walls)),
    ]
    if len(precision) >= 3:
        candidates.append(("precision-raster", precision, _wall_candidate_score(precision)))
    if len(combined) >= 3:
        candidates.append(("cubicasa+precision-raster", combined, _wall_candidate_score(combined)))

    selected_name, selected_walls, selected_score = max(candidates, key=lambda item: item[2])
    semantic_score = _wall_candidate_score(semantic_walls)
    if selected_name != "cubicasa-semantic" and len(semantic_walls) >= 4 and selected_score < semantic_score + 8.0:
        selected_name, selected_walls = "cubicasa-semantic", semantic_walls

    used = selected_name != "cubicasa-semantic"
    room_mask = _wall_mask_from_vectors(selected_walls, image.shape) if used else semantic_wall_mask
    return selected_walls, room_mask, {
        "used": used,
        "selected": selected_name,
        "semantic_walls": len(semantic_walls),
        "semantic_topology": semantic_topology,
        "precision_walls": len(precision),
        "precision_topology": precision_topology,
        "combined_topology": combined_topology,
    }


def _metric_dimensions(lines: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for item in dimension_evidence(lines)[:80]:
        width = abs(float(item.get("right_pct", 0.0)) - float(item.get("left_pct", 0.0)))
        height = abs(float(item.get("bottom_pct", 0.0)) - float(item.get("top_pct", 0.0)))
        result.append({
            "value_m": float(item["value"]),
            "text": str(item.get("text", "")),
            "confidence": int(item.get("confidence", 0)),
            "axis": "horizontal" if width >= height else "vertical",
        })
    return result


def parse_floorplan(image_base64: str) -> dict[str, Any]:
    runtime = load_cubicasa_runtime()
    if runtime is None:
        raise ValueError("CubiCasa segmentation reader is not configured")

    image = _decode_image(image_base64)
    prediction = runtime.predict(image)
    wall_mask = ((prediction == 1) * 255).astype(np.uint8)
    door_mask = ((prediction == 2) * 255).astype(np.uint8)
    window_mask = ((prediction == 3) * 255).astype(np.uint8)

    semantic_walls = _axis_centerlines(wall_mask)
    walls, room_mask, geometry_recovery = _recover_wall_geometry(image, semantic_walls, wall_mask)
    room_confidence = 80 if geometry_recovery["used"] else 88
    rooms = _extract_enclosed_rooms(room_mask, confidence=room_confidence) if len(walls) >= 3 else []
    openings = _extract_openings(door_mask, "door", 90) + _extract_openings(window_mask, "window", 90)
    openings = assign_openings_to_walls(openings, walls)

    total = max(float(prediction.size), 1.0)
    coverage = {
        name: round(float(np.count_nonzero(prediction == index)) / total, 5)
        for index, name in enumerate(CLASS_NAMES)
    }
    del prediction, wall_mask, room_mask, door_mask, window_mask
    _trim_process_memory()

    reader = cloud_ocr_reader()
    ocr_lines, ocr_meta = adaptive_ocr(image, reader)
    ocr_lines, rotated_passes = precision_rotated_ocr(image, reader, ocr_lines)
    ocr_lines = merge_ocr_lines(ocr_lines)
    rooms = _label_rooms_from_ocr(rooms, ocr_lines)
    dimensions = _metric_dimensions(ocr_lines)

    if geometry_recovery["used"]:
        base_confidence = 82 if walls and rooms else (76 if walls else 0)
    else:
        base_confidence = 92 if walls and rooms else (86 if walls else 0)
    quality = verification_scores(
        model_used="cubicasa-unet-resnet34-v3",
        walls=walls,
        rooms=rooms,
        openings=openings,
        ocr_lines=ocr_lines,
        base_confidence=base_confidence,
    )
    topology = wall_topology_score(walls)
    quality["wall_topology"] = topology
    quality["dimension_evidence"] = len(dimensions)
    quality["geometry_source"] = geometry_recovery["selected"]
    quality["semantic_wall_count"] = geometry_recovery["semantic_walls"]
    quality["precision_wall_count"] = geometry_recovery["precision_walls"]
    if len(walls) < 4:
        quality["overall_verified"] = min(int(quality.get("overall_verified", 0)), 45)
    if topology < 25 and walls:
        quality["overall_verified"] = min(int(quality.get("overall_verified", 0)), 70)

    warnings: list[str] = []
    if geometry_recovery["used"]:
        warnings.append("CubiCasa semantic walls were sparse or fragmented; precision raster wall recovery was used and should be reviewed before 3D.")
    if not rooms:
        warnings.append("No reliable enclosed room regions were recovered; manual review is required.")
    if topology < 25 and walls:
        warnings.append("Wall topology is fragmented; 3D should remain blocked until reviewed.")
    if len(dimensions) < 2:
        warnings.append("Metric scale evidence is weak; verify one known dimension before relying on room sizes.")

    ocr_meta = dict(ocr_meta)
    ocr_meta["engine"] = cloud_ocr_engine_name()
    ocr_meta["rotated_passes"] = rotated_passes

    return {
        "model_used": "cubicasa-unet-resnet34-v3",
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
        "geometry_recovery": geometry_recovery,
        "ocr_meta": ocr_meta,
        "warnings": warnings,
    }
