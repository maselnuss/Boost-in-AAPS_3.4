#!/usr/bin/env python3
"""Forecast accuracy at each era's NATIVE cadence — the AID-relevant question.

Both cadences get the same look-back in minutes; the faster record simply has five times as
many samples inside it. Error is normalised by the standard deviation of the target, so a
difference in glycaemic variability between the eras cannot produce a difference in score.
1.0 means no better than predicting the mean.
"""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L

HORIZONS = [15, 30, 45, 60, 90]
print("03. FORECAST ACCURACY (normalised RMSE; 1.0 = no skill)\n")
E = L.load_eras(); out = {}
for k, e in E.items():
    X, names = L.build_features(e["ts"], e["bg"], e["nominal"])
    out[k] = dict(label=e["label"], features=names, horizons={})
    print(f"  {e['label']}")
    for H in HORIZONS:
        tgt = L.future_value(e["ts"], e["bg"], H)
        m = np.isfinite(X).all(1) & np.isfinite(tgt)
        Xm, y, g, cur = X[m], tgt[m], e["day"][m], e["bg"][m]
        p = L.cv_regress(Xm, y, g)
        allx = np.arange(len(y))
        nrmse = lambda s: float(np.sqrt(np.mean((p[s]-y[s])**2))/y[s].std())
        pers  = lambda s: float(np.sqrt(np.mean((cur[s]-y[s])**2))/y[s].std())
        pt = nrmse(allx); lo, hi = L.day_bootstrap(nrmse, g, 400)
        pp = pers(allx);  plo, phi = L.day_bootstrap(pers, g, 400)
        out[k]["horizons"][str(H)] = dict(n=int(m.sum()), model=pt, model_lo=lo, model_hi=hi,
                                          persistence=pp, pers_lo=plo, pers_hi=phi,
                                          skill_gain_pct=float(100*(pp-pt)/pp))
        print(f"    +{H:3d} min  n={int(m.sum()):6,d}  persistence {pp:.3f}  "
              f"model {L.ci_str(pt, lo, hi)}  skill gain {100*(pp-pt)/pp:4.1f}%")
    print()
print("  Comparison (5-minute era vs 1-minute era)")
print(f"    {'horizon':>8s} {'5-min nRMSE':>22s} {'1-min nRMSE':>22s} {'verdict':>28s}")
cmp = {}
for H in HORIZONS:
    a = out["e5"]["horizons"][str(H)]; b = out["e1"]["horizons"][str(H)]
    ov = L.overlaps((a["model_lo"], a["model_hi"]), (b["model_lo"], b["model_hi"]))
    better = "1-min" if b["model"] < a["model"] else "5-min"
    cmp[str(H)] = dict(overlap=bool(ov), nominally_better=better,
                       diff=float(b["model"]-a["model"]),
                       rel_diff_pct=float(100*(b["model"]-a["model"])/a["model"]))
    print(f"    {H:7d}m {L.ci_str(a['model'],a['model_lo'],a['model_hi']):>22s} "
          f"{L.ci_str(b['model'],b['model_lo'],b['model_hi']):>22s} "
          f"{('overlap; nominally '+better):>28s}")
out["comparison"] = cmp
n_ov = sum(1 for v in cmp.values() if v["overlap"])
out["all_overlap"] = bool(n_ov == len(cmp))
out["sign_alternates"] = bool(len(set(v["nominally_better"] for v in cmp.values())) > 1)
print(f"\n  intervals overlap at {n_ov}/{len(cmp)} horizons; "
      f"nominal winner {'ALTERNATES' if out['sign_alternates'] else 'is consistent'}")
L.save("03_forecast.json", out)
