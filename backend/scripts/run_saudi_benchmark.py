#!/usr/bin/env python3
from __future__ import annotations
import json, pathlib, statistics
from collections import Counter, defaultdict

ROOT=pathlib.Path(__file__).resolve().parents[2]
MANIFEST=ROOT/'benchmarks/saudi-floorplans/manifest.jsonl'
OUT=ROOT/'saudi-benchmark-measurement-report.json'

def rows():
    if not MANIFEST.exists(): return []
    out=[]
    for raw in MANIFEST.read_text(encoding='utf-8').splitlines():
        line=raw.strip()
        if line and not line.startswith('#'): out.append(json.loads(line))
    return out

def load(path):
    p=ROOT/path
    return json.loads(p.read_text(encoding='utf-8')) if p.exists() else None

def list_items(plan,key):
    items=plan.get(key,[]) if isinstance(plan,dict) else []
    if plan.get('floors'):
        items=[]
        for floor in plan.get('floors',[]): items.extend(floor.get(key,[]))
    return items

def labels(plan,key):
    vals=[]
    for item in list_items(plan,key):
        if key=='rooms': vals.append(str(item.get('type') or item.get('name') or '').strip().lower())
        else: vals.append(str(item.get('type') or item.get('kind') or key).strip().lower())
    return [x for x in vals if x]

def f1(a,b):
    ca,cb=Counter(a),Counter(b);tp=sum((ca&cb).values());fp=max(0,sum(ca.values())-tp);fn=max(0,sum(cb.values())-tp)
    if tp==fp==fn==0:return 1.0
    return 0.0 if tp==0 else 2*tp/(2*tp+fp+fn)

def evaluate(pred,ref):
    room=f1(labels(pred,'rooms'),labels(ref,'rooms'))
    opening=f1(labels(pred,'openings'),labels(ref,'openings'))
    walls_pred=len(list_items(pred,'walls'));walls_ref=len(list_items(ref,'walls'))
    wall=1.0 if walls_pred==walls_ref==0 else max(0.0,1.0-abs(walls_pred-walls_ref)/max(1,walls_ref))
    dims_pred=len(pred.get('dimensions',[]));dims_ref=len(ref.get('dimensions',[]));dims=1.0 if dims_pred==dims_ref==0 else max(0.0,1.0-abs(dims_pred-dims_ref)/max(1,dims_ref))
    overall=.40*room+.25*wall+.20*opening+.15*dims
    return {'room_f1':room,'wall_count_score':wall,'opening_f1':opening,'dimension_count_score':dims,'overall':overall}

def main():
    cases=rows();measured=[];skipped=[]
    by_split=defaultdict(list);by_region=defaultdict(list);by_type=defaultdict(list)
    for item in cases:
        prediction=item.get('prediction');reference=item.get('reference')
        if not prediction or not reference: skipped.append({'id':item.get('id'),'reason':'prediction/reference not both supplied'});continue
        pred,ref=load(prediction),load(reference)
        if pred is None or ref is None: skipped.append({'id':item.get('id'),'reason':'prediction/reference file missing'});continue
        m=evaluate(pred,ref);record={'id':item['id'],**m};measured.append(record)
        by_split[item.get('split','unknown')].append(m['overall']);by_region[item.get('region','unknown')].append(m['overall']);by_type[item.get('project_type','unknown')].append(m['overall'])
    def means(groups):return {k:round(statistics.mean(v),4) for k,v in groups.items() if v}
    report={
        'dataset':'Saudi Floorplan Benchmark','licensed_manifest_cases':len(cases),'measured_cases':len(measured),
        'mean_overall':round(statistics.mean([x['overall'] for x in measured]),4) if measured else None,
        'by_split':means(by_split),'by_region':means(by_region),'by_project_type':means(by_type),
        'cases':measured,'skipped':skipped,
        'claims_enabled':len(measured)>=100 and len(by_split.get('test',[]))>=20,
        'claim_policy':'Measured accuracy claims require licensed/deidentified manifest rows plus real prediction and reference files; zero rows means zero measured cases.'
    }
    OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False,indent=2))
    return 0
if __name__=='__main__':raise SystemExit(main())
