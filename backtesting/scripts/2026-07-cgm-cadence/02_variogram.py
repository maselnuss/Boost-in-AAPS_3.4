#!/usr/bin/env python3
"""Variogram of both real eras: do they differ by anything other than a scale factor, is
either noisier, and does anything new appear below five minutes?"""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L

FINE = [1,2,3,4,5,6,8,10,12,15,20,25,30,40,50,60,90,120]
SHARED = [5,10,15,20,25,30,40,50,60,90,120]
NOISE_SD_LIT = 3.19          # Vettoretti 2019, factory-calibrated sensor

print("02. VARIOGRAM  D(tau) = E[(x(t+tau) - x(t))^2]\n")
E = L.load_eras(); out = dict(vario={}, ratio=[], slopes={}, noise={})
for k, e in E.items():
    tol = 0.6 if e["nominal"] < 2 else 1.2
    out["vario"][k] = {str(L_): v for L_, v in
                       L.variogram(e["ts"], e["bg"], e["day"], FINE, tol).items()}
    print(f"  {e['label']}: variogram at {len(out['vario'][k])} lags")

print("\n  A. RATIO across every lag both sensors can see")
print(f"     {'lag':>5s} {'5-min D':>10s} {'1-min D':>10s} {'ratio':>8s}")
for L_ in SHARED:
    a = out["vario"]["e5"].get(str(L_)); b = out["vario"]["e1"].get(str(L_))
    if not a or not b: continue
    r = b["D"]/a["D"]; out["ratio"].append(dict(lag=L_, d5=a["D"], d1=b["D"], ratio=r))
    print(f"     {L_:4d}m {a['D']:10.1f} {b['D']:10.1f} {r:8.3f}")
rr = np.array([x["ratio"] for x in out["ratio"]])
out["ratio_summary"] = dict(mean=float(rr.mean()), min=float(rr.min()), max=float(rr.max()),
                            spread_pct=float(100*(rr.max()-rr.min())/rr.mean()))
print(f"     mean {rr.mean():.3f}, range {rr.min():.3f}-{rr.max():.3f}, "
      f"spread {out['ratio_summary']['spread_pct']:.1f}% of the mean")

print("\n  B. NOISE FLOOR — a sensor adding independent noise lifts D at EVERY lag")
floor = 2*NOISE_SD_LIT**2
out["noise"]["lit_floor"] = float(floor)
for L_ in [1,2,3,4,5,10]:
    v = out["vario"]["e1"].get(str(L_))
    if not v: continue
    out["noise"][f"e1_D{L_}"] = v
    print(f"     1-min era D({L_:2d} min) = {v['D']:7.2f} [{v['lo']:.2f}, {v['hi']:.2f}]  "
          f"= {100*v['D']/floor:5.1f}% of the {floor:.1f} floor implied by a "
          f"{NOISE_SD_LIT} mg/dL noise SD")
d1 = out["vario"]["e1"]["1"]["D"]
out["noise"]["pct_of_lit_floor_at_1min"] = float(100*d1/floor)
out["noise"]["implied_white_sd_at_1min"] = float(np.sqrt(d1/2))

print("\n  C. LOG-LOG SLOPE — 2 = smooth signal, 0 = white noise")
for k, e in E.items():
    tol = 0.6 if e["nominal"] < 2 else 1.2
    bands = [(1,5),(5,20),(20,60)] if e["nominal"] < 2 else [(5,20),(20,60)]
    out["slopes"][k] = {}
    for lo, hi in bands:
        s = L.loglog_slope(e["ts"], e["bg"], e["day"], lo, hi, tol)
        if not s: continue
        out["slopes"][k][f"{lo}-{hi}"] = s
        print(f"     {e['label']:>14s} {lo:3d}-{hi:3d} min : {L.ci_str(s['slope'], s['lo'], s['hi'], 2)}")
for band in ("5-20", "20-60"):
    a = out["slopes"]["e5"].get(band); b = out["slopes"]["e1"].get(band)
    if a and b:
        ov = L.overlaps((a["lo"], a["hi"]), (b["lo"], b["hi"]))
        out["slopes"][f"agree_{band}"] = bool(ov)
        print(f"     shared band {band} min: intervals {'OVERLAP' if ov else 'DO NOT overlap'}")
sub = out["slopes"]["e1"].get("1-5"); above = out["slopes"]["e1"].get("5-20")
if sub and above:
    ov = L.overlaps((sub["lo"], sub["hi"]), (above["lo"], above["hi"]))
    out["slopes"]["new_regime_below_5min"] = bool(not ov)
    print(f"     1-min era, below 5 min vs above: {'NO BREAK' if ov else 'BREAK'} "
          f"-> {'no new regime' if ov else 'new regime'}")
L.save("02_variogram.json", out)
