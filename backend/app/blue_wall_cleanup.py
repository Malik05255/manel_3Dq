from __future__ import annotations

import math
from typing import Any

import cv2
import numpy as np


def _line_support(
    mask: np.ndarray,
    start: tuple[float, float],
    end: tuple[float, float],
    *,
    radius: int,
) -> float:
    x1, y1 = start
    x2, y2 = end
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


def _structural_core(mask: np.ndarray, footprint_min_side: int) -> tuple[np.ndarray, float]:
    binary = (mask > 0).astype(np.uint8)
    distance = cv2.distanceTransform(binary, cv2.DIST_L2, 5)
    core_radius = min(4.5, max(1.8, float(footprint_min_side) * 0.0035))
    core = (distance >= core_radius).astype(np.uint8) * 255
    core = cv2.morphologyEx(core, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8), iterations=1)
    return core, core_radius


def _merge_axis_segments(
    segments: list[dict[str, Any]],
    *,
    footprint_min_side: int,
) -> list[dict[str, Any]]:
    if not segments:
        return []

    coord_tol = max(2.5, float(footprint_min_side) * 0.0045)
    tiny_gap = max(2.0, float(footprint_min_side) * 0.0030)
    merged: list[dict[str, Any]] = []

    for axis in ("h", "v"):
        axis_segments = [dict(item) for item in segments if item.get("axis") == axis]
        axis_segments.sort(
            key=lambda item: (
                (item["start_px"][1] + item["end_px"][1]) / 2.0
                if axis == "h"
                else (item["start_px"][0] + item["end_px"][0]) / 2.0,
                min(
                    item["start_px"][0] if axis == "h" else item["start_px"][1],
                    item["end_px"][0] if axis == "h" else item["end_px"][1],
                ),
            )
        )

        for item in axis_segments:
            sx, sy = map(float, item["start_px"])
            ex, ey = map(float, item["end_px"])
            coord = (sy + ey) / 2.0 if axis == "h" else (sx + ex) / 2.0
            lo = min(sx, ex) if axis == "h" else min(sy, ey)
            hi = max(sx, ex) if axis == "h" else max(sy, ey)

            candidate = None
            for kept in reversed(merged):
                if kept.get("axis") != axis:
                    continue
                ksx, ksy = map(float, kept["start_px"])
                kex, key = map(float, kept["end_px"])
                kcoord = (ksy + key) / 2.0 if axis == "h" else (ksx + kex) / 2.0
                if abs(coord - kcoord) > coord_tol:
                    continue
                klo = min(ksx, kex) if axis == "h" else min(ksy, key)
                khi = max(ksx, kex) if axis == "h" else max(ksy, key)
                if lo <= khi + tiny_gap and hi >= klo - tiny_gap:
                    candidate = kept
                    break

            if candidate is None:
                merged.append(item)
                continue

            csx, csy = map(float, candidate["start_px"])
            cex, cey = map(float, candidate["end_px"])
            ccoord = (csy + cey) / 2.0 if axis == "h" else (csx + cex) / 2.0
            clo = min(csx, cex) if axis == "h" else min(csy, cey)
            chi = max(csx, cex) if axis == "h" else max(csy, cey)
            nlo, nhi = min(lo, clo), max(hi, chi)
            if float(item.get("structural_core_support", 0.0)) > float(candidate.get("structural_core_support", 0.0)):
                ccoord = coord
            if axis == "h":
                candidate["start_px"] = (nlo, ccoord)
                candidate["end_px"] = (nhi, ccoord)
            else:
                candidate["start_px"] = (ccoord, nlo)
                candidate["end_px"] = (ccoord, nhi)
            candidate["support"] = max(float(candidate.get("support", 0.0)), float(item.get("support", 0.0)))
            candidate["structural_core_support"] = max(
                float(candidate.get("structural_core_support", 0.0)),
                float(item.get("structural_core_support", 0.0)),
            )

    return merged


def clean_axis_wall_segments(
    mask: np.ndarray,
    segments: list[dict[str, Any]],
    *,
    footprint_min_side: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if not segments:
        return [], {"input_count": 0, "output_count": 0, "rejected_thin": 0, "merged": 0}

    core, core_radius = _structural_core(mask, footprint_min_side)
    # The detail vectorizer has already applied its own minimum segment length. A second,
    # stricter 4.5% cutoff here removed genuine short bathroom/corridor partitions from
    # production plans. Keep the cleanup length guard below the vectorizer threshold and
    # let structural-core support decide whether a short stroke is a wall or door graphic.
    min_keep_len = max(8.0, float(footprint_min_side) * 0.020)
    support_radius = max(1, int(round(float(footprint_min_side) * 0.0025)))

    kept: list[dict[str, Any]] = []
    rejected = 0
    short_structural_kept = 0
    for item in segments:
        sx, sy = map(float, item["start_px"])
        ex, ey = map(float, item["end_px"])
        length = math.hypot(ex - sx, ey - sy)
        core_support = _line_support(core, (sx, sy), (ex, ey), radius=support_radius)
        if core_support < 0.58:
            rejected += 1
            continue
        # Never discard a source-thick segment merely for being short. Very tiny pieces are
        # accepted only with strong structural-core evidence; thin door leaves/jambs fail
        # the core-support test above.
        if length < min_keep_len and core_support < 0.82:
            rejected += 1
            continue
        if length < min_keep_len:
            short_structural_kept += 1
        updated = dict(item)
        updated["structural_core_support"] = core_support
        kept.append(updated)

    merged = _merge_axis_segments(kept, footprint_min_side=footprint_min_side)
    return merged, {
        "input_count": len(segments),
        "output_count": len(merged),
        "rejected_thin": rejected,
        "merged": max(0, len(kept) - len(merged)),
        "short_structural_kept": short_structural_kept,
        "core_radius_px": round(float(core_radius), 3),
        "min_keep_len_px": round(float(min_keep_len), 3),
    }


def clean_normalized_blue_walls(
    mask: np.ndarray,
    walls: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Apply structural-thickness filtering and axis de-duplication to final wall vectors."""
    if not walls:
        return [], {"input_count": 0, "output_count": 0, "rejected_thin": 0, "merged": 0}

    ys, xs = np.where(mask > 0)
    if xs.size < 16 or ys.size < 16:
        return walls, {"input_count": len(walls), "output_count": len(walls), "rejected_thin": 0, "merged": 0}

    h, w = mask.shape[:2]
    footprint_min = max(1, min(int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)))
    axis_raw: list[dict[str, Any]] = []
    diagonals: list[dict[str, Any]] = []

    for wall in walls:
        axis = str(wall.get("axis") or "")
        if axis not in {"h", "v"}:
            diagonals.append(dict(wall))
            continue
        start = wall.get("start") or {}
        end = wall.get("end") or {}
        item = dict(wall)
        item["start_px"] = (float(start.get("x", 0.0)) / 100.0 * w, float(start.get("y", 0.0)) / 100.0 * h)
        item["end_px"] = (float(end.get("x", 0.0)) / 100.0 * w, float(end.get("y", 0.0)) / 100.0 * h)
        item["support"] = float(wall.get("image_support", 1.0))
        axis_raw.append(item)

    cleaned_axis, meta = clean_axis_wall_segments(mask, axis_raw, footprint_min_side=footprint_min)
    cleaned: list[dict[str, Any]] = []
    for item in cleaned_axis:
        sx, sy = map(float, item["start_px"])
        ex, ey = map(float, item["end_px"])
        wall = {k: v for k, v in item.items() if k not in {"start_px", "end_px", "support"}}
        wall["start"] = {"x": sx / max(w, 1) * 100.0, "y": sy / max(h, 1) * 100.0}
        wall["end"] = {"x": ex / max(w, 1) * 100.0, "y": ey / max(h, 1) * 100.0}
        wall["image_support"] = round(float(item.get("support", 1.0)), 4)
        wall["structural_core_support"] = round(float(item.get("structural_core_support", 0.0)), 4)
        cleaned.append(wall)

    cleaned.extend(diagonals)
    for index, wall in enumerate(cleaned):
        wall["id"] = f"blue-detail-{index}"

    meta["input_count"] = len(walls)
    meta["output_count"] = len(cleaned)
    meta["diagonal_kept"] = len(diagonals)
    return cleaned, meta
