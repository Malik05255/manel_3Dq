from __future__ import annotations

import math
from typing import Any

import cv2
import numpy as np

from .parser import _blue_wall_mask


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


def _wall_extent(mask: np.ndarray) -> tuple[int, int, int, int] | None:
    ys, xs = np.where(mask > 0)
    if xs.size < 16 or ys.size < 16:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())


def _line_support(mask: np.ndarray, a: tuple[float, float], b: tuple[float, float], radius: int) -> float:
    x1, y1 = a
    x2, y2 = b
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


def _point_segment_distance(
    point: tuple[float, float],
    start: tuple[float, float],
    end: tuple[float, float],
) -> float:
    px, py = point
    ax, ay = start
    bx, by = end
    vx, vy = bx - ax, by - ay
    denom = vx * vx + vy * vy
    if denom <= 1e-9:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * vx + (py - ay) * vy) / denom
    t = max(0.0, min(1.0, t))
    qx, qy = ax + t * vx, ay + t * vy
    return math.hypot(px - qx, py - qy)


def _axis_detail_segments(
    mask: np.ndarray,
    *,
    horizontal_axis: bool,
    min_len: int,
    thickness: int,
    gap: int,
) -> tuple[list[dict[str, Any]], np.ndarray]:
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
            active = np.any(opened[y0:y1 + 1] > 0, axis=0).astype(np.uint8)[None, :] * 255
            active = cv2.morphologyEx(
                active,
                cv2.MORPH_CLOSE,
                cv2.getStructuringElement(cv2.MORPH_RECT, (gap, 1)),
            )[0] > 0
            for x0, x1 in _intervals(active.astype(np.int8), min_len=min_len):
                y = (y0 + y1) / 2.0
                support = _line_support(mask, (float(x0), y), (float(x1), y), radius=max(2, thickness))
                if support < 0.80:
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
            active = np.any(opened[:, x0:x1 + 1] > 0, axis=1).astype(np.uint8)[:, None] * 255
            active = cv2.morphologyEx(
                active,
                cv2.MORPH_CLOSE,
                cv2.getStructuringElement(cv2.MORPH_RECT, (1, gap)),
            )[:, 0] > 0
            for y0, y1 in _intervals(active.astype(np.int8), min_len=min_len):
                x = (x0 + x1) / 2.0
                support = _line_support(mask, (x, float(y0)), (x, float(y1)), radius=max(2, thickness))
                if support < 0.80:
                    continue
                segments.append({
                    "start_px": (x, float(y0)),
                    "end_px": (x, float(y1)),
                    "support": support,
                    "axis": "v",
                })
    return segments, opened


def _diagonal_detail_segments(
    mask: np.ndarray,
    *,
    footprint_min_side: int,
) -> tuple[list[dict[str, Any]], float]:
    """Recover only thick structural diagonal walls, never thin door graphics.

    Door leaves/arcs in CAD exports are often the same blue as walls. Running Hough on the
    raw colour mask therefore produced many short diagonal "walls" around every doorway.
    A distance-transform core keeps the thick centre of structural strokes and removes thin
    door symbols before line detection. Hough then runs only on that core.
    """
    binary = (mask > 0).astype(np.uint8)
    distance = cv2.distanceTransform(binary, cv2.DIST_L2, 5)
    core_radius = min(4.5, max(1.8, float(footprint_min_side) * 0.0035))
    core = (distance >= core_radius).astype(np.uint8) * 255
    core = cv2.morphologyEx(core, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8), iterations=1)

    min_len = max(14, int(round(footprint_min_side * 0.040)))
    lines = cv2.HoughLinesP(
        core,
        1,
        np.pi / 360.0,
        threshold=max(10, int(round(footprint_min_side * 0.014))),
        minLineLength=min_len,
        maxLineGap=max(3, int(round(footprint_min_side * 0.008))),
    )
    if lines is None:
        return [], core_radius

    candidates: list[tuple[float, float, float, float, float, float, float, float]] = []
    radius = max(2, int(round(footprint_min_side * 0.003)))
    for raw in lines[:400]:
        x1, y1, x2, y2 = map(float, raw[0])
        length = math.hypot(x2 - x1, y2 - y1)
        if length < min_len:
            continue
        angle = abs(math.degrees(math.atan2(y2 - y1, x2 - x1))) % 180.0
        acute = min(angle, 180.0 - angle)
        axis_delta = min(acute, abs(90.0 - acute))
        # Axis walls are already represented by the horizontal/vertical passes. Rejecting
        # near-axis Hough fragments also removes the common almost-vertical door-leaf artefact.
        if axis_delta < 20.0:
            continue
        support = _line_support(mask, (x1, y1), (x2, y2), radius=radius)
        core_support = _line_support(core, (x1, y1), (x2, y2), radius=1)
        if support < 0.90 or core_support < 0.72:
            continue
        candidates.append((x1, y1, x2, y2, length, angle, support, core_support))

    candidates.sort(key=lambda item: item[4], reverse=True)
    out: list[dict[str, Any]] = []
    for x1, y1, x2, y2, length, angle, support, core_support in candidates:
        midpoint = ((x1 + x2) / 2.0, (y1 + y2) / 2.0)
        duplicate = False
        for kept in out:
            ax, ay = kept["start_px"]
            bx, by = kept["end_px"]
            kangle = float(kept["angle"])
            delta = abs(angle - kangle) % 180.0
            delta = min(delta, 180.0 - delta)
            if delta > 7.0:
                continue
            # A shorter Hough fragment that lies on an already-kept structural stroke is
            # the same wall, even when their midpoints are not close.
            if _point_segment_distance(
                midpoint,
                (float(ax), float(ay)),
                (float(bx), float(by)),
            ) <= max(6.0, footprint_min_side * 0.015):
                duplicate = True
                break
        if duplicate:
            continue
        out.append({
            "start_px": (x1, y1),
            "end_px": (x2, y2),
            "support": support,
            "core_support": core_support,
            "angle": angle,
            "axis": "d",
        })
        if len(out) >= 12:
            break
    return out, core_radius


def vectorize_blue_detail_walls(image: np.ndarray) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Vectorise blue CAD walls while preserving short partitions and door gaps.

    Thresholds are derived from the detected building footprint instead of the entire
    page. Axis kernels are deliberately longer than normal wall thickness so vertical
    strokes cannot masquerade as horizontal walls (and vice versa). Only tiny raster
    breaks are healed; normal door openings remain split into separate wall segments.
    Diagonals must also survive a structural-thickness core so door leaves/arcs cannot
    inflate the wall graph.
    """
    if image.size == 0:
        return [], {"usable": False, "reason": "empty-image"}

    mask = _blue_wall_mask(image)
    coverage = float(np.count_nonzero(mask)) / max(float(mask.size), 1.0)
    extent = _wall_extent(mask)
    if coverage < 0.0012 or extent is None:
        return [], {"usable": False, "blue_coverage": round(coverage, 6)}

    x0, y0, x1, y1 = extent
    footprint_w = max(1, x1 - x0 + 1)
    footprint_h = max(1, y1 - y0 + 1)
    footprint_min = max(1, min(footprint_w, footprint_h))

    min_len = max(10, int(round(footprint_min * 0.030)))
    thickness = max(2, int(round(footprint_min * 0.003)))
    gap = max(2, int(round(footprint_min * 0.004)))

    horizontal, _ = _axis_detail_segments(
        mask,
        horizontal_axis=True,
        min_len=min_len,
        thickness=thickness,
        gap=gap,
    )
    vertical, _ = _axis_detail_segments(
        mask,
        horizontal_axis=False,
        min_len=min_len,
        thickness=thickness,
        gap=gap,
    )
    diagonal, core_radius = _diagonal_detail_segments(mask, footprint_min_side=footprint_min)

    raw = horizontal + vertical + diagonal
    h, w = image.shape[:2]
    walls: list[dict[str, Any]] = []
    for item in raw:
        x1p, y1p = item["start_px"]
        x2p, y2p = item["end_px"]
        support = float(item["support"])
        wall = {
            "id": f"blue-detail-{len(walls)}",
            "start": {"x": x1p / max(w, 1) * 100.0, "y": y1p / max(h, 1) * 100.0},
            "end": {"x": x2p / max(w, 1) * 100.0, "y": y2p / max(h, 1) * 100.0},
            "confidence": max(78, min(94, int(round(84 + support * 10)))),
            "kind": "blue-raster-detail-centerline",
            "axis": item["axis"],
            "image_support": round(support, 4),
        }
        if item["axis"] == "d":
            wall["structural_core_support"] = round(float(item.get("core_support", 0.0)), 4)
        walls.append(wall)
        if len(walls) >= 90:
            break

    axis_count = len(horizontal) + len(vertical)
    usable = len(walls) >= 5 and axis_count >= 5
    return walls, {
        "usable": usable,
        "blue_coverage": round(coverage, 6),
        "footprint_width": footprint_w,
        "footprint_height": footprint_h,
        "min_segment_px": min_len,
        "door_gap_heal_px": gap,
        "diagonal_core_radius_px": round(float(core_radius), 3),
        "horizontal_count": len(horizontal),
        "vertical_count": len(vertical),
        "diagonal_count": len(diagonal),
        "wall_count": len(walls),
    }
