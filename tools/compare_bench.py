#!/usr/bin/env python3
"""Compara resultados de benchmark: python compare_bench.py base.json otro.json [...]"""
import json, sys

def get(d, *k):
    for x in k:
        d = d.get(x) if isinstance(d, dict) else None
    return d

rows = [("FPS prom", ("frames_raf","fps"), True), ("1% low fps", ("frames_raf","low1Fps"), True),
        ("Frame time ms", ("frames_raf","avgMs"), False), ("P99 ms", ("frames_raf","p99Ms"), False),
        ("Pico ms", ("frames_raf","maxMs"), False), ("Frames >33ms", ("frames_raf","over33"), False),
        ("PSS max MB", ("memory","appPssMaxMb"), False), ("Bateria max C", ("thermal","batteryTempMaxC"), False),
        ("Carga pagina ms", ("load","pageFinishedMs"), False), ("Perdidas ctx", ("stability","contextLosses"), False),
        ("Renderer perdido", ("stability","rendererLosses"), False)]
runs = [json.load(open(p)) for p in sys.argv[1:]]
if len(runs) < 2: sys.exit(__doc__)
base = runs[0]
for r in runs[1:]:
    for k in (("url",), ("config","durationS"), ("config","targetFps"), ("config","display"), ("config","buffer"), ("device","model")):
        if get(base, *k) != get(r, *k): print(f"AVISO: difiere {'.'.join(k)}: {get(base,*k)} vs {get(r,*k)} (comparacion no valida)")
print(f"{'metrica':18}" + "".join(f"{'run'+str(i):>14}" for i in range(len(runs))))
for name, key, higher_better in rows:
    vals = [get(r, *key) for r in runs]
    line = f"{name:18}" + "".join(f"{(round(v,2) if isinstance(v,(int,float)) else '-'):>14}" for v in vals)
    print(line)
