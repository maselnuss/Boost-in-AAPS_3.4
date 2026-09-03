#!/usr/bin/env python3
"""What is a CGM actually used for, and which of those uses benefit from a faster cadence?

Real records only — the subject's 83-day 5-minute era and 61-day 1-minute era, each analysed
at its OWN native cadence. Nothing decimated.

The uses divide into four kinds:

  (a) DISPLAY — the current value and a trend arrow.
  (b) RETROSPECTIVE METRICS — mean, CV, time in range, GMI.
  (c) REACTIVE ALARMS — glucose is below 70 now.
  (d) PREDICTIVE USES — glucose will be below 70 in twenty minutes; an AID deciding a dose
      against a forecast.

(a) and (c) are answered by the newest sample, so a faster feed delivers them sooner but not
better; that is a scheduling question. (b) is an average over thousands of samples. (d) is the
only category where a faster feed could plausibly be more ACCURATE, and it is what this script
tests.

THE CONFOUND AND THE FIX. The two eras differ in glycaemic variability, which changes the
difficulty of any prediction task. Every metric here is therefore scale-free:

  - normalised RMSE = RMSE / SD(target). A value of 1.0 means "no better than guessing the
    mean"; persistence and model skill are both reported this way.
  - AUC, which is rank-based, plus lift over each era's own base rate.

Each era is validated out of sample against itself with GroupKFold over whole days, so no day
contributes to both training and test.

FAIR HISTORY. Both cadences are given the same look-back in MINUTES. The 1-minute record simply
has five times as many samples inside that window. If the extra samples carry information, the
1-minute era must score better on its own task than the 5-minute era does on its own.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.metrics import roc_auc_score
from sklearn.preprocessing import StandardScaler

DSN = "dbname=oref host=127.0.0.1 port=5432"
def load(a, b):
    with psycopg2.connect(DSN) as c, c.cursor() as cur:
        cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                    "where user_id='I' and cgm_mgdl is not null and ts_utc>=%s and ts_utc<%s "
                    "order by ts_utc", (a, b))
        r = cur.fetchall()
    ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
    day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
    return ts, bg, day

ERAS = [("5-min era (83 d)", *load('2026-03-01', '2026-05-23'), 5.0),
        ("1-min era (61 d)", *load('2026-05-23', '2026-07-31'), 1.0)]

def features(ts, bg, nom):
    """current value, lagged values and slopes over FIXED TIME windows (minutes)."""
    n = len(ts)
    cols, names = [bg], ["bg"]
    for back in (5, 10, 15, 30, 45):
        j = np.searchsorted(ts, ts - back*60_000)
        v = np.where((ts - ts[j])/60_000.0 <= back*1.4, bg[j], np.nan)
        cols.append(bg - v); names.append(f"delta{back}")
    for win in (15, 30, 45):
        lo = np.searchsorted(ts, ts - win*60_000, side="left")
        sl = np.full(n, np.nan)
        for i in range(n):
            idx = np.arange(int(lo[i]), i+1)
            if len(idx) < 2: continue
            if (ts[i]-ts[idx[0]])/60_000.0 < win*0.6: continue
            x = (ts[idx]-ts[idx[0]])/60_000.0; y = bg[idx]
            sxx = float(((x-x.mean())**2).sum())
            if sxx > 0: sl[i] = float(((x-x.mean())*(y-y.mean())).sum()/sxx*5.0)
        cols.append(sl); names.append(f"slope{win}")
    return np.column_stack(cols), names

print("Which CGM uses could a faster cadence make MORE ACCURATE (not merely sooner)?\n")
print("   display current value / trend .......... no: answered by the newest sample")
print("   retrospective metrics (TIR, CV, GMI) ... no: an average over thousands of samples")
print("   reactive alarm (BG < 70 now) ........... no: answered by the newest sample")
print("   PREDICTIVE alarm / AID forecast ........ possibly — tested below\n")

for name, ts, bg, day, nom in ERAS:
    X, fn = features(ts, bg, nom)
    n = len(ts)
    print(f"=== {name}  n={n:,}  CV={100*bg.std()/bg.mean():.1f}%  TBR<70={100*np.mean(bg<70):.2f}% ===")
    gk = GroupKFold(n_splits=5)
    # ---- regression: BG at +h
    print(f"   {'horizon':>8s} {'persistence nRMSE':>18s} {'model nRMSE':>12s} {'skill gain':>11s}")
    for H in (15, 30, 60):
        j = np.searchsorted(ts, ts + H*60_000)
        ok = (j < n)
        tgt = np.full(n, np.nan); tgt[ok] = bg[j[ok]]
        good = np.where((j < n) & (((ts[np.minimum(j, n-1)]-ts)/60_000.0) <= H*1.3), True, False)
        tgt[~good] = np.nan
        m = np.isfinite(X).all(1) & np.isfinite(tgt)
        Xm, y, g = X[m], tgt[m], day[m]
        sd = y.std()
        pers = float(np.sqrt(np.mean((bg[m]-y)**2)))/sd
        p = np.zeros(len(y))
        for tr, te in gk.split(Xm, y, groups=g):
            sc = StandardScaler().fit(Xm[tr])
            p[te] = LinearRegression().fit(sc.transform(Xm[tr]), y[tr]).predict(sc.transform(Xm[te]))
        mod = float(np.sqrt(np.mean((p-y)**2)))/sd
        print(f"   {H:7d}m {pers:18.3f} {mod:12.3f} {100*(pers-mod)/pers:10.1f}%")
    # ---- classification: will BG go below 70 within 30 min
    for THR, LAB, below in ((70.0, "hypo <70 within 30 min", True), (250.0, "hyper >250 within 30 min", False)):
        lab = np.full(n, np.nan)
        for i in range(n):
            k = np.searchsorted(ts, ts[i] + 30*60_000)
            if k >= n or (ts[k]-ts[i])/60_000.0 > 39: continue
            seg = bg[i:k+1]
            lab[i] = float((seg.min() < THR) if below else (seg.max() > THR))
        m = np.isfinite(X).all(1) & np.isfinite(lab) & ((bg >= THR) if below else (bg <= THR))
        Xm, y, g = X[m], lab[m], day[m]
        base = y.mean()
        if base < 0.002 or base > 0.998 or len(np.unique(y)) < 2:
            print(f"   {LAB:26s} base rate {100*base:.2f}% — too rare to model"); continue
        p = np.zeros(len(y))
        for tr, te in gk.split(Xm, y, groups=g):
            if len(np.unique(y[tr])) < 2: continue
            sc = StandardScaler().fit(Xm[tr])
            p[te] = LogisticRegression(max_iter=2000).fit(sc.transform(Xm[tr]), y[tr]).predict_proba(sc.transform(Xm[te]))[:,1]
        auc = roc_auc_score(y, p)
        # lift: precision in the top-decile of risk, relative to base rate
        k = max(int(0.1*len(p)), 50)
        top = np.argsort(-p)[:k]
        print(f"   {LAB:26s} base {100*base:5.2f}%  AUC {auc:.4f}  "
              f"top-decile precision {100*y[top].mean():5.1f}% = {y[top].mean()/base:.2f}x lift")
    print()
print("PROVISIONAL — one subject; each era validated out of sample against itself.")
