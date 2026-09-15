"""Install a rootless Tesseract runtime for Render native Python services.

The installer resolves runtime dependencies from the host apt metadata, so the same
code works on Ubuntu CI and Render's Debian native runtime without sudo/root access.
All downloaded packages are extracted under backend/.portable-tesseract.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from collections import deque
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = BACKEND_ROOT / ".portable-tesseract"

ROOT_PACKAGES = (
    "tesseract-ocr",
    "tesseract-ocr-ara",
    "tesseract-ocr-eng",
    "tesseract-ocr-osd",
    "libtesseract5",
    "liblept5",
)

HOST_RUNTIME_PACKAGES = {
    "libc6",
    "libgcc-s1",
    "libstdc++6",
    "linux-libc-dev",
    "gcc-12-base",
    "gcc-13-base",
    "gcc-14-base",
    "debconf",
    "dpkg",
}

_DEP_RE = re.compile(r"^(?:\|)?(?:PreDepends|Depends):\s*([^\s]+)")


def _normalize_package(value: str) -> str | None:
    value = value.strip()
    if value.startswith("<") or not value:
        return None
    if ":" in value:
        value = value.split(":", 1)[0]
    return value or None


def _has_candidate(package: str, apt_cache: str) -> bool:
    result = subprocess.run(
        [apt_cache, "policy", package],
        check=False,
        capture_output=True,
        text=True,
        timeout=20,
    )
    return result.returncode == 0 and "Candidate: (none)" not in result.stdout and "Candidate:" in result.stdout


def _direct_dependencies(package: str, apt_cache: str) -> list[str]:
    result = subprocess.run(
        [
            apt_cache,
            "depends",
            "--no-recommends",
            "--no-suggests",
            "--no-conflicts",
            "--no-breaks",
            "--no-replaces",
            "--no-enhances",
            package,
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        return []
    dependencies: list[str] = []
    for raw in result.stdout.splitlines():
        match = _DEP_RE.match(raw.strip())
        if not match:
            continue
        dep = _normalize_package(match.group(1))
        if dep:
            dependencies.append(dep)
    return dependencies


def _dependency_closure(apt_cache: str) -> list[str]:
    queue: deque[str] = deque(ROOT_PACKAGES)
    seen: set[str] = set()
    selected: list[str] = []

    while queue:
        package = queue.popleft()
        if package in seen:
            continue
        seen.add(package)
        if package in HOST_RUNTIME_PACKAGES:
            continue
        if not _has_candidate(package, apt_cache):
            if package in ROOT_PACKAGES:
                raise RuntimeError(f"required Tesseract package has no apt candidate: {package}")
            continue
        selected.append(package)
        if len(selected) > 120:
            raise RuntimeError("portable Tesseract dependency closure unexpectedly exceeded 120 packages")
        for dependency in _direct_dependencies(package, apt_cache):
            if dependency not in seen:
                queue.append(dependency)

    return selected


def _tessdata_dir(root: Path) -> Path | None:
    candidates = (
        root / "usr/share/tesseract-ocr/5/tessdata",
        root / "usr/share/tesseract-ocr/4.00/tessdata",
        root / "usr/share/tessdata",
    )
    for candidate in candidates:
        if (candidate / "ara.traineddata").is_file() and (candidate / "eng.traineddata").is_file():
            return candidate
    return None


def _library_dirs(root: Path) -> list[Path]:
    candidates = [root / "usr/lib", root / "lib"]
    candidates.extend(sorted((root / "usr/lib").glob("*-linux-gnu")))
    candidates.extend(sorted((root / "lib").glob("*-linux-gnu")))
    unique: list[Path] = []
    for candidate in candidates:
        if candidate.is_dir() and candidate not in unique:
            unique.append(candidate)
    return unique


def _runtime_env(root: Path) -> dict[str, str]:
    env = dict(os.environ)
    existing = env.get("LD_LIBRARY_PATH", "")
    joined = ":".join(str(path) for path in _library_dirs(root))
    env["LD_LIBRARY_PATH"] = ":".join(part for part in (joined, existing) if part)
    tessdata = _tessdata_dir(root)
    if tessdata is not None:
        env["TESSDATA_PREFIX"] = str(tessdata)
    return env


def _run_checked_binary(binary: Path, args: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(binary), *args],
        env=env,
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode == 0:
        return result
    ldd = shutil.which("ldd")
    ldd_output = ""
    if ldd:
        diagnostic = subprocess.run(
            [ldd, str(binary)],
            env=env,
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
        )
        ldd_output = diagnostic.stdout + diagnostic.stderr
    raise RuntimeError(
        f"portable Tesseract failed ({result.returncode}) args={args}; "
        f"stdout={result.stdout!r}; stderr={result.stderr!r}; ldd={ldd_output!r}"
    )


def verify(root: Path) -> None:
    binary = root / "usr/bin/tesseract"
    tessdata = _tessdata_dir(root)
    if not binary.is_file():
        raise RuntimeError(f"portable tesseract binary missing: {binary}")
    if tessdata is None:
        raise RuntimeError("portable tesseract Arabic/English traineddata is missing")

    env = _runtime_env(root)
    version = _run_checked_binary(binary, ["--version"], env)
    languages = _run_checked_binary(binary, ["--list-langs"], env)
    available = {line.strip() for line in languages.stdout.splitlines() if line.strip()}
    missing = {"ara", "eng"} - available
    if missing:
        raise RuntimeError(f"portable tesseract missing languages: {sorted(missing)}")
    first_line = (version.stdout or version.stderr).splitlines()[0]
    print(f"Portable Tesseract ready: {first_line}; languages=ara,eng; root={root}")


def install(root: Path) -> None:
    root.mkdir(parents=True, exist_ok=True)
    apt = shutil.which("apt-get") or shutil.which("apt")
    apt_cache = shutil.which("apt-cache")
    dpkg_deb = shutil.which("dpkg-deb")
    if not apt or not apt_cache or not dpkg_deb:
        raise RuntimeError("apt/apt-get, apt-cache and dpkg-deb are required for rootless Tesseract installation")

    packages = _dependency_closure(apt_cache)
    print(f"Portable Tesseract apt closure: {len(packages)} packages")
    with tempfile.TemporaryDirectory(prefix="manzili-tesseract-") as temp_dir:
        temp = Path(temp_dir)
        # Every package in the closure already has a candidate, so one batched apt download
        # is both safe and far faster than spawning apt once per dependency on Render builds.
        result = subprocess.run(
            [apt, "download", *packages],
            cwd=temp,
            check=False,
            capture_output=True,
            text=True,
            timeout=300,
        )
        if result.returncode != 0:
            raise RuntimeError(f"portable Tesseract apt download failed: {result.stderr}")
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
