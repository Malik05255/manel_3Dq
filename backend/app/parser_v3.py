from __future__ import annotations

import math
from typing import Any

import cv2
import numpy as np

from .cubicasa_model import CLASS_NAMES, MODEL_LICENSE, MODEL_NAME, load_cubicasa_runtime, model_status
from .ocr_reader import cloud_ocr_engine_name, cloud_ocr_reader
from .parser import _decode_image, _extract_enclosed_rooms, _extract_openings, _label_rooms_from_ocr
from .parser_accuracy import dimension_evidence, precision_rotated_ocr, wall_topology_score
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
    """Turn thick semantic wall regions into one centerline per wall band.

    Hough on a thick wall mask commonly returns both visible edges of the same wall. This
    extractor first isolates long horizontal/vertical wall bands and uses each band's centre,
    which is substantially closer to the topology needed by the Android editor and 3D builder.
    """
    h, w = mask.shape[:2]
    binary = ((mask > 0) * 255).astype(np.uint8)
    min_axis = max(12, int(round(min(h, w) * 0.028)))
    results: list[dict[str, Any]] = []

    specs = (
        ("horizontal", cv2.getStructuringElement(cv2.MORPH_RECT, (min_axis, 3))), True),
        ("vertical", cv2.getStructuringElement(cv2.MORPH_RECT, (3, min_axis))), False),
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

    # Keep true diagonals from the semantic wall class. Axis-aligned candidates are excluded
    # here because they have already been represented by a single centreline above.
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
    """Merge obvious duplicate/overlapping semantic centre lines without inventing geometry."""
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

    walls = _axis_centerlines(wall_mask)
    rooms = _extract_enclosed_rooms(wall_mask, confidence=88) if len(walls) >= 3 else []
    openings = _extract_openings(door_mask, "door", 90) + _extract_openings(window_mask, "window", 90)
    openings = assign_openings_to_walls(openings, walls)

    reader = cloud_ocr_reader()
    ocr_lines, ocr_meta = adaptive_ocr(image, reader)
    ocr_lines, rotated_passes = precision_rotated_ocr(image, reader, ocr_lines)
    ocr_lines = merge_ocr_lines(ocr_lines)
    rooms = _label_rooms_from_ocr(rooms, ocr_lines)
    dimensions = _metric_dimensions(ocr_lines)

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
    if len(walls) < 4:
        quality["overall_verified"] = min(int(quality.get("overall_verified", 0)), 45)
    if topology < 25 and walls:
        quality["overall_verified"] = min(int(quality.get("overall_verified", 0)), 70)

    total = max(float(prediction.size), 1.0)
    coverage = {
        name: round(float(np.count_nonzero(prediction == index)) / total, 5)
        for index, name in enumerate(CLASS_NAMES)
    }
    warnings: list[str] = []
    if not rooms:
        warnings.append("CubiCasa detected wall structure but no reliable enclosed room regions; manual review is required.")
    if topology < 25 and walls:
        warnings.append("Segmentation wall topology is fragmented; 3D should remain blocked until reviewed.")
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
        "ocr_meta": ocr_meta,
        "warnings": warnings,
    }
