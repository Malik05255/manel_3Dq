#!/usr/bin/env python3
import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
from app.reader_training_dataset import materialize

parser = argparse.ArgumentParser()
parser.add_argument("--inbox", type=pathlib.Path, required=True)
parser.add_argument("--output", type=pathlib.Path, required=True)
args = parser.parse_args()
report = materialize(args.inbox, args.output)
print(json.dumps(report, ensure_ascii=False, indent=2))
raise SystemExit(0 if report.get("trainable_cases", 0) else 3)
