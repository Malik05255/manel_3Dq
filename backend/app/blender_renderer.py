from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from .geometry import canonicalize_plan

PBR_TEXTURE_KEYS = (
    "plaster",
    "concrete",
    "wood",
    "metal",
    "limestone",
    "sand",
    "paving",
    "soil",
)
PBR_MAP_SUFFIXES = ("basecolor", "roughness", "normal")
PBR_EXTENSIONS = ("jpg", "jpeg", "png", "webp")


def _pbr_root() -> Path:
    return Path(os.getenv("PBR_ASSET_DIR", "/srv/manzili/assets/pbr"))


def _truthy(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _find_pbr_map(root: Path, key: str, suffix: str) -> Path | None:
    for ext in PBR_EXTENSIONS:
        candidate = root / f"{key}_{suffix}.{ext}"
        if candidate.is_file():
            return candidate
    return None


def pbr_asset_status() -> dict[str, Any]:
    root = _pbr_root()
    found: list[str] = []
    missing: list[str] = []
    material_sets: dict[str, bool] = {}
    for key in PBR_TEXTURE_KEYS:
        complete = True
        for suffix in PBR_MAP_SUFFIXES:
            if _find_pbr_map(root, key, suffix):
                found.append(f"{key}:{suffix}")
            else:
                missing.append(f"{key}:{suffix}")
                complete = False
        material_sets[key] = complete
    complete_sets = sum(1 for ready in material_sets.values() if ready)
    return {
        "root": str(root),
        "required_material_sets": len(PBR_TEXTURE_KEYS),
        "complete_material_sets": complete_sets,
        "complete": complete_sets == len(PBR_TEXTURE_KEYS),
        "material_sets": material_sets,
        "found_maps": found,
        "missing_maps": missing,
        "manifest_present": (root / "manifest.json").is_file(),
    }


def blender_status() -> dict[str, Any]:
    configured = os.getenv("BLENDER_BIN", "").strip()
    executable = configured or shutil.which("blender") or ""
    assets = pbr_asset_status()
    strict_pbr = _truthy("REQUIRE_COMPLETE_PBR", False)
    ready = bool(executable) and (assets["complete"] or not strict_pbr)
    return {
        "configured": bool(executable),
        "ready": ready,
        "executable": executable or None,
        "worker_url_configured": bool(os.getenv("BLENDER_WORKER_URL", "").strip()),
        "renderer": "blender-pbr-v3",
        "require_complete_pbr": strict_pbr,
        "pbr_asset_dir": assets["root"],
        "pbr_assets_present": assets["complete_material_sets"] > 0,
        "pbr_assets_complete": assets["complete"],
        "pbr_complete_material_sets": assets["complete_material_sets"],
        "pbr_missing_maps": assets["missing_maps"],
        "pbr_manifest_present": assets["manifest_present"],
    }


def render_plan_glb(plan: dict[str, Any], timeout_seconds: int = 360) -> tuple[bytes, dict[str, Any]]:
    canonical = canonicalize_plan(plan)
    if not canonical.ready:
        raise ValueError("; ".join(canonical.errors))

    assets = pbr_asset_status()
    if _truthy("REQUIRE_COMPLETE_PBR", False) and not assets["complete"]:
        missing = ", ".join(assets["missing_maps"][:8])
        if len(assets["missing_maps"]) > 8:
            missing += ", ..."
        raise RuntimeError(f"Production PBR assets are incomplete: {missing}")

    executable = os.getenv("BLENDER_BIN", "").strip() or shutil.which("blender")
    if not executable:
        raise RuntimeError("Blender executable is not installed on this service")

    script = Path(__file__).resolve().parents[1] / "scripts" / "blender_build_v3.py"
    if not script.is_file():
        raise RuntimeError("Blender PBR v3 build script is missing")

    with tempfile.TemporaryDirectory(prefix="manzili-blender-") as temp_dir:
        temp = Path(temp_dir)
        input_path = temp / "geometry.json"
        output_path = temp / "house.glb"
        input_path.write_text(json.dumps(canonical.geometry, ensure_ascii=False), encoding="utf-8")

        command = [
            str(executable),
            "-b",
            "--factory-startup",
            "--python",
            str(script),
            "--",
            "--input",
            str(input_path),
            "--output",
            str(output_path),
        ]
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        if completed.returncode != 0:
            tail = completed.stdout[-5000:] if completed.stdout else ""
            raise RuntimeError(f"Blender render failed ({completed.returncode}): {tail}")
        if not output_path.is_file():
            raise RuntimeError("Blender finished without producing a GLB")

        payload = output_path.read_bytes()
        if len(payload) < 20 or payload[:4] != b"glTF":
            raise RuntimeError("Blender output is not a valid GLB container")

        return payload, {
            "bytes": len(payload),
            "geometry": canonical.geometry.get("metrics", {}),
            "warnings": canonical.warnings,
            "renderer": "blender-pbr-v3",
            "pbr_assets_present": assets["complete_material_sets"] > 0,
            "pbr_assets_complete": assets["complete"],
            "pbr_complete_material_sets": assets["complete_material_sets"],
            "pbr_missing_maps": assets["missing_maps"],
        }
