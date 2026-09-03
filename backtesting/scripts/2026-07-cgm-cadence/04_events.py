#!/usr/bin/env python3
"""Predicting HIGHS and LOWS at each era's native cadence.

Base rates differ substantially between the eras, so LIFT (precision in the top risk decile
divided by that era's own base rate) is the metric to compare. AUC is reported alongside with
its base-rate caveat.
"""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L
from sklearn.metrics import roc_auc_score

TASKS = [
    dict(name="low <70",   thr=70.0,  kind="min", eligible="above"),
    dict(name="low <54",   thr=54.0,  kind="min", eligible="above"),
    dict(name="high >180", thr=180.0, kind="max", eligible="below"),
    dict(name="high >250", thr=250.0, kind="max", eligible="below"),
]
HORIZONS = [15, 20, 30, 45, 60]

print("04. PREDICTING HIGHS AND LOWS\n")
E = L.load_eras(); out = dict(tasks={})
FE = {k: L.build_features(e["ts"], e["bg"], e["nominal"])[0] for k, e in E.items()}
for t in TASKS:
    out["tasks"][t["name"]] = {}
    print(f"  === {t['name']} ===")
    print(f"    {'H':>5s} {'era':>13s} {'base':>7s} {'n':>8s} {'AUC':>24s} {'lift':>22s}")
    for H in HORIZONS:
        row = {}
        for k, e in E.items():
            X = FE[k]
            ext = L.future_extreme(e["ts"], e["bg"], H, t["kind"])
            lab = np.where(np.isfinite(ext),
                           (ext < t["thr"]) if t["kind"] == "min" else (ext > t["thr"]), np.nan)
            elig = (e["bg"] >= t["thr"]) if t["eligible"] == "above" else (e["bg"] <= t["thr"])
            m = np.isfinite(X).all(1) & np.isfinite(lab) & elig
            y = lab[m].astype(float); g = e["day"][m]
            if len(y) < 500 or len(np.unique(y)) < 2 or y.mean() < 0.002:
                row[k] = dict(base=float(y.mean()) if len(y) else float("nan"),
                              n=int(m.sum()), too_rare=True)
                print(f"    {H:4d}m {e['label']:>13s} {100*(y.mean() if len(y) else 0):6.2f}% "
                      f"{int(m.sum()):8,d} {'too rare to model':>24s}")
                continue
            p = L.cv_classify(X[m], y, g)
            ok = np.isfinite(p); idx = np.nonzero(ok)[0]
            fa = lambda s: float(roc_auc_score(y[s], p[s])) if len(np.unique(y[s])) > 1 else np.nan
            fl = lambda s: L.lift_at_decile(y, p, s)
            auc = fa(idx); alo, ahi = L.day_bootstrap(fa, g[ok], 400)
            lft = fl(idx); llo, lhi = L.day_bootstrap(fl, g[ok], 400)
            row[k] = dict(base=float(y.mean()), n=int(len(y)), auc=auc, auc_lo=alo, auc_hi=ahi,
                          lift=lft, lift_lo=llo, lift_hi=lhi, too_rare=False)
            print(f"    {H:4d}m {e['label']:>13s} {100*y.mean():6.2f}% {len(y):8,d} "
                  f"{L.ci_str(auc, alo, ahi, 4):>24s} {L.ci_str(lft, llo, lhi, 2)+'x':>22s}")
        if all(not v.get("too_rare") for v in row.values()) and len(row) == 2:
            a, b = row["e5"], row["e1"]
            row["compare"] = dict(
                lift_overlap=bool(L.overlaps((a["lift_lo"], a["lift_hi"]), (b["lift_lo"], b["lift_hi"]))),
                auc_overlap=bool(L.overlaps((a["auc_lo"], a["auc_hi"]), (b["auc_lo"], b["auc_hi"]))),
                auc_gap=float(b["auc"]-a["auc"]), lift_gap=float(b["lift"]-a["lift"]),
                base_ratio=float(b["base"]/a["base"]) if a["base"] > 0 else float("nan"))
        out["tasks"][t["name"]][str(H)] = row
    print()

print("  Does the AUC gap behave like a cadence effect or like an era effect?")
print("  (a cadence benefit should be LARGEST at short horizons and wash out; an era effect need not)")
for t in TASKS:
    gaps = [(H, out["tasks"][t["name"]][str(H)].get("compare", {}).get("auc_gap"))
            for H in HORIZONS]
    gaps = [(h, g) for h, g in gaps if g is not None]
    if len(gaps) < 3: continue
    first, last = gaps[0][1], gaps[-1][1]
    if abs(last) > abs(first)*1.2:
        trend = ("favours 1-min more strongly at long horizons" if last > 0
                 else "favours 5-min more strongly at long horizons")
    elif abs(last) < abs(first)*0.8:
        trend = "narrows with horizon"
    else:
        trend = "roughly flat with horizon"
    out["tasks"][t["name"]]["auc_gap_trend"] = trend
    print(f"    {t['name']:>10s}: " + ", ".join(f"{h}m {g:+.4f}" for h, g in gaps) + f"  -> {trend}")
L.save("04_events.json", out)
