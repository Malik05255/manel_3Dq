#!/usr/bin/env python3
from __future__ import annotations
import json, os, pathlib, sys
from collections import Counter

REQUIRED={"id","city","region","source","asset","reference","floors","license","deidentified","split","project_type"}
ALLOWED_SPLITS={"train","validation","test"}

def main()->int:
    root=pathlib.Path(__file__).resolve().parents[2]
    path=pathlib.Path(os.getenv("SAUDI_BENCHMARK_MANIFEST",root/"benchmarks/saudi-floorplans/manifest.jsonl"))
    minimum=int(os.getenv("MIN_SAUDI_BENCHMARK_CASES","0"));report_path=pathlib.Path(os.getenv("SAUDI_BENCHMARK_REPORT",root/"benchmark-contract-report.json"))
    ids=set();regions=Counter();cities=Counter();splits=Counter();project_types=Counter();sources=Counter();errors=[];count=0
    if path.exists():
        for number,raw in enumerate(path.read_text(encoding="utf-8").splitlines(),1):
            line=raw.strip()
            if not line or line.startswith("#"):continue
            try:item=json.loads(line)
            except json.JSONDecodeError as exc:errors.append(f"line {number}: invalid JSON: {exc}");continue
            missing=REQUIRED-set(item)
            if missing:errors.append(f"line {number}: missing {sorted(missing)}");continue
            sid=str(item["id"]).strip()
            if not sid or sid in ids:errors.append(f"line {number}: duplicate/blank id {sid!r}");continue
            ids.add(sid);count+=1
            if not isinstance(item["floors"],int) or item["floors"]<1:errors.append(f"line {number}: floors must be a positive integer")
            split=str(item["split"]).strip().lower()
            if split not in ALLOWED_SPLITS:errors.append(f"line {number}: split must be one of {sorted(ALLOWED_SPLITS)}")
            license_text=str(item["license"]).strip()
            if not license_text or license_text.lower() in {"unknown","none","unverified"}:errors.append(f"line {number}: license/consent must be explicit")
            if item["deidentified"] is not True:errors.append(f"line {number}: deidentified must be true")
            for field in ("asset","reference"):
                rel=str(item[field]).strip();p=root/rel
                if not rel:errors.append(f"line {number}: {field} cannot be blank")
                elif not p.is_file():errors.append(f"line {number}: {field} file missing: {rel}")
            prediction=str(item.get("prediction","")).strip()
            if prediction and not (root/prediction).is_file():errors.append(f"line {number}: prediction file missing: {prediction}")
            regions[str(item["region"]).strip()]+=1;cities[str(item["city"]).strip()]+=1;splits[split]+=1;project_types[str(item["project_type"]).strip()]+=1;sources[str(item["source"]).strip()]+=1
    else:print(f"Saudi benchmark manifest not present yet: {path}")
    ready=count>=100 and splits.get("test",0)>=20 and len(regions)>=3 and len(project_types)>=3 and not errors
    report={"dataset":"Saudi Floorplan Benchmark","licensed_deidentified_cases":count,"production_dataset_ready":ready,"targets":[100,300,500],"regions":dict(regions),"cities":dict(cities),"splits":dict(splits),"project_types":dict(project_types),"sources":dict(sources),"errors":errors,"readiness_policy":{"minimum_cases":100,"minimum_test_cases":20,"minimum_regions":3,"minimum_project_types":3},"claim_policy":"Never claim N real Saudi plans unless N valid manifest rows exist with explicit rights, de-identification and present source/reference files."}
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8");print(json.dumps(report,ensure_ascii=False,indent=2))
    if errors:return 2
    if count<minimum:print(f"Benchmark gate failed: {count} < {minimum}",file=sys.stderr);return 3
    if count==0:print("No real licensed Saudi benchmark cases are present yet; measured dataset claims remain disabled.")
    return 0
if __name__=="__main__":raise SystemExit(main())
