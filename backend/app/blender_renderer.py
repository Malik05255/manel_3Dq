from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from .geometry import canonicalize_plan


def blender_status() -> dict[str, Any]:
    configured = os.getenv("BLENDER_BIN", "").strip()
    executable = configured or shutil.which("blender") or ""
    return {
        "configured": bool(executable),
        "executable": executable or None,
        "worker_url_configured": bool(os.getenv("BLENDER_WORKER_URL", "").strip()),
    }


def render_plan_glb(plan: dict[str, Any], timeout_seconds: int = 300) -> tuple[bytes, dict[str, Any]]:
    canonical = canonicalize_plan(plan)
    if not canonical.ready:
        raise ValueError("; ".join(canonical.errors))

    executable = os.getenv("BLENDER_BIN", "").strip() or shutil.which("blender")
    if not executable:
        raise RuntimeError("Blender executable is not installed on this service")

    script = Path(__file__).resolve().parents[1] / "scripts" / "blender_build.py"
    if not script.is_file():
        raise RuntimeError("Blender build script is missing")

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
            tail = completed.stdout[-4000:] if completed.stdout else ""
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
            "renderer": "blender-headless",
        }
