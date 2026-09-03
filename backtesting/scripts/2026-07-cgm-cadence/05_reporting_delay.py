#!/usr/bin/env python3
"""The one thing cadence genuinely changes: how long you wait to be told.

Measured on the real records, with no decimation. A threshold crossing happens at some instant
between two reported samples. We locate it by linear interpolation between the bracketing
samples and measure the delay until the next sample the sensor actually reported. That delay
is pure scheduling — it carries no information question at all.
"""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L

THRESHOLDS = [(70.0, "falling below 70"), (54.0, "falling below 54"),
              (180.0, "rising above 180"), (250.0, "rising above 250")]
print("05. REPORTING DELAY — time from a threshold being crossed to the next reported sample\n")
E = L.load_eras(); out = {}
for k, e in E.items():
    ts, bg, day, nom = e["ts"], e["bg"], e["day"], e["nominal"]
    out[k] = dict(label=e["label"], nominal=nom, thresholds={})
    print(f"  {e['label']}")
    for thr, lab in THRESHOLDS:
        gap = np.diff(ts)/60_000.0
        valid = np.abs(gap - nom) < 0.3*nom
        if thr < 100: cross = (bg[:-1] >= thr) & (bg[1:] < thr)
        else:         cross = (bg[:-1] <= thr) & (bg[1:] > thr)
        sel = np.nonzero(cross & valid)[0]
        if len(sel) < 15:
            out[k]["thresholds"][str(thr)] = dict(n=int(len(sel)), too_few=True)
            print(f"    {lab:>18s}: {len(sel)} crossings — too few"); continue
        # where in the interval did the crossing occur?
        f = (bg[sel] - thr)/(bg[sel] - bg[sel+1])          # fraction of the interval elapsed
        f = np.clip(f, 0.0, 1.0)
        delay = (1.0 - f)*gap[sel]                          # minutes until the next report
        g = day[sel]
        med = lambda s: float(np.median(delay[s]))
        mean = lambda s: float(np.mean(delay[s]))
        mlo, mhi = L.day_bootstrap(mean, g, 400)
        out[k]["thresholds"][str(thr)] = dict(
            n=int(len(sel)), median=med(np.arange(len(delay))), mean=mean(np.arange(len(delay))),
            mean_lo=mlo, mean_hi=mhi, p90=float(np.percentile(delay, 90)),
            max=float(delay.max()), too_few=False)
        o = out[k]["thresholds"][str(thr)]
        print(f"    {lab:>18s}: {o['n']:4d} crossings  median {o['median']:.2f} min  "
              f"mean {L.ci_str(o['mean'], mlo, mhi, 2)}  p90 {o['p90']:.2f}  max {o['max']:.2f}")
    print()
print("  Difference (5-minute era minus 1-minute era), mean delay:")
cmp = {}
for thr, lab in THRESHOLDS:
    a = out["e5"]["thresholds"].get(str(thr)); b = out["e1"]["thresholds"].get(str(thr))
    if not a or not b or a.get("too_few") or b.get("too_few"): continue
    cmp[str(thr)] = float(a["mean"] - b["mean"])
    print(f"    {lab:>18s}: {a['mean']:.2f} - {b['mean']:.2f} = {a['mean']-b['mean']:+.2f} min")
out["difference_min"] = cmp
if cmp:
    v = np.array(list(cmp.values()))
    out["mean_difference"] = float(v.mean())
    print(f"\n  average across thresholds: {v.mean():+.2f} min")
    print(f"  arithmetic expectation from sample spacing alone: "
          f"{(5.0-1.0)/2:.2f} min")
L.save("05_reporting_delay.json", out)
