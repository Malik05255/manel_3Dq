"""Install a rootless Tesseract runtime for Render native Python services."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = BACKEND_ROOT / ".portable-tesseract"
PACKAGES = (
    "tesseract-ocr",
    "tesseract-ocr-ara",
    "tesseract-ocr-eng",
    "tesseract-ocr-osd",
    "libtesseract5",
    "liblept5",
)


def tessdata_dir(root: Path) -> Path | None:
    candidates = (
        root / "usr/share/tesseract-ocr/5/tessdata",
        root / "usr/share/tesseract-ocr/4.00/tessdata",
        root / "usr/share/tessdata",
    )
    for candidate in candidates:
        if (candidate / "ara.traineddata").is_file() and (candidate / "eng.traineddata").is_file():
            return candidate
    return None


def runtime_env(root: Path) -> dict[str, str]:
    env = dict(os.environ)
    lib_dirs = [
        root / "usr/lib/x86_64-linux-gnu",
        root / "lib/x86_64-linux-gnu",
        root / "usr/lib",
        root / "lib",
    ]
    existing = env.get("LD_LIBRARY_PATH", "")
    joined = ":".join(str(path) for path in lib_dirs if path.is_dir())
    env["LD_LIBRARY_PATH"] = ":".join(part for part in (joined, existing) if part)
    tessdata = tessdata_dir(root)
    if tessdata is not None:
        env["TESSDATA_PREFIX"] = str(tessdata)
    return env


def verify(root: Path) -> None:
    binary = root / "usr/bin/tesseract"
    tessdata = tessdata_dir(root)
    if not binary.is_file():
        raise RuntimeError(f"portable tesseract binary missing: {binary}")
    if tessdata is None:
        raise RuntimeError("portable tesseract Arabic/English traineddata is missing")

    env = runtime_env(root)
    version = subprocess.run(
        [str(binary), "--version"], env=env, check=True, capture_output=True, text=True, timeout=30
    )
    languages = subprocess.run(
        [str(binary), "--list-langs"], env=env, check=True, capture_output=True, text=True, timeout=30
    )
    available = {line.strip() for line in languages.stdout.splitlines() if line.strip()}
    missing = {"ara", "eng"} - available
    if missing:
        raise RuntimeError(f"portable tesseract missing languages: {sorted(missing)}")
    first_line = (version.stdout or version.stderr).splitlines()[0]
    print(f"Portable Tesseract ready: {first_line}; languages=ara,eng; root={root}")


def install(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    apt = shutil.which("apt-get") or shutil.which("apt")
    dpkg_deb = shutil.which("dpkg-deb")
    if not apt or not dpkg_deb:
        raise RuntimeError("apt/apt-get and dpkg-deb are required for rootless Tesseract installation")

    with tempfile.TemporaryDirectory(prefix="manzili-tesseract-") as temp_dir:
        temp = Path(temp_dir)
        subprocess.run([apt, "download", *PACKAGES], cwd=temp, check=True, timeout=300)
        archives = sorted(temp.glob("*.deb"))
        if not archives:
            raise RuntimeError("apt download returned no Tesseract packages")
        for archive in archives:
            subprocess.run([dpkg_deb, "-x", str(archive), str(root)], check=True, timeout=60)


def main() -> int:
    root = Path(os.getenv("TESSERACT_PORTABLE_ROOT", str(DEFAULT_ROOT))).resolve()
    force = os.getenv("FORCE_PORTABLE_TESSERACT", "").strip().lower() in {"1", "true", "yes", "on"}
    if force and root.exists():
        shutil.rmtree(root)
    try:
        verify(root)
        return 0
    except Exception:
        pass
    install(root)
    verify(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
