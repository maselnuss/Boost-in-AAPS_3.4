#!/usr/bin/env python3
"""Three rigorous follow-ups to 10_generic_signal_analysis.py. Controller-independent.

F. RECONSTRUCTION (the decisive Nyquist test). If interstitial glucose is band-limited near
   a ~36-min period, then 5-min samples oversample it ~7x and the intervening 1-min samples
   must be RECOVERABLE from them. Anything recoverable is not new information. We reconstruct
   the withheld 1-min samples from the 5-min subsequence with interpolators of increasing
   power and compare the residual to the measurement-noise floor sigma estimated in test B.
   If residual ~= sigma, the 1-min samples carry no signal the 5-min samples lack.

G. RATE ESTIMATION on a COMMON mask (test C compared different sample sets).

H. DETECTION LATENCY at MATCHED FALSE-ALARM RATE. Test E compared fixed thresholds, so a
   noisier, faster feed can look "earlier" purely by crossing a threshold on noise. Here each
   cadence gets its own threshold, tuned so both raise the same number of false alarms; only
   then is latency comparable.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from aaps_cadence_lib import block_bootstrap_ci, verdict
from scipy.interpolate import CubicSpline

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts)
day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
gapm = np.diff(ts)/60_000.0
runs, s = [], 0
for i in range(len(gapm)):
    if abs(gapm[i]-1.0) >= 0.2:
        if i-s >= 61: runs.append((s, i))
        s = i+1
if n-1-s >= 61: runs.append((s, n-1))
sec = bg[2:] - 2*bg[1:-1] + bg[:-2]
ok2 = (np.abs(np.diff(ts)[1:]/60_000.0-1.0) < 0.2) & (np.abs(np.diff(ts)[:-1]/60_000.0-1.0) < 0.2)
sigma = float(np.std(sec[ok2])/np.sqrt(6.0))
print(f"{n:,} readings, {len(runs)} contiguous runs. measurement-noise sigma = {sigma:.2f} mg/dL\n")

# ---------------- F. RECONSTRUCTION
print("F. RECONSTRUCTION of withheld 1-min samples from the 5-min subsequence")
res = {k: [] for k in ("hold", "linear", "cubic", "bandlimited")}
truth_by_day = {}
for (a, b) in runs:
    y = bg[a:b+1]; L = len(y)
    idx5 = np.arange(0, L, 5)
    if len(idx5) < 8: continue
    miss = np.setdiff1d(np.arange(L), idx5)
    miss = miss[(miss > idx5[0]) & (miss < idx5[-1])]
    if not len(miss): continue
    x5, y5 = idx5.astype(float), y[idx5]
    rec = {}
    rec["hold"]   = y5[np.searchsorted(idx5, miss, side="right")-1]           # zero-order hold (what a 5-min consumer sees)
    rec["linear"] = np.interp(miss, x5, y5)
    rec["cubic"]  = CubicSpline(x5, y5)(miss)
    # band-limited (Whittaker-Shannon) reconstruction at the 5-min sample rate
    sinc = np.sinc((miss[:, None] - x5[None, :]) / 5.0)
    rec["bandlimited"] = sinc @ y5
    for k in res: res[k].append(np.abs(rec[k] - y[miss]))
    d = day[a + miss]
    for k in res: pass
    truth_by_day.setdefault('all', []).append(y[miss])
allerr = {k: np.concatenate(v) for k, v in res.items()}
print(f"   withheld samples: {len(allerr['linear']):,}   (4 of every 5 minutes)")
print(f"   {'method':<14s} {'RMSE':>7s} {'MAE':>7s} {'exact':>7s}  interpretation")
for k in ("hold", "linear", "cubic", "bandlimited"):
    e = allerr[k]
    print(f"   {k:<14s} {np.sqrt(np.mean(e**2)):7.2f} {np.mean(e):7.2f} {100*np.mean(e<0.5):6.1f}%")
print(f"   noise floor (sigma)  {sigma:7.2f}   <- irreducible; a reconstruction cannot beat this")
best = min(("linear","cubic","bandlimited"), key=lambda k: np.sqrt(np.mean(allerr[k]**2)))
br = float(np.sqrt(np.mean(allerr[best]**2)))
print(f"   best interpolator ({best}) RMSE {br:.2f} = {br/sigma:.1f}x the noise floor")
print(f"   -> {100*(1 - (br**2 - sigma**2)/max(np.var(np.concatenate(truth_by_day['all'])),1e-9)):.2f}% of the")
print("      withheld samples' variance is already implied by the 5-min samples plus noise")

# ---------------- G. RATE on a COMMON mask
print("\nG. RATE ESTIMATION, common mask (mg/dL per 5 min, vs centred 21-min reference)")
W = 10
ref = np.full(n, np.nan)
xw = np.arange(-W, W+1, dtype=float); sxx_w = float((xw*xw).sum())
for i in range(W, n-W):
    if ts[i+W]-ts[i-W] != 2*W*60_000: continue
    y = bg[i-W:i+W+1]; ref[i] = float((xw*(y-y.mean())).sum()/sxx_w*5.0)
def causal_slope(minutes, stride):
    out = np.full(n, np.nan)
    lo = np.searchsorted(ts, ts - minutes*60_000, side="left")
    for i in range(60, n):
        idx = np.arange(int(lo[i]), i+1)
        idx = idx[(ts[i]-ts[idx]) % (stride*60_000) == 0]
        if len(idx) < 3: continue
        x = (ts[idx]-ts[idx][0])/60_000.0; y = bg[idx]
        sxx = float(((x-x.mean())**2).sum())
        if sxx > 0: out[i] = float(((x-x.mean())*(y-y.mean())).sum()/sxx*5.0)
    return out
ests = {(m, st): causal_slope(m, st) for m in (15, 30) for st in (1, 5)}
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
    print(f"   {m_:2d}-min window:  1-min RMSE {r1:5.3f} [{l1:5.3f},{h1:5.3f}]   "
          f"5-min RMSE {r5:5.3f} [{l5:5.3f},{h5:5.3f}]   1min-5min {dd:+.3f} [{dl:+.3f},{dh:+.3f}] {verdict(dl,dh)}")

# ---------------- H. DETECTION LATENCY at MATCHED FALSE-ALARM RATE
print("\nH. DETECTION LATENCY at matched false-alarm rate")
HOR = 30            # minutes ahead the event must complete
for DROP in (20, 30):
    ev, non = [], []
    for i in range(60, n-HOR-1):
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: continue
        seg = slice(i, j+1)
        if (bg[i] - bg[seg]).max() >= DROP: ev.append(i)
        elif (bg[i] - bg[seg]).max() < DROP*0.4: non.append(i)
    ev, non = np.array(ev), np.array(non)
    print(f"   drop >= {DROP} mg/dL within {HOR} min : {len(ev):,} event starts, {len(non):,} quiet starts")
    def curve(stride):
        """for each threshold: (false-alarm rate on quiet starts, median detection lag on events)"""
        out = []
        for th in np.arange(2.0, float(DROP)+0.01, 1.0):
            def first_cross(i):
                j = min(i+HOR+1, n)
                k = np.arange(i, j)
                k = k[((ts[k]-ts[i]) % (stride*60_000)) == 0]
                d = bg[i] - bg[k]
                w = np.where(d >= th)[0]
                return (ts[k[w[0]]]-ts[i])/60_000.0 if len(w) else None
            lags = [first_cross(i) for i in ev]
            det  = [l for l in lags if l is not None]
            fa = np.mean([first_cross(i) is not None for i in non[:4000]])
            if det: out.append((fa, float(np.median(det)), len(det)/len(ev), th))
        return out
    c1, c5 = curve(1), curve(5)
    print(f"   {'FA rate':>8s} | {'1-min: thr lag sens':>26s} | {'5-min: thr lag sens':>26s} | {'lag gain':>9s}")
    for target in (0.02, 0.05, 0.10, 0.20):
        p1 = min(c1, key=lambda z: abs(z[0]-target)); p5 = min(c5, key=lambda z: abs(z[0]-target))
        if abs(p1[0]-target) > 0.05 or abs(p5[0]-target) > 0.05: continue
        print(f"   {target:8.0%} | {p1[3]:8.0f} {p1[1]:6.1f}m {p1[2]:7.0%}       | "
              f"{p5[3]:8.0f} {p5[1]:6.1f}m {p5[2]:7.0%}       | {p5[1]-p1[1]:+7.1f}m")
print("\nPROVISIONAL — one person's sensor record.")
