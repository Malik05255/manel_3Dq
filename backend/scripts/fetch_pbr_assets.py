#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

MATERIALS = {
    "plaster",
    "concrete",
    "wood",
    "metal",
    "limestone",
    "sand",
    "paving",
    "soil",
}
MAPS = {"basecolor", "roughness", "normal"}
EXTENSIONS = {"jpg", "jpeg", "png", "webp"}
FILE_RE = re.compile(r"^([a-z0-9_-]+)_(basecolor|roughness|normal)\.(jpg|jpeg|png|webp)$")
MAX_ASSET_BYTES = 64 * 1024 * 1024


def _https(url: str) -> bool:
    parsed = urlparse(url)
    return parsed.scheme == "https" and bool(parsed.netloc)


def _read_url(url: str, max_bytes: int) -> bytes:
    if not _https(url):
        raise ValueError(f"Only HTTPS asset URLs are allowed: {url}")
    request = urllib.request.Request(url, headers={"User-Agent": "Manzili-HAI-PBR/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        length = response.headers.get("Content-Length")
        if length and int(length) > max_bytes:
            raise ValueError(f"Asset exceeds size limit: {url}")
        payload = response.read(max_bytes + 1)
    if len(payload) > max_bytes:
        raise ValueError(f"Asset exceeds size limit: {url}")
    return payload


def validate_entry(entry: dict) -> tuple[str, str, str, str]:
    filename = str(entry.get("filename") or "").strip()
    match = FILE_RE.fullmatch(filename)
    if not match:
        raise ValueError(f"Invalid PBR filename: {filename}")
    material, map_name, extension = match.groups()
    if material not in MATERIALS or map_name not in MAPS or extension not in EXTENSIONS:
        raise ValueError(f"Unsupported PBR slot: {filename}")

    url = str(entry.get("url") or "").strip()
    if not _https(url):
        raise ValueError(f"Only HTTPS asset URLs are allowed: {url}")

    digest = str(entry.get("sha256") or "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError(f"sha256 is required for {filename}")

    license_info = entry.get("license") or {}
    if license_info.get("redistributionAllowed") is not True:
        raise ValueError(f"redistributionAllowed must be true for {filename}")
    if not str(license_info.get("source") or "").strip():
        raise ValueError(f"license source is required for {filename}")
    if not str(license_info.get("name") or "").strip():
        raise ValueError(f"license name is required for {filename}")

    return filename, url, digest, f"{material}:{map_name}"


def install_bundle(manifest: dict, destination: Path, strict: bool = False) -> dict:
    entries = manifest.get("assets") or []
    if not isinstance(entries, list):
        raise ValueError("manifest.assets must be a list")

    destination.mkdir(parents=True, exist_ok=True)
    installed: list[str] = []
    slots: set[str] = set()

    for raw in entries:
        if not isinstance(raw, dict):
            raise ValueError("each asset entry must be an object")
        filename, url, expected_sha, slot = validate_entry(raw)
        if slot in slots:
            raise ValueError(f"duplicate PBR slot: {slot}")
        slots.add(slot)

        payload = _read_url(url, MAX_ASSET_BYTES)
        actual_sha = hashlib.sha256(payload).hexdigest()
        if actual_sha != expected_sha:
            raise ValueError(f"sha256 mismatch for {filename}")

        target = destination / filename
        with tempfile.NamedTemporaryFile(dir=destination, prefix=f".{filename}.", delete=False) as handle:
            handle.write(payload)
            temp_path = Path(handle.name)
        temp_path.replace(target)
        installed.append(filename)

    required = {f"{material}:{map_name}" for material in MATERIALS for map_name in MAPS}
    missing = sorted(required - slots)
    if strict and missing:
        raise ValueError("PBR bundle is incomplete: " + ", ".join(missing))

    audit = {
        "schemaVersion": 1,
        "installed": sorted(installed),
        "installedSlots": sorted(slots),
        "missingSlots": missing,
        "complete": not missing,
        "sourceManifest": manifest.get("source") or None,
    }
    (destination / "installed-assets.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return audit


def main() -> int:
    parser = argparse.ArgumentParser(description="Install licensed PBR maps for the Manzili Blender worker")
    parser.add_argument("--manifest-url", default=os.getenv("PBR_BUNDLE_MANIFEST_URL", ""))
    parser.add_argument("--destination", default=os.getenv("PBR_ASSET_DIR", "/srv/manzili/assets/pbr"))
    parser.add_argument("--strict", action="store_true", default=os.getenv("REQUIRE_COMPLETE_PBR", "").lower() in {"1", "true", "yes", "on"})
    args = parser.parse_args()

    if not args.manifest_url:
        print("No PBR_BUNDLE_MANIFEST_URL configured; keeping packaged/fallback materials.")
        return 0

    payload = _read_url(args.manifest_url, 2 * 1024 * 1024)
    manifest = json.loads(payload.decode("utf-8"))
    audit = install_bundle(manifest, Path(args.destination), strict=args.strict)
    print(json.dumps(audit, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
