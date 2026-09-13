from __future__ import annotations

from dataclasses import dataclass
from math import hypot
from typing import Any


@dataclass(frozen=True)
class CanonicalGeometryResult:
    geometry: dict[str, Any]
    errors: list[str]
    warnings: list[str]

    @property
    def ready(self) -> bool:
        return not self.errors


def _number(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _point(raw: Any, sx: float, sy: float) -> dict[str, float]:
    raw = raw if isinstance(raw, dict) else {}
    return {
        "x": _number(raw.get("x")) * sx,
        "y": _number(raw.get("y")) * sy,
    }


def _normalized_point(raw: Any) -> tuple[float, float]:
    raw = raw if isinstance(raw, dict) else {}
    return _number(raw.get("x")), _number(raw.get("y"))


def _wall_length_m(start: dict[str, float], end: dict[str, float]) -> float:
    return hypot(end["x"] - start["x"], end["y"] - start["y"])


def _opening_width_m(opening: dict[str, Any], sx: float, sy: float) -> float:
    explicit = _number(opening.get("widthM"), 0.0)
    if explicit > 0:
        return explicit
    raw = abs(_number(opening.get("width"), 0.0))
    # Android stores opening width in the same normalized 0..100 plan space.
    return raw * ((sx + sy) / 2.0)


def _canonical_wall(raw: dict[str, Any], sx: float, sy: float, index: int) -> dict[str, Any] | None:
    start = _point(raw.get("start"), sx, sy)
    end = _point(raw.get("end"), sx, sy)
    length = _wall_length_m(start, end)
    if length < 0.05:
        return None
    thickness_cm = _number(raw.get("thicknessCm"), 20.0)
    if not 8.0 <= thickness_cm <= 80.0:
        thickness_cm = 20.0
    return {
        "id": str(raw.get("id") or f"wall-{index}"),
        "start": start,
        "end": end,
        "lengthM": length,
        "thicknessM": thickness_cm / 100.0,
        "confidence": int(max(0, min(100, _number(raw.get("confidence"), 0.0)))),
        "kind": str(raw.get("kind") or "unknown"),
    }


def _canonical_opening(
    raw: dict[str, Any],
    sx: float,
    sy: float,
    wall_ids: set[str],
    index: int,
) -> dict[str, Any] | None:
    wall_id = str(raw.get("wallId") or "").strip()
    if not wall_id or wall_id not in wall_ids:
        return None
    kind = str(raw.get("type") or "door").lower()
    kind = "window" if "window" in kind or "ناف" in kind else "door"
    width_m = _opening_width_m(raw, sx, sy)
    if width_m <= 0.15:
        return None
    x_norm, y_norm = _normalized_point(raw)
    return {
        "id": str(raw.get("id") or f"opening-{index}"),
        "type": kind,
        "wallId": wall_id,
        "center": {"x": x_norm * sx, "y": y_norm * sy},
        "widthM": min(width_m, 5.0),
        "heightM": _number(raw.get("heightM"), 2.15 if kind == "door" else 1.35),
        "sillM": 0.0 if kind == "door" else _number(raw.get("sillM"), 0.9),
        "confidence": int(max(0, min(100, _number(raw.get("confidence"), 0.0)))),
    }


def _floor_source(plan: dict[str, Any]) -> list[dict[str, Any]]:
    floors = plan.get("floors")
    if isinstance(floors, list) and floors:
        return [f for f in floors if isinstance(f, dict)]
    return [{
        "id": str(plan.get("activeFloorId") or "floor-0"),
        "name": "الدور الأرضي",
        "index": 0,
        "elevationM": 0.0,
        "clearHeightM": 3.0,
        "footprint": plan.get("footprint") or [],
        "walls": plan.get("walls") or [],
        "openings": plan.get("openings") or [],
        "rooms": plan.get("rooms") or [],
    }]


def canonicalize_plan(plan: dict[str, Any]) -> CanonicalGeometryResult:
    errors: list[str] = []
    warnings: list[str] = []
    width_m = _number(plan.get("widthM"), 0.0)
    depth_m = _number(plan.get("heightM"), 0.0)

    if width_m <= 0.5 or depth_m <= 0.5:
        errors.append("metric scale is not verified: widthM and heightM are required before server 3D")
        width_m = max(width_m, 1.0)
        depth_m = max(depth_m, 1.0)

    sx = width_m / 100.0
    sy = depth_m / 100.0
    canonical_floors: list[dict[str, Any]] = []
    total_walls = 0
    total_openings = 0

    for floor_index, floor in enumerate(_floor_source(plan)):
        raw_walls = floor.get("walls") if isinstance(floor.get("walls"), list) else []
        walls = [
            wall for wall in (
                _canonical_wall(raw, sx, sy, i)
                for i, raw in enumerate(raw_walls)
                if isinstance(raw, dict)
            )
            if wall is not None
        ]
        wall_ids = {wall["id"] for wall in walls}
        raw_openings = floor.get("openings") if isinstance(floor.get("openings"), list) else []
        openings = [
            opening for opening in (
                _canonical_opening(raw, sx, sy, wall_ids, i)
                for i, raw in enumerate(raw_openings)
                if isinstance(raw, dict)
            )
            if opening is not None
        ]

        raw_footprint = floor.get("footprint") if isinstance(floor.get("footprint"), list) else []
        footprint = [_point(p, sx, sy) for p in raw_footprint if isinstance(p, dict)]
        if len(footprint) < 3:
            footprint = [
                {"x": 0.0, "y": 0.0},
                {"x": width_m, "y": 0.0},
                {"x": width_m, "y": depth_m},
                {"x": 0.0, "y": depth_m},
            ]
            warnings.append(f"{floor.get('id', floor_index)} footprint was missing; plot rectangle used only as slab envelope")

        room_count = len(floor.get("rooms") or []) if isinstance(floor.get("rooms"), list) else 0
        if len(walls) < 4:
            errors.append(f"{floor.get('id', floor_index)} has fewer than four valid walls")
        if room_count == 0:
            warnings.append(f"{floor.get('id', floor_index)} has no verified rooms")
        orphaned = len(raw_openings) - len(openings)
        if orphaned > 0:
            warnings.append(f"{floor.get('id', floor_index)} ignored {orphaned} opening(s) without a valid wall association")

        total_walls += len(walls)
        total_openings += len(openings)
        canonical_floors.append({
            "id": str(floor.get("id") or f"floor-{floor_index}"),
            "name": str(floor.get("name") or f"Floor {floor_index}"),
            "index": int(_number(floor.get("index"), floor_index)),
            "elevationM": _number(floor.get("elevationM"), floor_index * 3.2),
            "clearHeightM": max(2.2, min(5.5, _number(floor.get("clearHeightM"), 3.0))),
            "footprint": footprint,
            "walls": walls,
            "openings": openings,
            "roomCount": room_count,
        })

    if total_walls < 4:
        errors.append("not enough verified wall geometry for 3D rendering")

    scale_confidence = int(max(0, min(100, _number(plan.get("scaleConfidence"), 0.0))))
    if scale_confidence < 65:
        errors.append(f"scale confidence {scale_confidence}% is below the server-render threshold (65%)")

    geometry = {
        "schemaVersion": 1,
        "title": str(plan.get("title") or "Manzili HAI"),
        "widthM": width_m,
        "depthM": depth_m,
        "scaleConfidence": scale_confidence,
        "northDeg": _number(plan.get("northDeg"), 0.0),
        "floors": canonical_floors,
        "metrics": {
            "wallCount": total_walls,
            "openingCount": total_openings,
            "floorCount": len(canonical_floors),
        },
    }
    return CanonicalGeometryResult(geometry=geometry, errors=list(dict.fromkeys(errors)), warnings=list(dict.fromkeys(warnings)))
