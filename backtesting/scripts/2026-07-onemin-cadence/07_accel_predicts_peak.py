#!/usr/bin/env python3
"""Does 1-min delta ACCELERATION predict the end of a carb climb EARLIER than 5-min?

Tim's hypothesis (2026-07-30): a reduction in the delta-acceleration rate indicates the
carb climb is ending, and minute-by-minute that is visible sooner.

Script 06 did NOT test this. It tested the FIRST CROSSING of a slope-drop threshold as an
exit rule, and compared a new 1-min trigger against the shipped 5-min deltaDeclining.
That answers "is a naive threshold a good exit rule" (no) but says nothing about whether
the INFORMATION exists at 1-min. This tests the information question directly, as a
graded prediction problem, which is the honest framing:

    at each minute inside a rise, does the peak arrive within HORIZON minutes?

and compares the SAME feature computed at 1-min resolution vs 5-min resolution, by ROC
AUC and by lead at matched false-positive rate. Matched FPR is the key: a signal that
fires earlier only because it fires more often is not earlier, it is looser.

Features (both are second derivatives; only the resolution differs):
    accel_1  = slope(last W min, 1-min data) - slope(prior W min, 1-min data)
    accel_5  = same, but computed from the 5-min BUCKETED series the engine sees
Also tested: PERSISTENCE (consecutive minutes of negative acceleration), which 06 omitted.

PROVISIONAL: one user's glucose (the only 1-min arm), detection/timing only.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '.')
from aaps_cadence_lib import deltas_vectorised, block_bootstrap_ci, verdict

DSN = "dbname=oref host=127.0.0.1 port=5432"
HORIZON_MIN = 10          # "peak is imminent" = peak within this many minutes
W = 5                     # slope window for the acceleration feature

with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    rows = cur.fetchall()
ts = np.array([int(r[0]) for r in rows], dtype=np.int64)
bg = np.array([float(r[1]) for r in rows], float)
n = len(ts)

# 5-min bucketed series the engine actually sees (re-anchored grid, verified in 06)
K = 9
targets = ts[:, None] - (np.arange(K) * 300_000).astype(np.int64)[None, :]
vals = np.floor(np.interp(targets.ravel(), ts, bg) + 0.5).reshape(n, K)
vals[targets < ts[0]] = np.nan
delta5, short5, long5 = deltas_vectorised(vals)

def slope_series(minutes, source="raw"):
    """mg/dL per 5 min over `minutes` ending at each index."""
    out = np.full(n, np.nan)
    lo = np.searchsorted(ts, ts - minutes * 60_000, side="left")
    for i in range(60, n):
        l = int(lo[i])
        if i - l < 1:
            continue
        if source == "raw":
            x = (ts[l:i+1] - ts[l]) / 60_000.0; y = bg[l:i+1]
        else:                      # 5-min view: only every 5th minute is "new" information
            m = np.arange(l, i+1)
            m = m[(ts[m] - ts[i]) % 300_000 == 0]
            if len(m) < 2: continue
            x = (ts[m] - ts[m][0]) / 60_000.0; y = bg[m]
        if x[-1] - x[0] < minutes * 0.6: continue
        sxx = float(((x - x.mean())**2).sum())
        if sxx <= 0: continue
        out[i] = float(((x - x.mean())*(y - y.mean())).sum()/sxx*5.0)
    return out

s1 = slope_series(W, "raw")
s5 = slope_series(W, "grid")
back = np.searchsorted(ts, ts - W*60_000, side="left")
def accel(s):
    a = np.full(n, np.nan); ok = (back > 0) & (back < n)
    a[ok] = s[ok] - s[back[ok]]
    return a
a1, a5 = accel(s1), accel(s5)

# persistence: consecutive minutes with negative acceleration
def run_len(a):
    r = np.zeros(n); c = 0
    for i in range(n):
        c = c + 1 if (np.isfinite(a[i]) and a[i] < 0) else 0
        r[i] = c
    return r
p1 = run_len(a1)

# episodes + label: peak within HORIZON
eps = []; i = 60
while i < n-1:
    if not (np.isfinite(delta5[i]) and delta5[i] >= 3.0): i += 1; continue
    s = i; j = i; pi, pv = i, bg[i]
    while j < n-1 and (ts[j]-ts[s]) <= 90*60_000:
        if bg[j] > pv: pv, pi = bg[j], j
        if bg[j] < pv-8.0 and (ts[j]-ts[pi]) > 5*60_000: break
        j += 1
    if pv-bg[s] >= 25.0 and pi > s: eps.append((s, pi))
    i = max(j, s+1)

idx, lab, day = [], [], []
for (s, p) in eps:
    for k in range(s, p+1):                      # only BEFORE the peak
        idx.append(k)
        lab.append(1 if (ts[p]-ts[k]) <= HORIZON_MIN*60_000 else 0)
        day.append(dt.datetime.fromtimestamp(ts[s]/1000, dt.UTC).date())
idx = np.array(idx); lab = np.array(lab); day = np.array(day)
print(f"episodes {len(eps)}, pre-peak minutes {len(idx):,}, "
      f"peak-imminent base rate {100*lab.mean():.1f}%\n")

def auc(sc, y):
    m = np.isfinite(sc)
    sc, y = sc[m], y[m]
    if y.sum() == 0 or y.sum() == len(y): return np.nan
    o = np.argsort(-sc); y = y[o]
    tp = np.cumsum(y); fp = np.cumsum(1-y)
    return float(np.trapezoid(tp/tp[-1], fp/fp[-1]))

print(f"AUC for 'peak within {HORIZON_MIN} min'  (higher = better; 0.5 = chance)")
feats = {"accel 1-min (-a)": -a1[idx], "accel 5-min (-a)": -a5[idx],
         "persistence 1-min": p1[idx], "delta5 (falling)": -delta5[idx]}
blocks_by_day = {}
for name, f in feats.items():
    days = sorted(set(day))
    blks = [np.column_stack([f[day == d], lab[day == d]]) for d in days]
    blks = [b for b in blks if len(b) > 5 and 0 < b[:,1].sum() < len(b)]
    pt, lo, hi = block_bootstrap_ci(blks, lambda bs: auc(np.concatenate([b[:,0] for b in bs]),
                                                          np.concatenate([b[:,1] for b in bs]).astype(int)))
    print(f"  {name:20s} {pt:.3f}  [{lo:.3f}, {hi:.3f}]  {verdict(lo, hi, 0.5)}")

# lead at MATCHED false-positive rate
print(f"\nLead at matched FPR (first fire per episode, minutes before peak)")
for fpr_target in (0.05, 0.10, 0.20):
    out = {}
    for name, f in (("1-min", -a1), ("5-min", -a5)):
        v = f[idx]; neg = v[(lab == 0) & np.isfinite(v)]
        thr = np.quantile(neg, 1-fpr_target)
        leads = []
        for (s, p) in eps:
            w = np.where(np.isfinite(f[s:p+1]) & (f[s:p+1] >= thr))[0]
            if len(w): leads.append((ts[p]-ts[s+w[0]])/60_000.0)
        out[name] = (np.median(leads) if leads else np.nan, len(leads))
    print(f"  FPR {fpr_target:.0%}: 1-min {out['1-min'][0]:5.1f} min (n={out['1-min'][1]})   "
          f"5-min {out['5-min'][0]:5.1f} min (n={out['5-min'][1]})   "
          f"diff {out['1-min'][0]-out['5-min'][0]:+.1f}")
print("\nPROVISIONAL — one user, detection/timing only.")
