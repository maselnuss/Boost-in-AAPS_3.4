#!/usr/bin/env python3
"""How far ahead can each real record predict, and does the faster one reach further?

Real eras, each at its native cadence, each validated out of sample against itself with
GroupKFold over whole days. Scale-free metrics throughout, with day-level block-bootstrap
intervals, so a difference in glycaemic variability between the eras cannot manufacture a
difference in the answer:

  - normalised RMSE  = RMSE / SD(target)   (1.0 = no better than predicting the mean)
  - LIFT             = precision in the top risk decile / base rate  (base-rate free)
  - AUC              reported alongside, with the caveat that base rates differ

Both cadences get the same look-back in MINUTES; the 1-minute record simply has five times
as many samples inside it.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.metrics import roc_auc_score
from sklearn.preprocessing import StandardScaler
RNG = np.random.default_rng(20260730)

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
ERAS = [("5-min era", *load('2026-03-01', '2026-05-23')),
        ("1-min era", *load('2026-05-23', '2026-07-31'))]

def features(ts, bg):
    n = len(ts); cols = [bg]
    for back in (5, 10, 15, 30, 45):
        j = np.searchsorted(ts, ts - back*60_000)
        cols.append(np.where((ts-ts[j])/60_000.0 <= back*1.4, bg - bg[j], np.nan))
    for win in (15, 30, 45):
        lo = np.searchsorted(ts, ts - win*60_000, side="left"); sl = np.full(n, np.nan)
        for i in range(n):
            idx = np.arange(int(lo[i]), i+1)
            if len(idx) < 2 or (ts[i]-ts[idx[0]])/60_000.0 < win*0.6: continue
            x = (ts[idx]-ts[idx[0]])/60_000.0; y = bg[idx]
            sxx = float(((x-x.mean())**2).sum())
            if sxx > 0: sl[i] = float(((x-x.mean())*(y-y.mean())).sum()/sxx*5.0)
        cols.append(sl)
    return np.column_stack(cols)

def dayboot(stat_fn, groups, nboot=800):
    du = np.unique(groups); idx = {d: np.nonzero(groups == d)[0] for d in du}
    bs = []
    for _ in range(nboot):
        pick = RNG.choice(du, size=len(du), replace=True)
        sel = np.concatenate([idx[d] for d in pick])
        try: bs.append(stat_fn(sel))
        except Exception: pass
    bs = np.array(bs)
    return float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))

DATA = {}
for name, ts, bg, day in ERAS: DATA[name] = (ts, bg, day, features(ts, bg))

print("A. FORECAST ERROR by horizon — normalised RMSE (1.0 = no skill)")
print(f"   {'horizon':>8s} " + "".join(f"{n:>26s}" for n,*_ in ERAS))
for H in (15, 30, 45, 60, 90):
    row = f"   {H:7d}m "
    for name, *_ in ERAS:
        ts, bg, day, X = DATA[name]; n = len(ts)
        j = np.searchsorted(ts, ts + H*60_000)
        ok = (j < n) & (((ts[np.minimum(j, n-1)]-ts)/60_000.0) <= H*1.3)
        tgt = np.where(ok, bg[np.minimum(j, n-1)], np.nan)
        m = np.isfinite(X).all(1) & np.isfinite(tgt)
        Xm, y, g = X[m], tgt[m], day[m]
        p = np.zeros(len(y))
        for tr, te in GroupKFold(n_splits=5).split(Xm, y, groups=g):
            sc = StandardScaler().fit(Xm[tr])
            p[te] = LinearRegression().fit(sc.transform(Xm[tr]), y[tr]).predict(sc.transform(Xm[te]))
        f = lambda s: float(np.sqrt(np.mean((p[s]-y[s])**2))/y[s].std())
        pt = f(np.arange(len(y))); lo, hi = dayboot(f, g, 400)
        row += f"{pt:14.3f} [{lo:.3f},{hi:.3f}]"
    print(row)

print("\nB. PREDICTIVE HYPO ALARM — will BG drop below 70 within H minutes?")
print(f"   {'H':>5s} {'era':>10s} {'base':>7s} {'AUC':>21s} {'top-decile lift':>22s}")
for H in (15, 20, 30, 45, 60):
    for name, *_ in ERAS:
        ts, bg, day, X = DATA[name]; n = len(ts)
        lab = np.full(n, np.nan)
        for i in range(n):
            k = np.searchsorted(ts, ts[i] + H*60_000)
            if k >= n or (ts[k]-ts[i])/60_000.0 > H*1.3: continue
            lab[i] = float(bg[i:k+1].min() < 70.0)
        m = np.isfinite(X).all(1) & np.isfinite(lab) & (bg >= 70.0)
        Xm, y, g = X[m], lab[m], day[m]
        if len(np.unique(y)) < 2: continue
        p = np.zeros(len(y))
        for tr, te in GroupKFold(n_splits=5).split(Xm, y, groups=g):
            if len(np.unique(y[tr])) < 2: continue
            sc = StandardScaler().fit(Xm[tr])
            p[te] = LogisticRegression(max_iter=2000).fit(sc.transform(Xm[tr]), y[tr]).predict_proba(sc.transform(Xm[te]))[:,1]
        fa = lambda s: float(roc_auc_score(y[s], p[s]))
        def fl(s):
            k = max(int(0.1*len(s)), 50); top = s[np.argsort(-p[s])[:k]]
            return float(y[top].mean()/max(y[s].mean(), 1e-9))
        allx = np.arange(len(y))
        a_lo, a_hi = dayboot(fa, g, 400); l_lo, l_hi = dayboot(fl, g, 400)
        print(f"   {H:4d}m {name:>10s} {100*y.mean():6.2f}% {fa(allx):9.4f} [{a_lo:.4f},{a_hi:.4f}] "
              f"{fl(allx):11.2f}x [{l_lo:.2f},{l_hi:.2f}]")
print("\nPROVISIONAL — one subject; between-era comparison is observational.")
