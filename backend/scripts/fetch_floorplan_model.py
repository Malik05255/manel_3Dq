from __future__ import annotations

import hashlib
import os
import shutil
import sys
import time
from pathlib import Path

from huggingface_hub import hf_hub_download

REPO_ID = os.getenv("FLOORPLAN_MODEL_REPO", "Yytsi/floorplan-to-3d-walls")
FILENAME = os.getenv("FLOORPLAN_MODEL_FILENAME", "best.safetensors")
REVISION = os.getenv(
    "FLOORPLAN_MODEL_REVISION",
    "1367bc2cdec43fae1e7b7bdd16d0b61c506ccd7c",
)
EXPECTED_SHA256 = os.getenv(
    "FLOORPLAN_MODEL_SHA256",
    "d7f6a0fd06e2931aecfc8c4849192c5e153701578026efc78d9a6246731a8d6c",
).lower()
DEST = Path(os.getenv("FLOORPLAN_SAFETENSORS_MODEL", "/opt/manzili/models/floorplan/best.safetensors"))
MAX_ATTEMPTS = max(1, int(os.getenv("FLOORPLAN_MODEL_DOWNLOAD_ATTEMPTS", "6")))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_with_retry() -> Path:
    last_error: Exception | None = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            print(f"fetching floorplan model attempt {attempt}/{MAX_ATTEMPTS} from {REPO_ID}@{REVISION}")
            cached = hf_hub_download(
                repo_id=REPO_ID,
                filename=FILENAME,
                revision=REVISION,
            )
            return Path(cached)
        except Exception as exc:  # The hub can surface 429/5xx through different HTTP exception types.
            last_error = exc
            if attempt >= MAX_ATTEMPTS:
                break
            delay = min(60, 3 * (2 ** (attempt - 1)))
            print(f"model download failed ({type(exc).__name__}: {exc}); retrying in {delay}s", file=sys.stderr)
            time.sleep(delay)
    assert last_error is not None
    raise last_error


def main() -> int:
    DEST.parent.mkdir(parents=True, exist_ok=True)
    if DEST.exists() and sha256(DEST) == EXPECTED_SHA256:
        print(f"verified existing model: {DEST}")
        return 0

    temp = DEST.with_suffix(DEST.suffix + ".part")
    temp.unlink(missing_ok=True)
    print(f"downloading floorplan model to {DEST}")
    source = download_with_retry()
    shutil.copyfile(source, temp)

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
