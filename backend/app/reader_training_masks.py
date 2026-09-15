from __future__ import annotations

import math
from typing import Any

import cv2
import numpy as np

CLASS_NAMES = ("floor", "wall", "door", "window")
CLASS_INDEX = {name: index for index, name in enumerate(CLASS_NAMES)}


def _pct(value: Any) -> float:
    try:
        value = float(value)
    except (TypeError, ValueError):
        value = 0.0
    return max(0.0, min(100.0, value))


def _point(point: dict[str, Any], width: int, height: int) -> tuple[int, int]:
    x = int(round(_pct(point.get("x")) * max(width - 1, 1) / 100.0))
    y = int(round(_pct(point.get("y")) * max(height - 1, 1) / 100.0))
    return x, y


def _wall_px(wall: dict[str, Any], reference: dict[str, Any], width: int, height: int) -> int:
    try:
        cm = float(wall.get("thickness_cm") or 0.0)
        width_m = float(reference.get("width_m") or 0.0)
        height_m = float(reference.get("height_m") or 0.0)
    except (TypeError, ValueError):
        cm = width_m = height_m = 0.0
    if cm > 0 and width_m > 0 and height_m > 0:
        px_per_m = ((width / width_m) + (height / height_m)) / 2.0
        return max(2, min(64, int(round(cm / 100.0 * px_per_m))))
    return max(2, min(32, int(round(min(width, height) * 0.006))))


def _opening_segment(opening: dict[str, Any], width: int, height: int) -> tuple[tuple[int, int], tuple[int, int]]:
    cx, cy = _pct(opening.get("x")), _pct(opening.get("y"))
    try:
        span = max(0.5, min(35.0, float(opening.get("width") or 2.0)))
        angle = math.radians(float(opening.get("rotation_deg") or 0.0))
    except (TypeError, ValueError):
        span, angle = 2.0, 0.0
    dx, dy = math.cos(angle) * span / 2.0, math.sin(angle) * span / 2.0
    return _point({"x": cx - dx, "y": cy - dy}, width, height), _point({"x": cx + dx, "y": cy + dy}, width, height)


def validate_reference(reference: dict[str, Any]) -> None:
    if int(reference.get("schema_version", 0)) != 1:
        raise ValueError("unsupported training reference schema")
    if reference.get("coordinate_space") != "percent-0-100":
        raise ValueError("coordinate_space must be percent-0-100")
    for key in ("walls", "rooms", "openings"):
        if not isinstance(reference.get(key), list):
            raise ValueError(f"{key} must be a list")


def render_mask(reference: dict[str, Any], image_shape: tuple[int, ...]) -> np.ndarray:
    validate_reference(reference)
    height, width = int(image_shape[0]), int(image_shape[1])
    if min(height, width) < 8:
        raise ValueError("training image is too small")
    mask = np.zeros((height, width), dtype=np.uint8)
    fallback = max(2, int(round(min(width, height) * 0.006)))
    wall_widths: dict[str, int] = {}
    for wall in reference["walls"]:
        if not isinstance(wall, dict) or not isinstance(wall.get("start"), dict) or not isinstance(wall.get("end"), dict):
            continue
        thickness = _wall_px(wall, reference, width, height)
        cv2.line(mask, _point(wall["start"], width, height), _point(wall["end"], width, height), CLASS_INDEX["wall"], thickness)
        wall_id = str(wall.get("id", ""))
        if wall_id:
            wall_widths[wall_id] = thickness
    for opening in reference["openings"]:
        if not isinstance(opening, dict):
            continue
        kind = str(opening.get("type", "")).lower()
        if kind not in {"door", "window"}:
            continue
        base = wall_widths.get(str(opening.get("wall_id", "")), fallback)
        a, b = _opening_segment(opening, width, height)
        cv2.line(mask, a, b, CLASS_INDEX[kind], max(3, int(round(base * 1.35))))
    return mask
