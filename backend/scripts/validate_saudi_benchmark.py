#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import pathlib
import sys
from collections import Counter

REQUIRED = {
    "id", "city", "region", "source", "asset", "reference", "floors",
    "license", "deidentified", "split", "project_type"
}
ALLOWED_SPLITS = {"train", "validation", "test"}


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[2]
    path = pathlib.Path(os.getenv("SAUDI_BENCHMARK_MANIFEST", root / "benchmarks/saudi-floorplans/manifest.jsonl"))
    minimum = int(os.getenv("MIN_SAUDI_BENCHMARK_CASES", "0"))
    report_path = pathlib.Path(os.getenv("SAUDI_BENCHMARK_REPORT", root / "benchmark-contract-report.json"))
    ids: set[str] = set()
    regions: Counter[str] = Counter()
    cities: Counter[str] = Counter()
    splits: Counter[str] = Counter()
    project_types: Counter[str] = Counter()
    sources: Counter[str] = Counter()
    errors: list[str] = []
    count = 0

    if not path.exists():
        print(f"Saudi benchmark manifest not present yet: {path}")
    else:
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                errors.append(f"line {number}: invalid JSON: {exc}")
                continue
            missing = REQUIRED - set(item)
            if missing:
                errors.append(f"line {number}: missing {sorted(missing)}")
                continue
            sample_id = str(item["id"]).strip()
            if not sample_id or sample_id in ids:
                errors.append(f"line {number}: duplicate/blank id {sample_id!r}")
                continue
            if not isinstance(item["floors"], int) or item["floors"] < 1:
                errors.append(f"line {number}: floors must be a positive integer")
            split = str(item["split"]).strip().lower()
            if split not in ALLOWED_SPLITS:
                errors.append(f"line {number}: split must be one of {sorted(ALLOWED_SPLITS)}")
            license_text = str(item["license"]).strip()
            if not license_text or license_text.lower() in {"unknown", "none", "unverified"}:
                errors.append(f"line {number}: license/consent must be explicit")
            if item["deidentified"] is not True:
                errors.append(f"line {number}: deidentified must be true")
            if not str(item["asset"]).strip() or not str(item["reference"]).strip():
                errors.append(f"line {number}: asset/reference paths cannot be blank")
            ids.add(sample_id)
            count += 1
            regions[str(item["region"]).strip()] += 1
            cities[str(item["city"]).strip()] += 1
            splits[split] += 1
            project_types[str(item["project_type"]).strip()] += 1
            sources[str(item["source"]).strip()] += 1

    report = {
        "dataset": "Saudi Floorplan Benchmark",
        "licensed_deidentified_cases": count,
        "production_dataset_ready": count >= 100 and not errors,
        "targets": [100, 300, 500],
        "regions": dict(regions),
        "cities": dict(cities),
        "splits": dict(splits),
        "project_types": dict(project_types),
        "sources": dict(sources),
        "errors": errors,
        "claim_policy": "Never claim N real Saudi plans unless N valid manifest rows exist with explicit rights and de-identification."
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    if errors:
        print(f"Benchmark contract failed with {len(errors)} error(s).", file=sys.stderr)
        return 2
    if count < minimum:
        print(f"Benchmark gate failed: {count} < {minimum}", file=sys.stderr)
        return 3
    if count == 0:
        print("No real licensed Saudi benchmark cases are present yet; pipeline is valid but measured dataset claims remain disabled.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
