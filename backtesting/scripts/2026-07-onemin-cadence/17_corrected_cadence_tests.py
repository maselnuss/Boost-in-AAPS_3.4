#!/usr/bin/env python3
"""CORRECTED cadence comparisons. Supersedes the grid-dependent parts of 10, 11, 13, 14, 15.

DEFECT FOUND 2026-07-30. Those scripts selected the 5-minute view with
    (ts[k] - ts[i]) % (stride*60_000) == 0
Sensor timestamps jitter by +/-1-4 s (only 1.2% of samples land on an exact minute), so that
test finds a mean of 3.22 of the ~7 grid points available in a 30-minute window. The
"5-minute feed" was therefore sampling at roughly 10 minutes, and every comparison that
favoured 1-minute data was inflated by an unknown amount.

Here the 5-minute view is taken by INDEX (every 5th reading of a ~1/min series), which is
what a 5-minute sensor actually delivers, and detection tests are averaged over all five
grid phases rather than being handed a free sample at the event start.

Unaffected by the defect and NOT re-run here: the spectrum, noise floor, reconstruction,
aggregate metrics and AR(2) attribution, all of which decimate by index already.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from aaps_cadence_lib import block_bootstrap_ci, verdict
from sklearn.linear_model import LinearRegression
from sklearn.model_selection import GroupKFold

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts); ndays = (ts[-1]-ts[0])/86_400_000.0
day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
print(f"{n:,} readings over {ndays:.1f} days. 5-min view = every 5th reading (index-based).\n")

# ---------- rate of change
def slope(minutes, stride, phase=0):
    out = np.full(n, np.nan)
    lo = np.searchsorted(ts, ts - minutes*60_000, side="left")
    for i in range(60, n):
        idx = np.arange(int(lo[i]), i+1)
        if stride > 1: idx = idx[(i - idx) % stride == phase]
        if len(idx) < 2: continue
        x = (ts[idx]-ts[idx][0])/60_000.0; y = bg[idx]
        sxx = float(((x-x.mean())**2).sum())
        if sxx > 0: out[i] = float(((x-x.mean())*(y-y.mean())).sum()/sxx*5.0)
    return out

W = 10
ref = np.full(n, np.nan)
xw = np.arange(-W, W+1, dtype=float); sxx_w = float((xw*xw).sum())
for i in range(W, n-W):
    if ts[i+W]-ts[i-W] > (2*W+3)*60_000: continue
    y = bg[i-W:i+W+1]; ref[i] = float((xw*(y-y.mean())).sum()/sxx_w*5.0)

print("A. RATE OF CHANGE (mg/dL per 5 min, vs centred 21-min reference), common mask")
ests = {(m, s): slope(m, s) for m in (15, 30) for s in (1, 5)}
m_all = np.isfinite(ref)
for v in ests.values(): m_all &= np.isfinite(v)
print(f"   common mask n = {m_all.sum():,}")
days_u = sorted(set(day[m_all]))
for m_ in (15, 30):
    blocks = [np.column_stack([ests[(m_,1)][m_all][day[m_all]==d], ests[(m_,5)][m_all][day[m_all]==d],
                               ref[m_all][day[m_all]==d]]) for d in days_u]
    blocks = [b for b in blocks if len(b) > 20]
    rm = lambda bs, col: float(np.sqrt(np.mean((np.concatenate([b[:,col] for b in bs]) -
                                                np.concatenate([b[:,2] for b in bs]))**2)))
    r1,l1,h1 = block_bootstrap_ci(blocks, lambda bs: rm(bs,0))
    r5,l5,h5 = block_bootstrap_ci(blocks, lambda bs: rm(bs,1))
    dd,dl,dh = block_bootstrap_ci(blocks, lambda bs: rm(bs,0)-rm(bs,1))
    print(f"   {m_:2d}-min window: 1-min {r1:5.3f} [{l1:5.3f},{h1:5.3f}]  5-min {r5:5.3f} "
          f"[{l5:5.3f},{h5:5.3f}]  1min-5min {dd:+.3f} [{dl:+.3f},{dh:+.3f}] {verdict(dl,dh)}")

print("\nB. FORWARD PREDICTION (GroupKFold by day)")
s = {(m,st): ests.get((m,st)) if (m,st) in ests else slope(m,st) for m in (5,15,40) for st in (1,5)}
gk = GroupKFold(n_splits=5)
for H in (15, 30, 60):
    fut = np.full(n, np.nan)
    j = np.searchsorted(ts, ts + H*60_000); good = (j < n)
    fut[good] = bg[j[good]]
    X1 = np.column_stack([bg, s[(5,1)], s[(15,1)], s[(40,1)]])
    X5 = np.column_stack([bg, s[(5,5)], s[(15,5)], s[(40,5)]])
    m = np.isfinite(X1).all(1) & np.isfinite(X5).all(1) & np.isfinite(fut)
    y = fut[m]; g = day[m]
    def cv(X):
        Xm = X[m]; p = np.zeros(len(y))
        for tr, te in gk.split(Xm, y, groups=g): p[te] = LinearRegression().fit(Xm[tr], y[tr]).predict(Xm[te])
        return p
    p1, p5 = cv(X1), cv(X5)
    du = sorted(set(g))
    blocks = [np.column_stack([p1[g==d], p5[g==d], y[g==d]]) for d in du]
    blocks = [b for b in blocks if len(b) > 20]
    rm = lambda bs, c_: float(np.sqrt(np.mean((np.concatenate([b[:,c_] for b in bs]) -
                                               np.concatenate([b[:,2] for b in bs]))**2)))
    a1,_,_ = block_bootstrap_ci(blocks, lambda bs: rm(bs,0))
    a5,_,_ = block_bootstrap_ci(blocks, lambda bs: rm(bs,1))
    dd,dl,dh = block_bootstrap_ci(blocks, lambda bs: rm(bs,0)-rm(bs,1))
    print(f"   +{H:2d} min  1-min {a1:5.2f}  5-min {a5:5.2f}  1min-5min {dd:+.3f} "
          f"[{dl:+.3f},{dh:+.3f}] {verdict(dl,dh)}")

# ---------- detection, averaged over grid phase
print("\nC. DETECTION LATENCY at matched false-alarm rate, averaged over all 5 grid phases")
HOR = 30
def detect(direction, MAG):
    sgn = -1.0 if direction == "rise" else 1.0
    ev, non = [], []
    for i in range(60, n-HOR-1):
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: continue
        e = (sgn*(bg[i]-bg[i:j+1])).max()
        if e >= MAG: ev.append(i)
        elif e < MAG*0.4: non.append(i)
    ev, non = np.array(ev), np.array(non[:4000])
    def curve(stride, phases):
        out = []
        for th in np.arange(2.0, float(MAG)+0.01, 1.0):
            lags, fa = [], []
            for ph in phases:
                for pool, isev in ((ev, True), (non, False)):
                    for i in pool:
                        j = min(i+HOR+1, n); k = np.arange(i, j)
                        if stride > 1: k = k[(k-i-ph) % stride == 0]
                        if not len(k): continue
                        w = np.where(sgn*(bg[i]-bg[k]) >= th)[0]
                        if isev: lags.append((ts[k[w[0]]]-ts[i])/60_000.0 if len(w) else None)
                        else: fa.append(len(w) > 0)
            det = [x for x in lags if x is not None]
            if det: out.append((float(np.mean(fa)), float(np.median(det)),
                                len(det)/max(len(lags),1), th))
        return out
    return ev, curve(1, [0]), curve(5, [0,1,2,3,4])

for direction in ("fall", "rise"):
    ev, c1, c5 = detect(direction, 20)
    print(f"   {direction.upper()}S >= 20 mg/dL within {HOR} min  (n={len(ev):,} starts)")
    print(f"   {'FA':>5s} | {'1-min thr/lag/sens':>22s} | {'5-min thr/lag/sens':>22s} | {'gain':>7s}")
    for target in (0.02, 0.05, 0.10, 0.20):
        p1 = min(c1, key=lambda z: abs(z[0]-target)); p5 = min(c5, key=lambda z: abs(z[0]-target))
        if abs(p1[0]-target) > 0.05 or abs(p5[0]-target) > 0.05: continue
        print(f"   {target:5.0%} | {p1[3]:6.0f} {p1[1]:6.1f}m {p1[2]:5.0%} | "
              f"{p5[3]:6.0f} {p5[1]:6.1f}m {p5[2]:5.0%} | {p5[1]-p1[1]:+6.1f}m")
print("\nPROVISIONAL — one person's sensor record.")
