#!/usr/bin/env python3
"""Is there information in 1-min CGM that 5-min sampling does not already carry?

CONTROLLER-INDEPENDENT. Nothing here touches any specific algorithm: the question is about
the SIGNAL. Earlier scripts in this directory ran through one controller's front end; this
one works on raw glucose with generic estimators so the answer does not depend on any
implementation.

The literature makes a strong prediction. Gough, Kreutz-Delgado & Bremer (Ann Biomed Eng
2003) put the frequency band edge of BLOOD glucose at about 1e-3 Hz, implying a ~10-min
sampling period suffices. Breton, Shields & Kovatchev (JDST 2008) went further for the
compartment a CGM actually measures: interstitial glucose in type 1 diabetes shows no
patterns of period shorter than ~36 min, giving an 18-min Nyquist period, and they note it
would be "detrimental" to sample blood faster because those dynamics are absent from
interstitium. If that holds on a modern 1-min sensor, then 5-min sampling already
oversamples by ~3-7x and 1-min by ~18x, and the extra samples cannot carry new signal.

Tests, in order:
  A. POWER SPECTRUM — where does the energy actually sit? Directly tests the 36-min claim.
  B. NOISE FLOOR — measurement noise and quantisation against the size of real changes.
  C. RATE ESTIMATION — is a rate estimated from 1-min data closer to truth than from 5-min?
  D. PREDICTION — do 1-min features forecast future glucose better, at several horizons?
  E. DETECTION LATENCY vs MAGNITUDE — the crossover: how big must a change be before more
     frequent reporting actually delivers it sooner?

PROVISIONAL: one person's sensor record.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from aaps_cadence_lib import block_bootstrap_ci, verdict
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.metrics import roc_auc_score

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts)
day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
print(f"{n:,} readings, {len(set(day))} days, 1-min cadence\n")

# contiguous 1-min runs
gapm = np.diff(ts)/60_000.0
runs, s = [], 0
for i in range(len(gapm)):
    if abs(gapm[i]-1.0) >= 0.2:
        if i-s >= 128: runs.append((s, i))
        s = i+1
if n-1-s >= 128: runs.append((s, n-1))
print(f"contiguous runs >=128 min: {len(runs)}\n")

# ---------------- A. POWER SPECTRUM
print("A. POWER SPECTRUM — testing Breton 2008's ~36-min interstitial cutoff")
segs = []
for (a, b) in runs:
    y = bg[a:b+1].astype(float)
    L = 256
    for k in range(0, len(y)-L+1, L//2):
        w = y[k:k+L]
        w = w - w.mean()
        if w.std() < 1e-9: continue
        segs.append(w * np.hanning(L))
if segs:
    P = np.mean([np.abs(np.fft.rfft(w))**2 for w in segs], axis=0)
    f = np.fft.rfftfreq(256, d=60.0)            # Hz, 1-min spacing
    P[0] = 0.0
    tot = P.sum()
    def frac_above(period_min):
        return 100.0*P[f > 1.0/(period_min*60.0)].sum()/tot
    print(f"   segments {len(segs)} x 256 min")
    for p_ in (60, 36, 20, 10, 5, 2):
        print(f"   power at periods SHORTER than {p_:3d} min : {frac_above(p_):6.3f}%")
    # frequency below which 95/99% of power sits
    cum = np.cumsum(P)/tot
    for q in (0.95, 0.99, 0.999):
        idx = int(np.searchsorted(cum, q))
        per = (1.0/f[idx])/60.0 if f[idx] > 0 else float('inf')
        print(f"   {q*100:5.1f}% of power lies at periods LONGER than {per:6.1f} min")

# ---------------- B. NOISE FLOOR
print("\nB. NOISE FLOOR")
d1 = np.diff(bg)[np.abs(gapm-1.0) < 0.2]
print(f"   1-min change: SD {d1.std():.2f} mg/dL, {100*np.mean(d1==0):.1f}% exactly zero, "
      f"{100*np.mean(np.abs(d1)<=1):.1f}% within +/-1")
# high-frequency residual as a noise proxy: second difference / sqrt(6)
sec = bg[2:] - 2*bg[1:-1] + bg[:-2]
ok2 = (np.abs(np.diff(ts)[1:]/60_000.0 - 1.0) < 0.2) & (np.abs(np.diff(ts)[:-1]/60_000.0 - 1.0) < 0.2)
sigma = float(np.std(sec[ok2])/np.sqrt(6.0))
print(f"   implied measurement noise sigma ~= {sigma:.2f} mg/dL (2nd-difference estimator)")
print(f"   quantisation step 1 mg/dL -> uniform-quantiser SD = {1/np.sqrt(12):.2f} mg/dL")
print(f"   a change is resolvable per-sample only above ~{2*sigma:.1f} mg/dL")
print(f"   -> per MINUTE that needs a rate above {2*sigma:.1f} mg/dL/min = {2*sigma*5:.1f} mg/dL per 5 min")

# ---------------- C. RATE ESTIMATION vs a smooth reference
print("\nC. RATE ESTIMATION — error against a centred 21-min reference slope (mg/dL per 5 min)")
W = 10
ref = np.full(n, np.nan)
for i in range(W, n-W):
    x = np.arange(-W, W+1, dtype=float); y = bg[i-W:i+W+1]
    ref[i] = float((x*(y-y.mean())).sum()/(x*x).sum()*5.0)
def causal_slope(minutes, stride):
    out = np.full(n, np.nan)
    lo = np.searchsorted(ts, ts - minutes*60_000, side="left")
    for i in range(60, n):
        l = int(lo[i]); idx = np.arange(l, i+1)
        idx = idx[(ts[i]-ts[idx]) % (stride*60_000) == 0]
        if len(idx) < 2: continue
        x = (ts[idx]-ts[idx][0])/60_000.0; y = bg[idx]
        sxx = float(((x-x.mean())**2).sum())
        if sxx > 0: out[i] = float(((x-x.mean())*(y-y.mean())).sum()/sxx*5.0)
    return out
for label, mins, stride in (("1-min data, 15-min window", 15, 1), ("5-min data, 15-min window", 15, 5),
                            ("1-min data, 30-min window", 30, 1), ("5-min data, 30-min window", 30, 5)):
    est = causal_slope(mins, stride)
    m = np.isfinite(est) & np.isfinite(ref)
    print(f"   {label:28s} RMSE {np.sqrt(np.mean((est[m]-ref[m])**2)):.3f}  n={m.sum():,}")

# ---------------- D. PREDICTION at several horizons
print("\nD. PREDICTION — future glucose from 1-min vs 5-min features (GroupKFold by day)")
s5_1, s15_1, s40_1 = causal_slope(5,1), causal_slope(15,1), causal_slope(40,1)
s5_5, s15_5, s40_5 = causal_slope(5,5), causal_slope(15,5), causal_slope(40,5)
gk = GroupKFold(n_splits=5)
for H in (15, 30, 60):
    fut = np.full(n, np.nan)
    j = np.searchsorted(ts, ts + H*60_000)
    good = j < n
    fut[good] = bg[j[good]]
    X5 = np.column_stack([bg, s5_5, s15_5, s40_5]); X1 = np.column_stack([bg, s5_1, s15_1, s40_1])
    m = np.isfinite(X5).all(1) & np.isfinite(X1).all(1) & np.isfinite(fut)
    y = fut[m]; g = day[m]
    def cv_rmse(X):
        Xm = X[m]; p = np.zeros(len(y))
        for tr, te in gk.split(Xm, y, groups=g):
            mo = LinearRegression().fit(Xm[tr], y[tr]); p[te] = mo.predict(Xm[te])
        return p
    p5, p1 = cv_rmse(X5), cv_rmse(X1)
    days_u = sorted(set(g))
    blocks = [np.column_stack([p5[g==d], p1[g==d], y[g==d]]) for d in days_u]
    blocks = [b for b in blocks if len(b) > 20]
    def rmse(bs, col):
        a = np.concatenate([b[:,col] for b in bs]); yy = np.concatenate([b[:,2] for b in bs])
        return float(np.sqrt(np.mean((a-yy)**2)))
    r5, lo5, hi5 = block_bootstrap_ci(blocks, lambda bs: rmse(bs,0))
    r1, lo1, hi1 = block_bootstrap_ci(blocks, lambda bs: rmse(bs,1))
    dd, dlo, dhi = block_bootstrap_ci(blocks, lambda bs: rmse(bs,1)-rmse(bs,0))
    print(f"   +{H:2d} min  5-min feats RMSE {r5:5.2f} [{lo5:5.2f},{hi5:5.2f}]   "
          f"1-min feats {r1:5.2f} [{lo1:5.2f},{hi1:5.2f}]   diff {dd:+.3f} [{dlo:+.3f},{dhi:+.3f}]  {verdict(dlo,dhi)}")

# ---------------- E. DETECTION LATENCY vs MAGNITUDE
print("\nE. DETECTION LATENCY — how big must a move be before 1-min delivers it sooner?")
print(f"   {'threshold':>10s} {'events':>8s} {'median gain (min)':>18s} {'% earlier':>10s}")
for TH in (3, 5, 10, 15, 20, 30):
    gains = []
    step = 25
    for i in range(60, n-40, step):
        seg = slice(i, min(i+40, n))
        drop = bg[i] - bg[seg]
        w1 = np.where(drop >= TH)[0]
        if not len(w1): continue
        w5 = np.where((drop >= TH) & (((ts[seg]-ts[i]) % 300_000) == 0))[0]
        if not len(w5): continue
        gains.append((ts[i+w5[0]] - ts[i+w1[0]])/60_000.0)
    if len(gains) >= 30:
        g_ = np.array(gains)
        print(f"   {TH:9d} {len(g_):8d} {np.median(g_):18.1f} {100*np.mean(g_>0):9.0f}%")
print("\nPROVISIONAL — one person's sensor record; signal-level analysis, no clinical outcome.")
