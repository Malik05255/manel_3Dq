from __future__ import annotations

import argparse
import json
import math
import os
import sys
from pathlib import Path

import bpy


def parse_args() -> argparse.Namespace:
    tail = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    return parser.parse_args(tail)


def clear_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for datablocks in (bpy.data.meshes, bpy.data.curves, bpy.data.materials, bpy.data.cameras, bpy.data.lights, bpy.data.images):
        for block in list(datablocks):
            if block.users == 0:
                datablocks.remove(block)


def _principled(mat):
    return mat.node_tree.nodes.get("Principled BSDF") if mat and mat.use_nodes else None


def _connect_texture(mat, socket_name: str, path: Path, *, non_color: bool = False, normal: bool = False) -> None:
    if not path.is_file():
        return
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    bsdf = _principled(mat)
    if bsdf is None or socket_name not in bsdf.inputs:
        return
    image = bpy.data.images.load(str(path), check_existing=True)
    if non_color:
        image.colorspace_settings.name = "Non-Color"
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = image
    if normal:
        normal_node = nodes.new("ShaderNodeNormalMap")
        links.new(tex.outputs.get("Color"), normal_node.inputs.get("Color"))
        links.new(normal_node.outputs.get("Normal"), bsdf.inputs.get("Normal"))
    else:
        output = tex.outputs.get("Color") or tex.outputs[0]
        links.new(output, bsdf.inputs[socket_name])


def material(name: str, color: tuple[float, float, float, float], roughness: float, *, metallic: float = 0.0, transmission: float = 0.0, texture_key: str | None = None):
    mat = bpy.data.materials.new(name=name)
    mat.use_nodes = True
    bsdf = _principled(mat)
    if bsdf is not None:
        if "Base Color" in bsdf.inputs:
            bsdf.inputs["Base Color"].default_value = color
        if "Roughness" in bsdf.inputs:
            bsdf.inputs["Roughness"].default_value = roughness
        if "Metallic" in bsdf.inputs:
            bsdf.inputs["Metallic"].default_value = metallic
        transmission_socket = "Transmission Weight" if "Transmission Weight" in bsdf.inputs else "Transmission"
        if transmission_socket in bsdf.inputs:
            bsdf.inputs[transmission_socket].default_value = transmission
        if "IOR" in bsdf.inputs:
            bsdf.inputs["IOR"].default_value = 1.45
        if "Coat Weight" in bsdf.inputs:
            bsdf.inputs["Coat Weight"].default_value = 0.12 if metallic > 0.2 or "Timber" in name else 0.04
    mat.diffuse_color = color
    if transmission > 0.0:
        if hasattr(mat, "surface_render_method"):
            mat.surface_render_method = "DITHERED"
        elif hasattr(mat, "blend_method"):
            mat.blend_method = "BLEND"
    if texture_key:
        root = Path(os.getenv("PBR_ASSET_DIR", "/srv/manzili/assets/pbr"))
        _connect_texture(mat, "Base Color", root / f"{texture_key}_basecolor.jpg")
        _connect_texture(mat, "Roughness", root / f"{texture_key}_roughness.jpg", non_color=True)
        _connect_texture(mat, "Normal", root / f"{texture_key}_normal.jpg", non_color=True, normal=True)
    return mat


def add_box(name: str, center: tuple[float, float, float], size: tuple[float, float, float], rotation_z: float, mat, *, bevel: float = 0.0):
    sx, sy, sz = size
    if min(sx, sy, sz) <= 0.001:
        return None
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center, rotation=(0.0, 0.0, rotation_z))
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = (sx, sy, sz)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if mat is not None:
        obj.data.materials.append(mat)
    if bevel > 0.0:
        modifier = obj.modifiers.new(name="Edge softness", type="BEVEL")
        modifier.width = min(bevel, min(sx, sy, sz) * 0.22)
        modifier.segments = 2
    obj["manziliSemantic"] = name.split("-")[0]
    return obj


def add_uv_sphere(name: str, center: tuple[float, float, float], radius: float, mat):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=20, ring_count=10, radius=radius, location=center)
    obj = bpy.context.object
    obj.name = name
    if mat is not None:
        obj.data.materials.append(mat)
    return obj


def wall_frame(wall: dict) -> tuple[float, float, float, float, float]:
    a, b = wall["start"], wall["end"]
    ax, ay = float(a["x"]), float(a["y"])
    bx, by = float(b["x"]), float(b["y"])
    dx, dy = bx - ax, by - ay
    length = max(0.0001, math.hypot(dx, dy))
    return ax, ay, dx / length, dy / length, length


def wall_local_box(wall: dict, along: float, width_along: float, center_z: float, height: float, thickness: float, mat, name: str, *, normal_offset: float = 0.0, bevel: float = 0.0) -> None:
    ax, ay, ux, uy, _ = wall_frame(wall)
    nx, ny = -uy, ux
    cx = ax + ux * along + nx * normal_offset
    cy = ay + uy * along + ny * normal_offset
    add_box(name, (cx, cy, center_z), (width_along, thickness, height), math.atan2(uy, ux), mat, bevel=bevel)


def opening_interval(wall: dict, opening: dict) -> tuple[float, float]:
    ax, ay, ux, uy, length = wall_frame(wall)
    c = opening["center"]
    projected = (float(c["x"]) - ax) * ux + (float(c["y"]) - ay) * uy
    half = min(float(opening.get("widthM", 0.9)) / 2.0, length * 0.45)
    return max(0.0, projected - half), min(length, projected + half)


def wall_box(wall: dict, start_m: float, end_m: float, z0: float, height: float, mat, suffix: str) -> None:
    segment = max(0.0, end_m - start_m)
    if segment <= 0.015 or height <= 0.015:
        return
    wall_local_box(wall, (start_m + end_m) / 2.0, segment, z0 + height / 2.0, height, max(0.08, float(wall.get("thicknessM", 0.2))), mat, f"wall-{wall['id']}-{suffix}", bevel=0.012)


def add_door_assembly(wall: dict, opening: dict, start: float, end: float, elevation: float, clear_height: float, mats: dict) -> None:
    width = end - start
    height = max(1.8, min(clear_height - 0.15, float(opening.get("heightM", 2.15))))
    center = (start + end) / 2.0
    frame = min(0.09, max(0.055, width * 0.08))
    wall_thickness = max(0.12, float(wall.get("thicknessM", 0.2)))
    frame_depth = wall_thickness * 1.08
    wall_local_box(wall, start + frame / 2.0, frame, elevation + height / 2.0, height, frame_depth, mats["frame"], f"doorframe-{opening['id']}-left", bevel=0.012)
    wall_local_box(wall, end - frame / 2.0, frame, elevation + height / 2.0, height, frame_depth, mats["frame"], f"doorframe-{opening['id']}-right", bevel=0.012)
    wall_local_box(wall, center, width, elevation + height - frame / 2.0, frame, frame_depth, mats["frame"], f"doorframe-{opening['id']}-top", bevel=0.012)
    slab_width = max(0.3, width - frame * 2.25)
    slab_height = max(1.6, height - frame * 1.25)
    wall_local_box(wall, center, slab_width, elevation + slab_height / 2.0, slab_height, wall_thickness * 0.22, mats["door"], f"door-{opening['id']}", normal_offset=wall_thickness * 0.10, bevel=0.018)
    for ratio in (0.27, 0.56):
        panel_h = slab_height * 0.18
        panel_z = elevation + slab_height * ratio
        wall_local_box(wall, center, slab_width * 0.72, panel_z, panel_h, wall_thickness * 0.235, mats["door_accent"], f"doorpanel-{opening['id']}-{ratio}", normal_offset=wall_thickness * 0.115, bevel=0.01)
    ax, ay, ux, uy, _ = wall_frame(wall)
    nx, ny = -uy, ux
    handle_along = center + slab_width * 0.34
    hx = ax + ux * handle_along + nx * wall_thickness * 0.18
    hy = ay + uy * handle_along + ny * wall_thickness * 0.18
    add_uv_sphere(f"doorhandle-{opening['id']}", (hx, hy, elevation + 1.0), 0.045, mats["metal"])


def add_window_assembly(wall: dict, opening: dict, start: float, end: float, elevation: float, clear_height: float, mats: dict) -> None:
    width = end - start
    height = max(0.55, min(clear_height - 0.35, float(opening.get("heightM", 1.35))))
    sill = max(0.3, min(clear_height - height - 0.15, float(opening.get("sillM", 0.9))))
    center = (start + end) / 2.0
    wall_thickness = max(0.12, float(wall.get("thicknessM", 0.2)))
    frame = min(0.065, max(0.035, width * 0.05))
    frame_depth = wall_thickness * 0.60
    center_z = elevation + sill + height / 2.0
    glass_h = max(0.25, height - frame * 2.2)
    glass_w = max(0.25, width - frame * 2.2)
    wall_local_box(wall, center, glass_w, center_z, glass_h, wall_thickness * 0.08, mats["glass"], f"windowglass-{opening['id']}")
    wall_local_box(wall, start + frame / 2.0, frame, center_z, height, frame_depth, mats["metal"], f"windowframe-{opening['id']}-left", bevel=0.008)
    wall_local_box(wall, end - frame / 2.0, frame, center_z, height, frame_depth, mats["metal"], f"windowframe-{opening['id']}-right", bevel=0.008)
    wall_local_box(wall, center, width, elevation + sill + frame / 2.0, frame, frame_depth, mats["metal"], f"windowframe-{opening['id']}-bottom", bevel=0.008)
    wall_local_box(wall, center, width, elevation + sill + height - frame / 2.0, frame, frame_depth, mats["metal"], f"windowframe-{opening['id']}-top", bevel=0.008)
    if width > 1.15:
        wall_local_box(wall, center, frame * 0.72, center_z, glass_h, frame_depth, mats["metal"], f"windowframe-{opening['id']}-mullion", bevel=0.006)
    wall_local_box(wall, center, width * 1.03, elevation + sill - 0.025, 0.05, wall_thickness * 1.18, mats["stone"], f"windowsill-{opening['id']}", bevel=0.008)


def build_wall(wall: dict, openings: list[dict], elevation: float, clear_height: float, mats: dict) -> None:
    length = float(wall.get("lengthM", 0.0))
    if length <= 0.05:
        return
    prepared: list[tuple[float, float, dict]] = []
    for opening in openings:
        start, end = opening_interval(wall, opening)
        if end - start >= 0.15:
            prepared.append((start, end, opening))
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
        if kind == "door":
            add_door_assembly(wall, opening, start, end, elevation, clear_height, mats)
        else:
            add_window_assembly(wall, opening, start, end, elevation, clear_height, mats)
        cursor = max(cursor, end)
    if cursor < length:
        wall_box(wall, cursor, length, elevation, clear_height, mats["wall"], "tail")


def bounds(points: list[dict], width: float, depth: float) -> tuple[float, float, float, float]:
    if len(points) < 3:
        return 0.0, width, 0.0, depth
    xs = [float(p["x"]) for p in points]
    ys = [float(p["y"]) for p in points]
    return min(xs), max(xs), min(ys), max(ys)


def build_environment(width: float, depth: float, mats: dict) -> None:
    margin = max(4.0, min(10.0, max(width, depth) * 0.24))
    add_box("site-ground", (width / 2.0, depth / 2.0, -0.22), (width + margin * 2.0, depth + margin * 2.0, 0.30), 0.0, mats["ground"], bevel=0.02)
    walkway_w = min(max(1.4, width * 0.18), 3.0)
    add_box("site-entry-walkway", (width / 2.0, -margin * 0.46, -0.035), (walkway_w, margin * 1.05, 0.09), 0.0, mats["paving"], bevel=0.018)
    planter_depth = min(1.25, margin * 0.22)
    add_box("site-planter-left", (margin * 0.30, depth * 0.52, -0.01), (planter_depth, max(2.0, depth * 0.34), 0.12), 0.0, mats["soil"], bevel=0.025)
    add_box("site-planter-right", (width - margin * 0.30, depth * 0.52, -0.01), (planter_depth, max(2.0, depth * 0.34), 0.12), 0.0, mats["soil"], bevel=0.025)


def build_scene(data: dict) -> None:
    clear_scene()
    mats = {
        "wall": material("HAI Fine Plaster", (0.90, 0.875, 0.82, 1.0), 0.54, texture_key="plaster"),
        "slab": material("HAI Concrete", (0.50, 0.51, 0.49, 1.0), 0.82, texture_key="concrete"),
        "door": material("HAI Walnut Timber", (0.29, 0.12, 0.045, 1.0), 0.30, texture_key="wood"),
        "door_accent": material("HAI Walnut Accent", (0.18, 0.065, 0.02, 1.0), 0.25, texture_key="wood"),
        "frame": material("HAI Door Frame", (0.20, 0.075, 0.025, 1.0), 0.28, texture_key="wood"),
        "glass": material("HAI Architectural Glass", (0.12, 0.30, 0.42, 0.22), 0.055, transmission=0.94),
        "metal": material("HAI Brushed Aluminum", (0.18, 0.19, 0.20, 1.0), 0.24, metallic=0.82, texture_key="metal"),
        "stone": material("HAI Saudi Limestone", (0.70, 0.63, 0.51, 1.0), 0.48, texture_key="limestone"),
        "roof": material("HAI Roof", (0.64, 0.61, 0.55, 1.0), 0.74, texture_key="concrete"),
        "ground": material("HAI Site Sand", (0.47, 0.40, 0.30, 1.0), 0.96, texture_key="sand"),
        "paving": material("HAI Paving", (0.59, 0.56, 0.49, 1.0), 0.68, texture_key="paving"),
        "soil": material("HAI Planting Soil", (0.16, 0.12, 0.07, 1.0), 0.98, texture_key="soil"),
    }
    width = float(data["widthM"])
    depth = float(data["depthM"])
    build_environment(width, depth, mats)
    floors = data.get("floors") or []
    for floor_index, floor in enumerate(floors):
        elevation = float(floor.get("elevationM", floor_index * 3.2))
        clear_height = float(floor.get("clearHeightM", 3.0))
        x0, x1, y0, y1 = bounds(floor.get("footprint") or [], width, depth)
        slab_thickness = 0.18
        add_box(f"slab-{floor.get('id', floor_index)}", ((x0 + x1) / 2.0, (y0 + y1) / 2.0, elevation - slab_thickness / 2.0), (max(0.2, x1 - x0), max(0.2, y1 - y0), slab_thickness), 0.0, mats["slab"], bevel=0.012)
        openings_by_wall: dict[str, list[dict]] = {}
        for opening in floor.get("openings") or []:
            openings_by_wall.setdefault(str(opening.get("wallId")), []).append(opening)
        for wall in floor.get("walls") or []:
            build_wall(wall, openings_by_wall.get(str(wall.get("id")), []), elevation, clear_height, mats)
        if floor_index == len(floors) - 1:
            roof_thickness = 0.20
            add_box("roof-main", ((x0 + x1) / 2.0, (y0 + y1) / 2.0, elevation + clear_height + roof_thickness / 2.0), (max(0.2, x1 - x0), max(0.2, y1 - y0), roof_thickness), 0.0, mats["roof"], bevel=0.015)
            parapet_h, parapet_t = 0.78, 0.16
            add_box("parapet-front", ((x0 + x1) / 2.0, y0, elevation + clear_height + parapet_h / 2.0), (x1 - x0, parapet_t, parapet_h), 0.0, mats["stone"], bevel=0.015)
            add_box("parapet-back", ((x0 + x1) / 2.0, y1, elevation + clear_height + parapet_h / 2.0), (x1 - x0, parapet_t, parapet_h), 0.0, mats["stone"], bevel=0.015)
            add_box("parapet-left", (x0, (y0 + y1) / 2.0, elevation + clear_height + parapet_h / 2.0), (y1 - y0, parapet_t, parapet_h), math.pi / 2.0, mats["stone"], bevel=0.015)
            add_box("parapet-right", (x1, (y0 + y1) / 2.0, elevation + clear_height + parapet_h / 2.0), (y1 - y0, parapet_t, parapet_h), math.pi / 2.0, mats["stone"], bevel=0.015)
    bpy.context.scene.world.color = (0.72, 0.79, 0.88)
    bpy.context.scene["manziliGeometrySchema"] = int(data.get("schemaVersion", 1))
    bpy.context.scene["manziliScaleConfidence"] = int(data.get("scaleConfidence", 0))
    bpy.context.scene["manziliRenderer"] = "blender-pbr-v2"


def main() -> None:
    parsed = parse_args()
    source = Path(parsed.input)
    output = Path(parsed.output)
    data = json.loads(source.read_text(encoding="utf-8"))
    build_scene(data)
    output.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=str(output), export_format="GLB", export_apply=True, export_materials="EXPORT", export_cameras=False, export_lights=False, export_yup=True)
    if not output.is_file() or output.stat().st_size < 20:
        raise RuntimeError("GLB export failed")


if __name__ == "__main__":
    main()
