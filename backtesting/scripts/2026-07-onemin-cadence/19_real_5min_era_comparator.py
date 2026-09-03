#!/usr/bin/env python3
"""Compare this person's REAL 5-minute era against their 1-minute era.

Every cadence comparison so far decimated the 1-minute record to simulate a 5-minute feed.
That assumes a real 5-minute sensor is just the 1-minute signal with samples removed. It may
not be: manufacturers apply internal filtering before reporting, so a real 5-minute feed
could be cleaner than a decimated one — in which case the decimation UNDERSTATES how good
5-minute data is, and every null in the paper is conservative.

This subject wore a 5-minute sensor for 83 days before switching. We take matched 45-day
windows either side of the switch:

    Era A (real 5-min):  2026-04-08 .. 2026-05-22
    Era B (real 1-min):  2026-06-13 .. 2026-07-30
    Era B5:              Era B decimated by index to 5 minutes — the simulated comparator

The decisive comparison is A vs B5. If they have the same noise and spectral character, the
decimation was fair. If A is cleaner, the paper's conclusions hold a fortiori.

Between-era comparison is observational: season, therapy and sensor model all change at the
boundary. Distributional statistics are reported first so the reader can judge comparability.
"""
import sys, numpy as np, psycopg2
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')

DSN = "dbname=oref host=127.0.0.1 port=5432"
def load(a, b):
    with psycopg2.connect(DSN) as c, c.cursor() as cur:
        cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                    "where user_id='I' and cgm_mgdl is not null and ts_utc>=%s and ts_utc<%s "
                    "order by ts_utc", (a, b))
        r = cur.fetchall()
    return np.array([int(x[0]) for x in r], np.int64), np.array([float(x[1]) for x in r], float)

tsA, bgA = load('2026-04-08', '2026-05-23')
tsB, bgB = load('2026-06-13', '2026-07-31')
iB5 = np.arange(0, len(tsB), 5)
tsB5, bgB5 = tsB[iB5], bgB[iB5]
ERAS = [("A  real 5-min", tsA, bgA, 5.0), ("B  real 1-min", tsB, bgB, 1.0),
        ("B5 decimated 5-min", tsB5, bgB5, 5.0)]

print("1. COMPARABILITY of the two eras")
print(f"   {'era':>20s} {'n':>8s} {'days':>6s} {'med gap':>8s} {'mean':>7s} {'SD':>6s} {'CV%':>6s} "
      f"{'TIR%':>6s} {'<70%':>6s}")
for name, t, y, nom in ERAS:
    g = np.median(np.diff(t))/60_000.0
    print(f"   {name:>20s} {len(t):8,d} {(t[-1]-t[0])/86_400_000:6.1f} {g:8.2f} {y.mean():7.1f} "
          f"{y.std():6.1f} {100*y.std()/y.mean():6.1f} {100*np.mean((y>=70)&(y<=180)):6.1f} "
          f"{100*np.mean(y<70):6.2f}")

print("\n2. NOISE at the reporting cadence (2nd-difference estimator, white component)")
print(f"   {'era':>20s} {'step SD':>8s} {'zero%':>7s} {'sigma':>7s} {'resid SD vs 25-min trend':>26s}")
for name, t, y, nom in ERAS:
    d = np.diff(t)/60_000.0
    ok = np.abs(d - nom) < nom*0.25
    step = np.diff(y)[ok]
    sec = y[2:] - 2*y[1:-1] + y[:-2]
    ok2 = (np.abs(d[1:]-nom) < nom*0.25) & (np.abs(d[:-1]-nom) < nom*0.25)
    sig = float(np.std(sec[ok2])/np.sqrt(6.0))
    W = int(round(12.5/nom))
    sm = np.convolve(y, np.ones(2*W+1)/(2*W+1), mode='valid')
    resid = y[W:len(y)-W] - sm
    print(f"   {name:>20s} {step.std():8.2f} {100*np.mean(step==0):6.1f}% {sig:7.2f} {resid.std():26.2f}")

print("\n3. AUTOCORRELATION of the residual (is the noise coloured, and equally so?)")
print(f"   {'era':>20s} {'lag1':>7s} {'lag2':>7s} {'lag3':>7s}  (lags in samples)")
for name, t, y, nom in ERAS:
    W = int(round(12.5/nom))
    sm = np.convolve(y, np.ones(2*W+1)/(2*W+1), mode='valid')
    r_ = y[W:len(y)-W] - sm; r_ = r_ - r_.mean()
    ac = [float(np.mean(r_[k:]*r_[:-k])/np.var(r_)) for k in (1,2,3)]
    print(f"   {name:>20s} {ac[0]:+7.3f} {ac[1]:+7.3f} {ac[2]:+7.3f}")

print("\n4. SPECTRUM over the band both cadences resolve (periods >= 10 min)")
print(f"   {'era':>20s} {'>60min':>8s} {'36-60':>8s} {'20-36':>8s} {'10-20':>8s}")
for name, t, y, nom in ERAS:
    d = np.diff(t)/60_000.0
    runs, s = [], 0
    L = int(round(256/nom))
    for i in range(len(d)):
        if abs(d[i]-nom) >= nom*0.25:
            if i-s >= L: runs.append((s,i))
            s = i+1
    if len(y)-1-s >= L: runs.append((s, len(y)-1))
    segs = []
    for (a,b) in runs:
        w_ = y[a:b+1]
        for k in range(0, len(w_)-L+1, L//2):
            q = w_[k:k+L] - w_[k:k+L].mean()
            if q.std() > 1e-9: segs.append(q*np.hanning(L))
    if not segs: print(f"   {name:>20s}  (no segments)"); continue
    P = np.mean([np.abs(np.fft.rfft(q))**2 for q in segs], axis=0); P[0] = 0
    f = np.fft.rfftfreq(L, d=nom)          # cycles per minute
    per = np.divide(1.0, f, out=np.full_like(f, np.inf), where=f > 0)
    tot = P.sum()
    band = lambda lo, hi: 100*P[(per >= lo) & (per < hi)].sum()/tot
    print(f"   {name:>20s} {band(60,1e9):7.1f}% {band(36,60):7.1f}% {band(20,36):7.1f}% {band(10,20):7.1f}%")

print("\n5. RATE OF CHANGE: error against a centred 25-min reference, each era in its own units")
print(f"   {'era':>20s} {'RMSE (mg/dL per 5 min)':>24s} {'n':>9s}")
for name, t, y, nom in ERAS:
    W = int(round(12.5/nom)); nn = len(y)
    if W < 2: W = 2
    xw = np.arange(-W, W+1, dtype=float)*nom; sxx = float((xw*xw).sum())
    ref = np.full(nn, np.nan); est = np.full(nn, np.nan)
    for i in range(W, nn-W):
        if t[i+W]-t[i-W] > (2*W*nom+3)*60_000: continue
        q = y[i-W:i+W+1]; ref[i] = float((xw*(q-q.mean())).sum()/sxx*5.0)
    M = max(int(round(15.0/nom)), 2)                     # causal 15-min window
    xm = np.arange(-M, 1, dtype=float)*nom; sxm = float(((xm-xm.mean())**2).sum())
    for i in range(M, nn):
        if t[i]-t[i-M] > (M*nom+3)*60_000: continue
        q = y[i-M:i+1]
        est[i] = float(((xm-xm.mean())*(q-q.mean())).sum()/sxm*5.0)
    m = np.isfinite(ref) & np.isfinite(est)
    print(f"   {name:>20s} {np.sqrt(np.mean((est[m]-ref[m])**2)):24.3f} {m.sum():9,d}")
print("\nPROVISIONAL — one subject, observational between-era comparison.")

# ---------------------------------------------------------------- de-confounding
print("\n6. NOISE ON FLAT STRETCHES ONLY (removes the variability confound)")
print("   Restricted to windows whose centred 25-min slope is under 1 mg/dL per 5 min, so the")
print("   true signal is near-constant and the second difference is close to pure noise.")
print(f"   {'era':>20s} {'flat n':>8s} {'sigma':>7s} {'resid SD':>9s} {'step SD':>8s}")
flat = {}
for name, t, y, nom in ERAS:
    W = int(round(12.5/nom)); nn = len(y)
    xw = np.arange(-W, W+1, dtype=float)*nom; sxx = float((xw*xw).sum())
    slope = np.full(nn, np.nan)
    for i in range(W, nn-W):
        if t[i+W]-t[i-W] > (2*W*nom+3)*60_000: continue
        q = y[i-W:i+W+1]; slope[i] = float((xw*(q-q.mean())).sum()/sxx*5.0)
    calm = np.isfinite(slope) & (np.abs(slope) < 1.0)
    d = np.diff(t)/60_000.0
    sec = y[2:] - 2*y[1:-1] + y[:-2]
    ok2 = (np.abs(d[1:]-nom) < nom*0.25) & (np.abs(d[:-1]-nom) < nom*0.25) & calm[1:-1]
    sig = float(np.std(sec[ok2])/np.sqrt(6.0))
    stp = np.diff(y)[(np.abs(d-nom) < nom*0.25) & calm[:-1]]
    sm = np.convolve(y, np.ones(2*W+1)/(2*W+1), mode='valid')
    resid = (y[W:nn-W] - sm)[calm[W:nn-W]]
    flat[name] = sig
    print(f"   {name:>20s} {int(ok2.sum()):8,d} {sig:7.2f} {resid.std():9.2f} {stp.std():8.2f}")
print(f"   -> real 5-min sensor noise is {100*(1-flat['A  real 5-min']/flat['B5 decimated 5-min']):.0f}% "
      f"LOWER than the decimated proxy used throughout this paper")

print("\n7. RATE OF CHANGE normalised by each era's own signal variability")
print(f"   {'era':>20s} {'RMSE':>7s} {'SD(ref slope)':>14s} {'RMSE/SD':>9s}")
for name, t, y, nom in ERAS:
    W = int(round(12.5/nom)); nn = len(y)
    xw = np.arange(-W, W+1, dtype=float)*nom; sxx = float((xw*xw).sum())
    ref = np.full(nn, np.nan); est = np.full(nn, np.nan)
    for i in range(W, nn-W):
        if t[i+W]-t[i-W] > (2*W*nom+3)*60_000: continue
        q = y[i-W:i+W+1]; ref[i] = float((xw*(q-q.mean())).sum()/sxx*5.0)
    M = max(int(round(15.0/nom)), 2)
    xm = np.arange(-M, 1, dtype=float)*nom; sxm = float(((xm-xm.mean())**2).sum())
    for i in range(M, nn):
        if t[i]-t[i-M] > (M*nom+3)*60_000: continue
        q = y[i-M:i+1]
        est[i] = float(((xm-xm.mean())*(q-q.mean())).sum()/sxm*5.0)
    m = np.isfinite(ref) & np.isfinite(est)
    rmse = float(np.sqrt(np.mean((est[m]-ref[m])**2))); sd = float(ref[m].std())
    print(f"   {name:>20s} {rmse:7.3f} {sd:14.3f} {rmse/sd:9.3f}")
