#!/usr/bin/env python3
from __future__ import annotations

import base64,json,math,os,pathlib,sys
from typing import Any

ROOT=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"backend"))
from app.parser import parse_floorplan


def f1(tp:int,fp:int,fn:int)->float:
    if tp==0:return 1.0 if fp==0 and fn==0 else 0.0
    p=tp/(tp+fp);r=tp/(tp+fn);return 2*p*r/(p+r)

def point(o:dict[str,Any])->tuple[float,float]:return float(o.get("x",0)),float(o.get("y",0))
def wall_dist(a:dict[str,Any],b:dict[str,Any])->float:
    sa=a["start"];ea=a["end"];sb=b["start"];eb=b["end"]
    d=lambda p,q:math.hypot(float(p["x"])-float(q["x"]),float(p["y"])-float(q["y"]))
    return min(d(sa,sb)+d(ea,eb),d(sa,eb)+d(ea,sb))/2

def greedy(expected:list[dict],actual:list[dict],distance,limit:float,type_key:str|None=None)->float:
    remaining=list(actual);tp=0
    for target in expected:
        pool=[(i,x) for i,x in enumerate(remaining) if type_key is None or str(x.get(type_key,""))==str(target.get(type_key,""))]
        if not pool:continue
        i,best=min(pool,key=lambda pair:distance(target,pair[1]))
        if distance(target,best)<=limit:tp+=1;remaining.pop(i)
    return f1(tp,len(actual)-tp,len(expected)-tp)

def opening_dist(a:dict,b:dict)->float:
    ax,ay=point(a);bx,by=point(b);return math.hypot(ax-bx,ay-by)+abs(float(a.get("width",0))-float(b.get("width",0)))*.35

def main()->int:
    manifest=pathlib.Path(os.getenv("SAUDI_BENCHMARK_MANIFEST",ROOT/"benchmarks/saudi-floorplans/manifest.jsonl"))
    out=pathlib.Path(os.getenv("SAUDI_BENCHMARK_EXEC_REPORT",ROOT/"benchmark-execution-report.json"))
    rows=[];errors=[]
    if manifest.exists():
        for n,raw in enumerate(manifest.read_text(encoding="utf-8").splitlines(),1):
            s=raw.strip()
            if not s or s.startswith("#"):continue
            try:rows.append(json.loads(s))
            except Exception as exc:errors.append(f"line {n}: {exc}")
    results=[]
    for row in rows:
        asset=ROOT/str(row.get("asset",""));ref=ROOT/str(row.get("reference",""))
        if not asset.is_file() or not ref.is_file():errors.append(f"{row.get('id')}: asset/reference missing");continue
        try:
            expected=json.loads(ref.read_text(encoding="utf-8"))
            encoded=base64.b64encode(asset.read_bytes()).decode("ascii")
            actual=parse_floorplan(encoded)
            walls=greedy(expected.get("walls",[]),actual.get("walls",[]),wall_dist,7.0)
            openings=greedy(expected.get("openings",[]),actual.get("openings",[]),opening_dist,6.5,"type")
            overall=round(walls*.65+openings*.35,5)
            results.append({"id":row["id"],"city":row["city"],"split":row["split"],"model_used":actual.get("model_used"),"wall_f1":round(walls,5),"opening_f1":round(openings,5),"overall":overall})
        except Exception as exc:errors.append(f"{row.get('id')}: {type(exc).__name__}: {exc}")
    report={"licensed_cases_in_manifest":len(rows),"executed_cases":len(results),"mean_overall":round(sum(r["overall"] for r in results)/len(results),5) if results else 0.0,"results":results,"errors":errors,"claim_policy":"Measured claims require executed licensed/deidentified cases; zero remains zero."}
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8");print(json.dumps(report,ensure_ascii=False,indent=2))
    if errors:return 2
    if not rows:print("No licensed Saudi cases present; executable pipeline is ready and measured claims stay disabled.")
    return 0

if __name__=="__main__":raise SystemExit(main())
