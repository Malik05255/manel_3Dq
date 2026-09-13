#!/usr/bin/env python3
from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path

import bpy

import blender_build_v2 as base

PBR_EXTENSIONS = ("jpg", "jpeg", "png", "webp")
TEXTURE_SCALE = {
    "plaster": 3.2,
    "concrete": 2.6,
    "wood": 1.3,
    "metal": 2.0,
    "limestone": 2.2,
    "sand": 4.0,
    "paving": 3.0,
    "soil": 4.0,
}


def _asset_root() -> Path:
    return Path(os.getenv("PBR_ASSET_DIR", "/srv/manzili/assets/pbr"))


def _find_map(texture_key: str, suffix: str) -> Path | None:
    root = _asset_root()
    for ext in PBR_EXTENSIONS:
        candidate = root / f"{texture_key}_{suffix}.{ext}"
        if candidate.is_file():
            return candidate
    return None


def _principled(mat):
    return mat.node_tree.nodes.get("Principled BSDF") if mat and mat.use_nodes else None


def material(
    name: str,
    color: tuple[float, float, float, float],
    roughness: float,
    *,
    metallic: float = 0.0,
    transmission: float = 0.0,
    texture_key: str | None = None,
):
    mat = bpy.data.materials.new(name=name)
    mat.use_nodes = True
    bsdf = _principled(mat)
    if bsdf is None:
        return mat

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
        bsdf.inputs["IOR"].default_value = 1.47 if transmission > 0.0 else 1.45
    if "Coat Weight" in bsdf.inputs:
        bsdf.inputs["Coat Weight"].default_value = 0.16 if metallic > 0.2 or "Timber" in name else 0.05
    if "Coat Roughness" in bsdf.inputs:
        bsdf.inputs["Coat Roughness"].default_value = 0.22

    mat.diffuse_color = color
    if transmission > 0.0:
        if hasattr(mat, "surface_render_method"):
            mat.surface_render_method = "DITHERED"
        elif hasattr(mat, "blend_method"):
            mat.blend_method = "BLEND"

    if not texture_key:
        return mat

    maps = {
        "basecolor": _find_map(texture_key, "basecolor"),
        "roughness": _find_map(texture_key, "roughness"),
        "normal": _find_map(texture_key, "normal"),
    }
    if not any(maps.values()):
        return mat

    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    texcoord = nodes.new("ShaderNodeTexCoord")
    mapping = nodes.new("ShaderNodeMapping")
    links.new(texcoord.outputs["UV"], mapping.inputs["Vector"])
    scale = float(TEXTURE_SCALE.get(texture_key, 2.0))
    mapping.inputs["Scale"].default_value = (scale, scale, scale)

    for suffix, path in maps.items():
        if path is None:
            continue
        image = bpy.data.images.load(str(path), check_existing=True)
        if suffix != "basecolor":
            image.colorspace_settings.name = "Non-Color"
        tex = nodes.new("ShaderNodeTexImage")
        tex.image = image
        tex.extension = "REPEAT"
        links.new(mapping.outputs["Vector"], tex.inputs["Vector"])
        if suffix == "basecolor" and "Base Color" in bsdf.inputs:
            links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        elif suffix == "roughness" and "Roughness" in bsdf.inputs:
            links.new(tex.outputs["Color"], bsdf.inputs["Roughness"])
        elif suffix == "normal" and "Normal" in bsdf.inputs:
            normal = nodes.new("ShaderNodeNormalMap")
            normal.inputs["Strength"].default_value = 0.72
            links.new(tex.outputs["Color"], normal.inputs["Color"])
            links.new(normal.outputs["Normal"], bsdf.inputs["Normal"])

    mat["manziliTextureKey"] = texture_key
    mat["manziliTextureScale"] = scale
    return mat


def _add_cylinder(name: str, center: tuple[float, float, float], radius: float, depth: float, mat) -> None:
    bpy.ops.mesh.primitive_cylinder_add(vertices=16, radius=radius, depth=depth, location=center)
    obj = bpy.context.object
    obj.name = name
    if mat is not None:
        obj.data.materials.append(mat)
    obj["manziliSemantic"] = name.split("-")[0]


def _add_shrub(name: str, x: float, y: float, radius: float, mats: dict) -> None:
    base.add_uv_sphere(name, (x, y, radius * 0.72), radius, mats["plant"])


def _add_palm(name: str, x: float, y: float, height: float, mats: dict) -> None:
    trunk_h = height * 0.72
    _add_cylinder(f"tree-{name}-trunk", (x, y, trunk_h / 2.0), 0.13, trunk_h, mats["trunk"])
    crown_z = trunk_h + height * 0.07
    for index in range(6):
        angle = (math.pi * 2.0 * index) / 6.0
        cx = x + math.cos(angle) * 0.38
        cy = y + math.sin(angle) * 0.38
        obj = base.add_uv_sphere(f"tree-{name}-crown-{index}", (cx, cy, crown_z), 0.48, mats["plant"])
        if obj is not None:
            obj.scale.z = 0.38
            obj.scale.x = 1.35
            bpy.context.view_layer.objects.active = obj
            bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)


def add_exterior_detail(width: float, depth: float) -> None:
    exterior = {
        "curb": material("HAI Exterior Curb", (0.58, 0.56, 0.52, 1.0), 0.74, texture_key="concrete"),
        "plant": material("HAI Desert Plant", (0.17, 0.31, 0.12, 1.0), 0.78),
        "trunk": material("HAI Palm Trunk", (0.27, 0.16, 0.075, 1.0), 0.88),
    }
    margin = max(4.0, min(10.0, max(width, depth) * 0.24))
    curb_t = 0.12
    curb_h = 0.16
    base.add_box("site-curb-front", (width / 2.0, -margin + 0.12, -0.02), (width + margin * 2.0, curb_t, curb_h), 0.0, exterior["curb"], bevel=0.012)
    base.add_box("site-curb-left", (-margin + 0.12, depth / 2.0, -0.02), (depth + margin * 2.0, curb_t, curb_h), math.pi / 2.0, exterior["curb"], bevel=0.012)
    base.add_box("site-curb-right", (width + margin - 0.12, depth / 2.0, -0.02), (depth + margin * 2.0, curb_t, curb_h), math.pi / 2.0, exterior["curb"], bevel=0.012)

    shrub_r = max(0.24, min(0.42, min(width, depth) * 0.025))
    for index, ratio in enumerate((0.20, 0.40, 0.60, 0.80)):
        _add_shrub(f"plant-left-{index}", -margin * 0.28, depth * ratio, shrub_r, exterior)
        _add_shrub(f"plant-right-{index}", width + margin * 0.28, depth * ratio, shrub_r, exterior)

    if width >= 7.0 and depth >= 7.0:
        _add_palm("front-left", -margin * 0.38, depth * 0.18, 3.4, exterior)
        _add_palm("front-right", width + margin * 0.38, depth * 0.18, 3.4, exterior)


def _asset_report() -> tuple[int, list[str]]:
    missing: list[str] = []
    complete = 0
    for key in TEXTURE_SCALE:
        ready = True
        for suffix in ("basecolor", "roughness", "normal"):
            if _find_map(key, suffix) is None:
                missing.append(f"{key}:{suffix}")
                ready = False
        if ready:
            complete += 1
    return complete, missing


def build_scene(data: dict) -> None:
    base.material = material
    base.build_scene(data)
    width = float(data["widthM"])
    depth = float(data["depthM"])
    add_exterior_detail(width, depth)
    complete, missing = _asset_report()
    scene = bpy.context.scene
    scene["manziliRenderer"] = "blender-pbr-v3"
    scene["manziliPbrCompleteSets"] = complete
    scene["manziliPbrMissingMaps"] = ",".join(missing[:32])
    scene["manziliExteriorDetail"] = "curbs+shrubs+palms"


def main() -> None:
    parsed = base.parse_args()
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
        export_yup=True,
    )
    if not output.is_file() or output.stat().st_size < 20:
        raise RuntimeError("GLB export failed")


if __name__ == "__main__":
    main()
