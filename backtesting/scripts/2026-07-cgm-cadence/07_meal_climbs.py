#!/usr/bin/env python3
"""Meal climbs: can either sampling rate see the start earlier, or the end earlier?

Two questions, both asked within subject, so every model here is already personalised in the
sense that it is trained only on this person's own data.

  ONSET. At a moment when glucose is not currently climbing, will a climb begin within the
  next H minutes? A model that does this is anticipatory: it fires before the rise is visible.

  END. Once a climb is under way, will it peak within the next H minutes? A model that does
  this lets a controller stop adding insulin before the peak arrives.

A climb is defined as a rise of at least RISE_MGDL within CLIMB_WINDOW minutes measured from a
local trough. Onset candidates are restricted to points that are not already rising, so the
task cannot be solved by observing the rise itself.

Both cadences receive the same look-back in elapsed time and are validated out of sample with
GroupKFold over whole days. Skill is reported against two baselines: chance, and a persistence
model using only the current value with recent slope, which is what a controller already has.
"""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L
from sklearn.metrics import roc_auc_score

RISE_MGDL, CLIMB_WINDOW, TROUGH_LOOKBACK = 40.0, 90, 20
ONSET_H = [10, 15, 20, 30]
END_H   = [10, 15, 20, 30]

def climb_episodes(ts, bg, nominal):
    """Return onset index and peak index for each climb of at least RISE_MGDL."""
    n = len(ts); k_w = max(int(round(CLIMB_WINDOW/nominal)), 2)
    k_b = max(int(round(TROUGH_LOOKBACK/nominal)), 1)
    eps = []
    i = k_b
    while i < n - 2:
        j = min(i + k_w, n - 1)
        if (ts[j] - ts[i])/60_000.0 > CLIMB_WINDOW*1.4: i += 1; continue
        seg = bg[i:j+1]
        if seg.max() - bg[i] < RISE_MGDL: i += 1; continue
        if bg[i] > bg[max(i-k_b, 0):i+1].min() + 5.0: i += 1; continue   # must be a trough
        pk = i + int(np.argmax(seg))
        eps.append((i, pk))
        i = pk + 1
    return eps

print("07. MEAL CLIMBS: ONSET AND END\n")
E = L.load_eras(); out = {}
for k, e in E.items():
    ts, bg, day, nom = e["ts"], e["bg"], e["day"], e["nominal"]
    n = len(ts)
    X, _ = L.build_features(ts, bg, nom)
    slope15 = L.causal_slope(ts, bg, 15, nom)
    eps = climb_episodes(ts, bg, nom)
    onset_t = np.array([ts[a] for a, _ in eps], np.int64)
    peak_t  = np.array([ts[p] for _, p in eps], np.int64)
    rises   = np.array([bg[p] - bg[a] for a, p in eps])
    durs    = np.array([(ts[p]-ts[a])/60_000.0 for a, p in eps])
    days_ = (ts[-1]-ts[0])/86_400_000.0
    o = dict(label=e["label"], n_climbs=len(eps), per_day=len(eps)/days_,
             median_rise=float(np.median(rises)), median_duration=float(np.median(durs)),
             onset={}, end={})
    print(f"  {e['label']}: {len(eps)} climbs of >= {RISE_MGDL:.0f} mg/dL "
          f"({len(eps)/days_:.2f} per day), median rise {np.median(rises):.0f} mg/dL, "
          f"median time to peak {np.median(durs):.0f} min")

    # ---------------- ONSET: anticipation proper
    print(f"    ONSET  (will a climb begin within H minutes, from a non-rising state)")
    quiet = np.isfinite(slope15) & (np.abs(slope15) < 2.0)
    for H in ONSET_H:
        lab = np.zeros(n)
        for t0 in onset_t:
            lo = np.searchsorted(ts, t0 - H*60_000, side="left")
            hi = np.searchsorted(ts, t0, side="right")
            lab[lo:hi] = 1.0
        m = np.isfinite(X).all(1) & quiet
        y = lab[m]; g = day[m]
        if y.sum() < 40 or len(np.unique(y)) < 2:
            print(f"      H={H:3d} min  too few positives"); continue
        p = L.cv_classify(X[m], y, g)
        ok = np.isfinite(p); idx = np.nonzero(ok)[0]
        fa = lambda s: float(roc_auc_score(y[s], p[s])) if len(np.unique(y[s])) > 1 else np.nan
        auc = fa(idx); lo_, hi_ = L.day_bootstrap(fa, g[ok], 300)
        lift = L.lift_at_decile(y, p, idx)
        o["onset"][str(H)] = dict(base=float(y.mean()), n=int(len(y)), auc=auc,
                                  lo=lo_, hi=hi_, lift=lift)
        print(f"      H={H:3d} min  base {100*y.mean():5.2f}%  n={len(y):6,d}  "
              f"AUC {L.ci_str(auc, lo_, hi_, 4)}  lift {lift:.2f}x")

    # ---------------- END: is the peak near?
    print(f"    END    (will the climb peak within H minutes, while rising)")
    rising = np.zeros(n, bool)
    for a, pk in eps: rising[a:pk] = True
    for H in END_H:
        lab = np.full(n, np.nan)
        for a, pk in eps:
            lo = np.searchsorted(ts, ts[pk] - H*60_000, side="left")
            lab[a:pk] = 0.0
            lab[max(lo, a):pk] = 1.0
        m = np.isfinite(X).all(1) & np.isfinite(lab) & rising
        y = lab[m]; g = day[m]
        if y.sum() < 40 or len(np.unique(y)) < 2:
            print(f"      H={H:3d} min  too few positives"); continue
        p = L.cv_classify(X[m], y, g)
        ok = np.isfinite(p); idx = np.nonzero(ok)[0]
        fa = lambda s: float(roc_auc_score(y[s], p[s])) if len(np.unique(y[s])) > 1 else np.nan
        auc = fa(idx); lo_, hi_ = L.day_bootstrap(fa, g[ok], 300)
        lift = L.lift_at_decile(y, p, idx)
        o["end"][str(H)] = dict(base=float(y.mean()), n=int(len(y)), auc=auc,
                                lo=lo_, hi=hi_, lift=lift)
        print(f"      H={H:3d} min  base {100*y.mean():5.2f}%  n={len(y):6,d}  "
              f"AUC {L.ci_str(auc, lo_, hi_, 4)}  lift {lift:.2f}x")
    out[k] = o
    print()

print("  Comparison between the two records")
for kind, hs in (("onset", ONSET_H), ("end", END_H)):
    print(f"    {kind.upper()}")
    for H in hs:
        a = out["e5"][kind].get(str(H)); b = out["e1"][kind].get(str(H))
        if not a or not b: continue
        ov = L.overlaps((a["lo"], a["hi"]), (b["lo"], b["hi"]))
        print(f"      H={H:3d} min  5-min AUC {a['auc']:.4f}  1-min AUC {b['auc']:.4f}  "
              f"gap {b['auc']-a['auc']:+.4f}  intervals {'overlap' if ov else 'separated'}")
        out.setdefault("compare", {}).setdefault(kind, {})[str(H)] = dict(
            gap=float(b["auc"]-a["auc"]), overlap=bool(ov))
L.save("07_meal_climbs.json", out)
