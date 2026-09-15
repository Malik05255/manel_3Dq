"""Prepare production Reader V3 assets: portable OCR plus verified CubiCasa weights."""

from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
import urllib.request
from pathlib import Path

MODEL_URL = "https://huggingface.co/Yytsi/floorplan-to-3d-walls/resolve/main/best.safetensors"
MODEL_SHA256 = "d7f6a0fd06e2931aecfc8c4849192c5e153701578026efc78d9a6246731a8d6c"
# Must match app.cubicasa_model.DEFAULT_MODEL_PATH so Docker, CI and runtime all load the same file.
DEFAULT_PATH = "/opt/manzili/models/floorplan/best.safetensors"


def _truthy(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in {"1", "true", "yes", "on"}


def _prepare_portable_ocr() -> None:
    # Render exposes RENDER=true at build time. The existing service build command already
    # runs this script, so OCR can be provisioned without requiring a dashboard build-command change.
    if os.getenv("RENDER", "").strip().lower() != "true" and not _truthy("INSTALL_PORTABLE_TESSERACT"):
        return
    from install_portable_tesseract import main as install_tesseract

    result = install_tesseract()
    if result != 0:
        raise RuntimeError(f"portable Tesseract installation failed with exit code {result}")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _ensure_model() -> None:
    target = Path(os.getenv("FLOORPLAN_SAFETENSORS_MODEL", DEFAULT_PATH))
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and sha256(target) == MODEL_SHA256:
        print(f"CubiCasa model already verified at {target}")
        return

    with tempfile.NamedTemporaryFile(prefix="floorplan-model-", suffix=".safetensors", delete=False) as tmp:
        temp_path = Path(tmp.name)
    try:
        request = urllib.request.Request(MODEL_URL, headers={"User-Agent": "Manzili-HAI/reader-v3"})
        with urllib.request.urlopen(request, timeout=180) as response, temp_path.open("wb") as out:
            shutil.copyfileobj(response, out, length=1024 * 1024)
        digest = sha256(temp_path)
        if digest != MODEL_SHA256:
            raise RuntimeError(f"model checksum mismatch: {digest}")
        # /tmp and /opt/manzili may live on different filesystems, so copy2 is used deliberately.
        shutil.copy2(temp_path, target)
        installed_digest = sha256(target)
        if installed_digest != MODEL_SHA256:
            target.unlink(missing_ok=True)
            raise RuntimeError(f"installed model checksum mismatch: {installed_digest}")
        print(f"Downloaded and verified CubiCasa model at {target}")
    finally:
        temp_path.unlink(missing_ok=True)


def main() -> int:
    _prepare_portable_ocr()
    _ensure_model()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
