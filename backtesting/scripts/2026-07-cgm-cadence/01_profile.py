#!/usr/bin/env python3
"""Profile the two real cadence eras: coverage, cadence stability, glycaemic distribution."""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L

print("01. RECORD PROFILE\n")
E = L.load_eras(); out = {}
for k, e in E.items():
    ts, bg, day = e["ts"], e["bg"], e["day"]
    gaps = np.diff(ts)/60_000.0
    o = dict(label=e["label"], start=e["start"], end=e["end"], nominal=e["nominal"],
             n=int(len(ts)), days=float((ts[-1]-ts[0])/86_400_000),
             n_days=int(len(set(day))),
             median_gap=float(np.median(gaps)),
             pct_on_cadence=float(100*np.mean(np.abs(gaps-e["nominal"]) < 0.3*e["nominal"])),
             coverage_pct=float(100*len(ts)/(((ts[-1]-ts[0])/60_000.0)/e["nominal"])),
             mean=float(bg.mean()), sd=float(bg.std()), cv=float(100*bg.std()/bg.mean()),
             tir=float(100*np.mean((bg>=70)&(bg<=180))),
             ting=float(100*np.mean((bg>=63)&(bg<=140))),
             tbr70=float(100*np.mean(bg<70)), tbr54=float(100*np.mean(bg<54)),
             tar180=float(100*np.mean(bg>180)), tar250=float(100*np.mean(bg>250)),
             integer_pct=float(100*np.mean(bg == np.round(bg))))
    out[k] = o
    print(f"  {o['label']}: {o['n']:,} readings over {o['days']:.1f} d ({o['n_days']} distinct days)")
    print(f"    median gap {o['median_gap']:.2f} min, {o['pct_on_cadence']:.1f}% on cadence, "
          f"coverage {o['coverage_pct']:.1f}%")
    print(f"    mean {o['mean']:.1f}  SD {o['sd']:.1f}  CV {o['cv']:.1f}%  TIR {o['tir']:.1f}%  "
          f"TING {o['ting']:.1f}%")
    print(f"    <70 {o['tbr70']:.2f}%  <54 {o['tbr54']:.2f}%  >180 {o['tar180']:.2f}%  "
          f">250 {o['tar250']:.2f}%\n")
out["comparability"] = dict(
    cv_ratio_squared=float((out["e1"]["cv"]/out["e5"]["cv"])**2),
    tbr70_ratio=float(out["e1"]["tbr70"]/out["e5"]["tbr70"]),
    tar180_ratio=float(out["e1"]["tar180"]/max(out["e5"]["tar180"], 1e-9)))
print(f"  The eras are NOT matched: (CV1/CV5)^2 = {out['comparability']['cv_ratio_squared']:.3f}, "
      f"TBR<70 ratio {out['comparability']['tbr70_ratio']:.2f}x, "
      f">180 ratio {out['comparability']['tar180_ratio']:.2f}x")
print("  Every downstream metric is therefore scale-free or base-rate-normalised.")
L.save("01_profile.json", out)
