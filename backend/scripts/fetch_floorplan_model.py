from __future__ import annotations

import hashlib
import os
import sys
import urllib.request
from pathlib import Path

URL = os.getenv(
    "FLOORPLAN_MODEL_URL",
    "https://huggingface.co/Yytsi/floorplan-to-3d-walls/resolve/main/best.safetensors",
)
EXPECTED_SHA256 = os.getenv(
    "FLOORPLAN_MODEL_SHA256",
    "d7f6a0fd06e2931aecfc8c4849192c5e153701578026efc78d9a6246731a8d6c",
).lower()
DEST = Path(os.getenv("FLOORPLAN_SAFETENSORS_MODEL", "/opt/manzili/models/floorplan/best.safetensors"))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    DEST.parent.mkdir(parents=True, exist_ok=True)
    if DEST.exists() and sha256(DEST) == EXPECTED_SHA256:
        print(f"verified existing model: {DEST}")
        return 0

    temp = DEST.with_suffix(DEST.suffix + ".part")
    if temp.exists():
        temp.unlink()
    print(f"downloading floorplan model to {DEST}")
    urllib.request.urlretrieve(URL, temp)
    actual = sha256(temp)
    if actual != EXPECTED_SHA256:
        temp.unlink(missing_ok=True)
        print(f"checksum mismatch: expected {EXPECTED_SHA256}, got {actual}", file=sys.stderr)
        return 2
    temp.replace(DEST)
    print(f"model verified: sha256={actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
