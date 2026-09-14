from __future__ import annotations

import math
from typing import Any

import cv2
import numpy as np

from .parser_quality import architectural_wall_mask


def _segment_pixels(wall: dict[str, Any], width: int, height: int) -> tuple[float, float, float, float]:
    start = wall.get("start") or {}
    end = wall.get("end") or {}
    x1 = float(start.get("x", 0.0)) / 100.0 * width
    y1 = float(start.get("y", 0.0)) / 100.0 * height
    x2 = float(end.get("x", 0.0)) / 100.0 * width
    y2 = float(end.get("y", 0.0)) / 100.0 * height
    return x1, y1, x2, y2


def _acute_angle_deg(x1: float, y1: float, x2: float, y2: float) -> float:
    angle = abs(math.degrees(math.atan2(y2 - y1, x2 - x1))) % 180.0
    if angle > 90.0:
        angle = 180.0 - angle
    return angle


def _support_ratio(mask: np.ndarray, wall: dict[str, Any]) -> float:
    height, width = mask.shape[:2]
    x1, y1, x2, y2 = _segment_pixels(wall, width, height)
    length_px = math.hypot(x2 - x1, y2 - y1)
    if length_px < 2.0:
        return 0.0

    samples = max(12, min(220, int(round(length_px / 3.0))))
    radius = max(2, int(round(min(width, height) * 0.0035)))
    supported = 0
    for index in range(samples + 1):
        t = index / samples
        x = int(round(x1 + (x2 - x1) * t))
        y = int(round(y1 + (y2 - y1) * t))
        x0, x3 = max(0, x - radius), min(width, x + radius + 1)
        y0, y3 = max(0, y - radius), min(height, y + radius + 1)
        if x0 < x3 and y0 < y3 and np.any(mask[y0:y3, x0:x3] > 0):
            supported += 1
    return supported / max(samples + 1, 1)


def validate_wall_image_support(
    image: np.ndarray,
    walls: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Reject long phantom segments that are not supported by source-image pixels.

    The main target is the failure mode where a malformed room polygon creates a
    long diagonal line across several real rooms. Legitimate diagonal walls are
    retained when the architectural mask contains matching pixels along them.
    """
    if image.size == 0 or not walls:
        return walls, []

    mask = architectural_wall_mask(image)
    height, width = mask.shape[:2]
    diagonal_length_scale = max(float(width), float(height), 1.0)
    kept: list[dict[str, Any]] = []
    rejected: list[dict[str, Any]] = []

    for raw in walls:
        wall = dict(raw)
        x1, y1, x2, y2 = _segment_pixels(wall, width, height)
        length_ratio = math.hypot(x2 - x1, y2 - y1) / diagonal_length_scale
        angle = _acute_angle_deg(x1, y1, x2, y2)
        support = _support_ratio(mask, wall)
        wall["image_support"] = round(support, 3)

        is_diagonal = 12.0 < angle < 78.0
        is_long = length_ratio >= 0.16
        unsupported_diagonal = is_diagonal and is_long and support < 0.34
        extreme_unsupported = length_ratio >= 0.32 and support < 0.20

        if unsupported_diagonal or extreme_unsupported:
            wall["rejection_reason"] = "unsupported-long-segment"
            rejected.append(wall)
            continue

        current_confidence = int(wall.get("confidence", 0) or 0)
        if support < 0.30:
            wall["confidence"] = min(current_confidence, 58) if current_confidence else 45
        elif support >= 0.70 and current_confidence:
            wall["confidence"] = min(96, current_confidence + 3)
        kept.append(wall)

    return kept, rejected
