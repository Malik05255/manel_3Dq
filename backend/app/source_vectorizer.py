from __future__ import annotations

import math
from typing import Any

import cv2
import numpy as np

from .parser_quality import architectural_wall_mask


def _intervals(active: np.ndarray, min_len: int = 1) -> list[tuple[int, int]]:
    values = np.asarray(active, dtype=np.int8)
    changes = np.diff(np.pad(values, (1, 1)))
    starts = np.where(changes == 1)[0]
    ends = np.where(changes == -1)[0] - 1
    return [
        (int(start), int(end))
        for start, end in zip(starts, ends)
        if int(end) - int(start) + 1 >= min_len
    ]


def _blue_structural_mask(image: np.ndarray) -> tuple[np.ndarray, float, int]:
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    hsv_blue = cv2.inRange(
        hsv,
        np.array([82, 34, 28], dtype=np.uint8),
        np.array([155, 255, 255], dtype=np.uint8),
    )
    b, g, r = cv2.split(image)
    dominant = (
        (b.astype(np.int16) >= r.astype(np.int16) + 14)
        & (b.astype(np.int16) >= g.astype(np.int16) + 4)
        & (b >= 55)
    ).astype(np.uint8) * 255
    blue = cv2.bitwise_or(hsv_blue, dominant)
    blue = cv2.morphologyEx(blue, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8), iterations=1)
    blue = cv2.morphologyEx(blue, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8), iterations=1)

    h, w = blue.shape[:2]
    min_side = max(1, min(h, w))
    axis_len = max(18, int(round(min_side * 0.045)))
    thickness = max(2, int(round(min_side * 0.003)))
    horizontal = cv2.morphologyEx(
        blue,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (axis_len, thickness)),
    )
    vertical = cv2.morphologyEx(
        blue,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (thickness, axis_len)),
    )
    structural_pixels = int(np.count_nonzero(cv2.bitwise_or(horizontal, vertical)))
    coverage = float(np.count_nonzero(blue)) / max(float(blue.size), 1.0)
    return blue, coverage, structural_pixels


def _structural_mask(image: np.ndarray) -> tuple[np.ndarray, str, dict[str, Any]]:
    blue, coverage, structural_pixels = _blue_structural_mask(image)
    total = max(int(blue.size), 1)
    # CAD plans with blue/purple wall strokes are common in the current HAI flow.
    # When enough long coloured structure is present, use it as the geometry authority.
    # This deliberately ignores black dimension leaders, OCR text and stale overlays.
    if coverage >= 0.003 and structural_pixels >= max(160, int(total * 0.0012)):
        return blue, "blue-source", {
            "blue_coverage": round(coverage, 5),
            "blue_structural_pixels": structural_pixels,
        }

    mask = architectural_wall_mask(image)
    min_side = max(1, min(mask.shape[:2]))
    # Remove single-pixel annotation leaders before vectorisation while preserving
    # ordinary architectural strokes.
    thickness = max(2, int(round(min_side * 0.0025)))
    structural = cv2.morphologyEx(
        mask,
        cv2.MORPH_OPEN,
        cv2.getStructuringElement(cv2.MORPH_RECT, (thickness, thickness)),
    )
    return structural, "monochrome-source", {
        "blue_coverage": round(coverage, 5),
        "blue_structural_pixels": structural_pixels,
    }


def _line_support(mask: np.ndarray, x1: float, y1: float, x2: float, y2: float, radius: int = 2) -> float:
    length = max(2, int(round(math.hypot(x2 - x1, y2 - y1))))
    xs = np.linspace(x1, x2, length)
    ys = np.linspace(y1, y2, length)
    dx, dy = x2 - x1, y2 - y1
    norm = max(math.hypot(dx, dy), 1e-6)
    nx, ny = -dy / norm, dx / norm
    supported = np.zeros(length, dtype=bool)
    for offset in range(-radius, radius + 1):
        xi = np.clip((xs + nx * offset).round().astype(np.int32), 0, mask.shape[1] - 1)
        yi = np.clip((ys + ny * offset).round().astype(np.int32), 0, mask.shape[0] - 1)
        supported |= mask[yi, xi] > 0
    return float(np.count_nonzero(supported)) / max(length, 1)


def _axis_segments(mask: np.ndarray, *, horizontal_axis: bool, mode: str) -> tuple[list[dict[str, Any]], np.ndarray]:
    h, w = mask.shape[:2]
    min_side = max(1, min(h, w))
    min_len = max(18, int(round(min_side * 0.045)))
    thickness = max(2, int(round(min_side * 0.003)))
    gap = max(4, int(round(min_side * 0.018)))

    if horizontal_axis:
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (min_len, thickness))
    else:
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (thickness, min_len))
    opened = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)

    segments: list[dict[str, Any]] = []
    if horizontal_axis:
        counts = np.count_nonzero(opened, axis=1)
        bands = _intervals((counts >= min_len).astype(np.int8))
        for y0, y1 in bands:
            line = np.any(opened[y0:y1 + 1] > 0, axis=0).astype(np.uint8)[None, :] * 255
            line = cv2.morphologyEx(
                line,
                cv2.MORPH_CLOSE,
                cv2.getStructuringElement(cv2.MORPH_RECT, (gap, 1)),
            )[0] > 0
            for x0, x1 in _intervals(line.astype(np.int8), min_len=min_len):
                y = (y0 + y1) / 2.0
                support = _line_support(mask, float(x0), y, float(x1), y, radius=max(2, thickness))
                if support < (0.72 if mode == "blue-source" else 0.64):
                    continue
                segments.append({
                    "start_px": (float(x0), y),
                    "end_px": (float(x1), y),
                    "support": support,
                    "axis": "h",
                })
    else:
        counts = np.count_nonzero(opened, axis=0)
        bands = _intervals((counts >= min_len).astype(np.int8))
        for x0, x1 in bands:
            line = np.any(opened[:, x0:x1 + 1] > 0, axis=1).astype(np.uint8)[:, None] * 255
            line = cv2.morphologyEx(
                line,
                cv2.MORPH_CLOSE,
                cv2.getStructuringElement(cv2.MORPH_RECT, (1, gap)),
            )[:, 0] > 0
            for y0, y1 in _intervals(line.astype(np.int8), min_len=min_len):
                x = (x0 + x1) / 2.0
                support = _line_support(mask, x, float(y0), x, float(y1), radius=max(2, thickness))
                if support < (0.72 if mode == "blue-source" else 0.64):
                    continue
                segments.append({
                    "start_px": (x, float(y0)),
                    "end_px": (x, float(y1)),
                    "support": support,
                    "axis": "v",
                })
    return segments, opened


def _diagonal_segments(mask: np.ndarray, axis_mask: np.ndarray, mode: str) -> list[dict[str, Any]]:
    h, w = mask.shape[:2]
    min_side = max(1, min(h, w))
    residual = cv2.bitwise_and(mask, cv2.bitwise_not(cv2.dilate(axis_mask, np.ones((5, 5), np.uint8), iterations=1)))
    residual = cv2.morphologyEx(residual, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8), iterations=1)
    min_len = max(18, int(round(min_side * 0.035)))
    lines = cv2.HoughLinesP(
        residual,
        1,
        np.pi / 360.0,
        threshold=max(14, int(round(min_side * 0.018))),
        minLineLength=min_len,
        maxLineGap=max(5, int(round(min_side * 0.012))),
    )
    if lines is None:
        return []

    out: list[dict[str, Any]] = []
    scale = max(float(h), float(w), 1.0)
    for raw in lines[:240]:
        x1, y1, x2, y2 = map(float, raw[0])
        length = math.hypot(x2 - x1, y2 - y1)
        if length < min_len or length / scale > 0.30:
            continue
        angle = abs(math.degrees(math.atan2(y2 - y1, x2 - x1))) % 180.0
        acute = min(angle, 180.0 - angle)
        if acute <= 10.0 or abs(90.0 - acute) <= 10.0:
            continue
        support = _line_support(mask, x1, y1, x2, y2, radius=max(2, int(round(min_side * 0.003))))
        threshold = 0.86 if mode == "blue-source" else 0.90
        if support < threshold:
            continue

        duplicate = False
        for kept in out:
            ax, ay = kept["start_px"]
            bx, by = kept["end_px"]
            direct = math.hypot(ax - x1, ay - y1) + math.hypot(bx - x2, by - y2)
            reverse = math.hypot(ax - x2, ay - y2) + math.hypot(bx - x1, by - y1)
            if min(direct, reverse) <= max(10.0, min_side * 0.025):
                duplicate = True
                break
        if duplicate:
            continue
        out.append({
            "start_px": (x1, y1),
            "end_px": (x2, y2),
            "support": support,
            "axis": "d",
        })
        if len(out) >= 24:
            break
    return out


def vectorize_source_walls(image: np.ndarray) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Vectorise structural wall strokes directly from source pixels.

    This path is intentionally source-first: cloud/legacy wall coordinates are not
    needed to build the final wall graph when the original drawing contains enough
    structural evidence. Coloured CAD walls are isolated from text/dimensions first,
    then horizontal/vertical wall bands are reduced to one centreline each. Genuine
    diagonal walls are recovered only from residual source pixels with very high support.
    """
    if image.size == 0:
        return [], {"mode": "unavailable", "authoritative": False}

    mask, mode, meta = _structural_mask(image)
    horizontal, horizontal_mask = _axis_segments(mask, horizontal_axis=True, mode=mode)
    vertical, vertical_mask = _axis_segments(mask, horizontal_axis=False, mode=mode)
    axis_mask = cv2.bitwise_or(horizontal_mask, vertical_mask)
    diagonal = _diagonal_segments(mask, axis_mask, mode)
    raw = horizontal + vertical + diagonal

    h, w = image.shape[:2]
    base_confidence = 92 if mode == "blue-source" else 84
    walls: list[dict[str, Any]] = []
    for item in raw:
        x1, y1 = item["start_px"]
        x2, y2 = item["end_px"]
        support = float(item["support"])
        confidence = int(round(base_confidence + max(0.0, min(5.0, (support - 0.70) * 16.0))))
        walls.append({
            "id": f"source-wall-{len(walls)}",
            "start": {"x": x1 / max(w, 1) * 100.0, "y": y1 / max(h, 1) * 100.0},
            "end": {"x": x2 / max(w, 1) * 100.0, "y": y2 / max(h, 1) * 100.0},
            "confidence": max(60, min(97, confidence)),
            "kind": "source-pixel-wall",
            "source_mode": mode,
            "image_support": round(support, 4),
            "axis": item["axis"],
        })

    axis_count = len(horizontal) + len(vertical)
    # Five axis-aligned centrelines already describe a closed rectangle plus one
    # interior divider. Requiring six made valid small plans fall back to the old
    # multi-source merge path unnecessarily.
    authoritative = len(walls) >= 5 and axis_count >= 5
    meta.update({
        "mode": mode,
        "authoritative": authoritative,
        "wall_count": len(walls),
        "horizontal_count": len(horizontal),
        "vertical_count": len(vertical),
        "diagonal_count": len(diagonal),
    })
    return walls, meta
