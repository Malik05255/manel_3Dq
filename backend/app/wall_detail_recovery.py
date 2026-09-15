from __future__ import annotations

from typing import Any

from .blue_detail_vectorizer import vectorize_blue_detail_walls
from .cubicasa_model import _trim_process_memory
from .parser import _decode_image
from .parser_accuracy import wall_topology_score
from .parser_quality import assign_openings_to_walls


def _wall_count_bounds(current_count: int) -> tuple[int, int]:
    lower = max(5, int(round(current_count * 0.72))) if current_count else 5
    upper = max(18, int(round(max(current_count, 8) * 1.75)))
    return lower, min(90, upper)


def enhance_blue_wall_details(image_base64: str, result: dict[str, Any]) -> dict[str, Any]:
    """Replace coarse blue fallback vectors with source-supported detailed centrelines.

    Room polygons are intentionally left untouched. This pass only improves the returned
    wall graph and remaps already-detected openings to the refined walls. It activates only
    for blue-raster recovery and only when the detailed graph stays within conservative
    count/topology bounds.
    """
    recovery = dict(result.get("geometry_recovery") or {})
    selected = str(recovery.get("selected") or "")
    if "blue-raster" not in selected:
        return result

    current_walls = list(result.get("walls") or [])
    if len(current_walls) < 4:
        return result

    try:
        image = _decode_image(image_base64)
        detailed, meta = vectorize_blue_detail_walls(image)
        if not bool(meta.get("usable")):
            return result

        lower, upper = _wall_count_bounds(len(current_walls))
        if not (lower <= len(detailed) <= upper):
            return result

        current_topology = wall_topology_score(current_walls)
        detail_topology = wall_topology_score(detailed)
        diagonal_count = int(meta.get("diagonal_count") or 0)

        # Never trade a coherent graph for a much more fragmented one. A modest topology
        # decrease is allowed only when the source reveals genuine diagonal/short details.
        tolerance = 14 if diagonal_count > 0 else 9
        if detail_topology + tolerance < current_topology:
            return result

        openings = list(result.get("openings") or [])
        remapped_openings = assign_openings_to_walls(openings, detailed) if openings else openings

        updated = dict(result)
        updated["walls"] = detailed
        updated["openings"] = remapped_openings

        recovery.update({
            "wall_detail_recovery_used": True,
            "wall_count_before_detail": len(current_walls),
            "wall_count_after_detail": len(detailed),
            "wall_topology_before_detail": current_topology,
            "wall_topology_after_detail": detail_topology,
            "wall_detail_mode": "blue-source-door-gap-aware",
            "wall_detail_diagonal_count": diagonal_count,
            "wall_detail_min_segment_px": int(meta.get("min_segment_px") or 0),
            "wall_detail_gap_heal_px": int(meta.get("door_gap_heal_px") or 0),
        })
        updated["geometry_recovery"] = recovery

        quality = dict(updated.get("quality") or {})
        quality["wall_topology"] = detail_topology
        quality["wall_detail_recovery"] = "blue-source-door-gap-aware"
        quality["wall_count_before_detail"] = len(current_walls)
        quality["wall_count_after_detail"] = len(detailed)
        updated["quality"] = quality

        warnings = list(updated.get("warnings") or [])
        warnings.append(
            "Blue CAD wall details were refined from source pixels while preserving door-sized gaps; review before 3D."
        )
        updated["warnings"] = warnings
        return updated
    finally:
        _trim_process_memory()
