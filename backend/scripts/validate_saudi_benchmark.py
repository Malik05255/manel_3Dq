#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import pathlib
import sys

REQUIRED = {"id", "city", "region", "source", "asset", "reference", "floors"}


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[2]
    path = pathlib.Path(os.getenv("SAUDI_BENCHMARK_MANIFEST", root / "benchmarks/saudi-floorplans/manifest.jsonl"))
    minimum = int(os.getenv("MIN_SAUDI_BENCHMARK_CASES", "0"))
    if not path.exists():
        count = 0
        print(f"Saudi benchmark manifest not present yet: {path}")
    else:
        ids: set[str] = set()
        count = 0
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"line {number}: invalid JSON: {exc}")
            missing = REQUIRED - set(item)
            if missing:
                raise SystemExit(f"line {number}: missing {sorted(missing)}")
            if not isinstance(item["floors"], int) or item["floors"] < 1:
                raise SystemExit(f"line {number}: floors must be a positive integer")
            sample_id = str(item["id"]).strip()
            if not sample_id or sample_id in ids:
                raise SystemExit(f"line {number}: duplicate/blank id {sample_id!r}")
            ids.add(sample_id)
            count += 1
        print(f"Saudi benchmark cases: {count}")
    print("Targets: 100 -> 300 -> 500 licensed/de-identified Saudi plans")
    if count < minimum:
        print(f"Benchmark gate failed: {count} < {minimum}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
