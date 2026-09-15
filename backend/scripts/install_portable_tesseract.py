"""Install a rootless Tesseract runtime for Render native Python services.

Render native Python has apt/dpkg binaries but no sudo and may start without local
APT package indexes. Build an isolated APT state/cache under /tmp, discover the
host distribution's dependency closure there, download the packages without root,
and extract them into the application tree.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = BACKEND_ROOT / ".portable-tesseract"
ROOT_PACKAGES = (
    "tesseract-ocr",
    "tesseract-ocr-ara",
    "tesseract-ocr-eng",
    "tesseract-ocr-osd",
)
HOST_BASE_PACKAGES = {
    "libc6",
    "libgcc-s1",
    "libstdc++6",
    "zlib1g",
    "debconf",
    "debconf-2.0",
}
_DEPENDENCY_RE = re.compile(r"^\s*\|?\s*(?:Pre)?Depends:\s+([^\s]+)")


def tessdata_dir(root: Path) -> Path | None:
    candidates = (
        root / "usr/share/tesseract-ocr/5/tessdata",
        root / "usr/share/tesseract-ocr/4.00/tessdata",
        root / "usr/share/tessdata",
    )
    for candidate in candidates:
        if (candidate / "ara.traineddata").is_file() and (candidate / "eng.traineddata").is_file():
            return candidate
    share = root / "usr/share"
    if share.is_dir():
        for candidate in sorted(share.glob("tesseract-ocr/*/tessdata")):
            if (candidate / "ara.traineddata").is_file() and (candidate / "eng.traineddata").is_file():
                return candidate
    return None


def _portable_library_dirs(root: Path) -> list[Path]:
    candidates = [root / "usr/lib", root / "lib"]
    for base in tuple(candidates):
        if not base.is_dir():
            continue
        candidates.extend(path for path in base.iterdir() if path.is_dir())
    seen: set[str] = set()
    result: list[Path] = []
    for path in candidates:
        value = str(path)
        if path.is_dir() and value not in seen:
            seen.add(value)
            result.append(path)
    return result


def runtime_env(root: Path) -> dict[str, str]:
    env = dict(os.environ)
    existing = env.get("LD_LIBRARY_PATH", "")
    portable = ":".join(str(path) for path in _portable_library_dirs(root))
    env["LD_LIBRARY_PATH"] = ":".join(part for part in (portable, existing) if part)
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


def _prepare_rootless_apt(apt_get: str, state: Path) -> list[str]:
    """Create writable APT package indexes/cache without touching /var/lib/apt."""
    lists = state / "lists"
    archives = state / "archives"
    (lists / "partial").mkdir(parents=True, exist_ok=True)
    (archives / "partial").mkdir(parents=True, exist_ok=True)
    options = [
        "-o", f"Dir::State::lists={lists}",
        "-o", f"Dir::Cache::archives={archives}",
        "-o", "Debug::NoLocking=1",
        "-o", "APT::Get::List-Cleanup=0",
    ]
    result = subprocess.run(
        [apt_get, *options, "update"],
        check=False,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if result.returncode != 0:
        tail = "\n".join((result.stderr or result.stdout).splitlines()[-20:])
        raise RuntimeError(f"rootless apt update failed ({result.returncode}):\n{tail}")
    return options


def _dependency_packages(apt_cache: str, apt_options: list[str]) -> list[str]:
    command = [
        apt_cache,
        *apt_options,
        "depends",
        "--recurse",
        "--no-recommends",
        "--no-suggests",
        "--no-conflicts",
        "--no-breaks",
        "--no-replaces",
        "--no-enhances",
        *ROOT_PACKAGES,
    ]
    result = subprocess.run(command, check=False, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        tail = "\n".join((result.stderr or result.stdout).splitlines()[-20:])
        raise RuntimeError(f"apt dependency discovery failed ({result.returncode}):\n{tail}")
    packages = set(ROOT_PACKAGES)
    for raw_line in result.stdout.splitlines():
        match = _DEPENDENCY_RE.match(raw_line)
        if not match:
            continue
        name = match.group(1).strip()
        if name.startswith("<") or not name:
            continue
        if name.endswith(":any"):
            name = name[:-4]
        if name not in HOST_BASE_PACKAGES:
            packages.add(name)
    return sorted(packages)


def install(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    apt_get = shutil.which("apt-get")
    apt_cache = shutil.which("apt-cache")
    dpkg_deb = shutil.which("dpkg-deb")
    if not apt_get or not apt_cache or not dpkg_deb:
        raise RuntimeError("apt-get, apt-cache and dpkg-deb are required for rootless Tesseract installation")

    with tempfile.TemporaryDirectory(prefix="manzili-tesseract-") as temp_dir:
        temp = Path(temp_dir)
        apt_state = temp / "apt"
        download_dir = temp / "downloads"
        download_dir.mkdir(parents=True, exist_ok=True)
        apt_options = _prepare_rootless_apt(apt_get, apt_state)
        packages = _dependency_packages(apt_cache, apt_options)
        print(f"Portable Tesseract packages ({len(packages)}): {', '.join(packages)}")

        for start in range(0, len(packages), 24):
            batch = packages[start:start + 24]
            result = subprocess.run(
                [apt_get, *apt_options, "download", *batch],
                cwd=download_dir,
                check=False,
                capture_output=True,
                text=True,
                timeout=300,
            )
            if result.returncode != 0:
                tail = "\n".join((result.stderr or result.stdout).splitlines()[-20:])
                raise RuntimeError(f"apt download failed for {batch} ({result.returncode}):\n{tail}")

        archives = sorted(download_dir.glob("*.deb"))
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
