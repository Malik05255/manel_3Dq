from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import bpy


def args() -> argparse.Namespace:
    tail = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args(tail)


def clear_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for datablocks in (bpy.data.meshes, bpy.data.curves, bpy.data.materials, bpy.data.cameras, bpy.data.lights):
        for block in list(datablocks):
            if block.users == 0:
                datablocks.remove(block)


def material(name: str, color: tuple[float, float, float, float], roughness: float, metallic: float = 0.0, transmission: float = 0.0):
    mat = bpy.data.materials.new(name=name)
    mat.use_nodes = True
    node = mat.node_tree.nodes.get("Principled BSDF")
    if node is not None:
        if "Base Color" in node.inputs:
            node.inputs["Base Color"].default_value = color
        if "Roughness" in node.inputs:
            node.inputs["Roughness"].default_value = roughness
        if "Metallic" in node.inputs:
            node.inputs["Metallic"].default_value = metallic
        if "Transmission Weight" in node.inputs:
            node.inputs["Transmission Weight"].default_value = transmission
        elif "Transmission" in node.inputs:
            node.inputs["Transmission"].default_value = transmission
        if "IOR" in node.inputs:
            node.inputs["IOR"].default_value = 1.45
    mat.diffuse_color = color
    if transmission > 0.0:
        if hasattr(mat, "surface_render_method"):
            mat.surface_render_method = "DITHERED"
        elif hasattr(mat, "blend_method"):
            mat.blend_method = "BLEND"
    return mat


def add_box(name: str, center: tuple[float, float, float], size: tuple[float, float, float], rotation_z: float, mat) -> None:
    sx, sy, sz = size
    if min(sx, sy, sz) <= 0.001:
        return
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center, rotation=(0.0, 0.0, rotation_z))
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = (sx, sy, sz)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if mat is not None:
        obj.data.materials.append(mat)
    obj["manziliSemantic"] = name.split("-")[0]


def wall_box(wall: dict, start_m: float, end_m: float, z0: float, height: float, mat, suffix: str) -> None:
    a = wall["start"]
    b = wall["end"]
    dx = float(b["x"]) - float(a["x"])
    dy = float(b["y"]) - float(a["y"])
    length = max(0.0001, math.hypot(dx, dy))
    ux, uy = dx / length, dy / length
    segment = max(0.0, end_m - start_m)
    if segment <= 0.015 or height <= 0.015:
        return
    mid = (start_m + end_m) / 2.0
    cx = float(a["x"]) + ux * mid
    cy = float(a["y"]) + uy * mid
    add_box(
        f"wall-{wall['id']}-{suffix}",
        (cx, cy, z0 + height / 2.0),
        (segment, max(0.08, float(wall.get("thicknessM", 0.2))), height),
        math.atan2(dy, dx),
        mat,
    )


def opening_interval(wall: dict, opening: dict) -> tuple[float, float]:
    a = wall["start"]
    b = wall["end"]
    c = opening["center"]
    dx = float(b["x"]) - float(a["x"])
    dy = float(b["y"]) - float(a["y"])
    length = max(0.0001, math.hypot(dx, dy))
    ux, uy = dx / length, dy / length
    projected = (float(c["x"]) - float(a["x"])) * ux + (float(c["y"]) - float(a["y"])) * uy
    half = min(float(opening.get("widthM", 0.9)) / 2.0, length * 0.45)
    return max(0.0, projected - half), min(length, projected + half)


def build_wall(wall: dict, openings: list[dict], elevation: float, clear_height: float, mats: dict) -> None:
    length = float(wall.get("lengthM", 0.0))
    if length <= 0.05:
        return
    prepared = []
    for opening in openings:
        s, e = opening_interval(wall, opening)
        if e - s >= 0.15:
            prepared.append((s, e, opening))
    prepared.sort(key=lambda item: item[0])

    cursor = 0.0
    for index, (start, end, opening) in enumerate(prepared):
        start = max(start, cursor)
        if start > cursor:
            wall_box(wall, cursor, start, elevation, clear_height, mats["wall"], f"solid-{index}")
        if end <= start:
            continue

        kind = opening.get("type", "door")
        opening_height = max(0.5, min(clear_height - 0.15, float(opening.get("heightM", 2.15))))
        sill = 0.0 if kind == "door" else max(0.3, min(clear_height - opening_height - 0.15, float(opening.get("sillM", 0.9))))

        if sill > 0.02:
            wall_box(wall, start, end, elevation, sill, mats["wall"], f"sill-{index}")
        top_start = sill + opening_height
        if clear_height - top_start > 0.02:
            wall_box(wall, start, end, elevation + top_start, clear_height - top_start, mats["wall"], f"header-{index}")

        a = wall["start"]
        b = wall["end"]
        dx = float(b["x"]) - float(a["x"])
        dy = float(b["y"]) - float(a["y"])
        ux, uy = dx / length, dy / length
        mid = (start + end) / 2.0
        cx = float(a["x"]) + ux * mid
        cy = float(a["y"]) + uy * mid
        width = end - start
        angle = math.atan2(dy, dx)
        thickness = max(0.04, float(wall.get("thicknessM", 0.2)) * 0.35)
        if kind == "door":
            add_box(f"door-{opening['id']}", (cx, cy, elevation + opening_height / 2.0), (width * 0.96, thickness, opening_height * 0.98), angle, mats["door"])
        else:
            add_box(f"window-{opening['id']}", (cx, cy, elevation + sill + opening_height / 2.0), (width * 0.96, thickness * 0.55, opening_height * 0.96), angle, mats["glass"])
        cursor = max(cursor, end)

    if cursor < length:
        wall_box(wall, cursor, length, elevation, clear_height, mats["wall"], "tail")


def bounds(points: list[dict], width: float, depth: float) -> tuple[float, float, float, float]:
    if len(points) < 3:
        return 0.0, width, 0.0, depth
    xs = [float(p["x"]) for p in points]
    ys = [float(p["y"]) for p in points]
    return min(xs), max(xs), min(ys), max(ys)


def build_scene(data: dict) -> None:
    clear_scene()
    mats = {
        "wall": material("HAI Plaster", (0.84, 0.82, 0.76, 1.0), 0.62),
        "slab": material("HAI Concrete", (0.47, 0.48, 0.47, 1.0), 0.82),
        "door": material("HAI Timber", (0.26, 0.105, 0.035, 1.0), 0.32),
        "glass": material("HAI Glass", (0.16, 0.42, 0.58, 0.32), 0.08, transmission=0.88),
        "roof": material("HAI Roof", (0.58, 0.56, 0.51, 1.0), 0.72),
    }

    width = float(data["widthM"])
    depth = float(data["depthM"])
    floors = data.get("floors") or []
    for floor_index, floor in enumerate(floors):
        elevation = float(floor.get("elevationM", floor_index * 3.2))
        clear_height = float(floor.get("clearHeightM", 3.0))
        x0, x1, y0, y1 = bounds(floor.get("footprint") or [], width, depth)
        slab_thickness = 0.16
        add_box(
            f"slab-{floor.get('id', floor_index)}",
            ((x0 + x1) / 2.0, (y0 + y1) / 2.0, elevation - slab_thickness / 2.0),
            (max(0.2, x1 - x0), max(0.2, y1 - y0), slab_thickness),
            0.0,
            mats["slab"],
        )

        openings_by_wall: dict[str, list[dict]] = {}
        for opening in floor.get("openings") or []:
            openings_by_wall.setdefault(str(opening.get("wallId")), []).append(opening)
        for wall in floor.get("walls") or []:
            build_wall(wall, openings_by_wall.get(str(wall.get("id")), []), elevation, clear_height, mats)

        if floor_index == len(floors) - 1:
            roof_thickness = 0.18
            add_box(
                "roof-main",
                ((x0 + x1) / 2.0, (y0 + y1) / 2.0, elevation + clear_height + roof_thickness / 2.0),
                (max(0.2, x1 - x0), max(0.2, y1 - y0), roof_thickness),
                0.0,
                mats["roof"],
            )

    bpy.context.scene.world.color = (0.055, 0.065, 0.075)
    bpy.context.scene["manziliGeometrySchema"] = int(data.get("schemaVersion", 1))
    bpy.context.scene["manziliScaleConfidence"] = int(data.get("scaleConfidence", 0))


def main() -> None:
    parsed = args()
    source = Path(parsed.input)
    output = Path(parsed.output)
    data = json.loads(source.read_text(encoding="utf-8"))
    build_scene(data)
    output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(output),
        export_format="GLB",
        export_apply=True,
        export_materials="EXPORT",
        export_cameras=False,
        export_lights=False,
    )
    if not output.is_file() or output.stat().st_size < 20:
        raise RuntimeError("GLB export failed")


if __name__ == "__main__":
    main()
