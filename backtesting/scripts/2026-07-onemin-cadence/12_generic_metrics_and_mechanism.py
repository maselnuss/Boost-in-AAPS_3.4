#!/usr/bin/env python3
"""Completing the controller-independent picture.

I. AGGREGATE METRICS at 1 / 5 / 15-min. Russon et al. (JDST 2025) coarsened 5->15 min and
   found mean, CV and TIR unchanged while hypo episodes fell 19.2%. If refining 5->1 min is
   the mirror image, aggregates should not move and only event counts should.

J. MECHANISM. Vettoretti et al. (Sensors 2019) put the blood->interstitial time constant at
   tau ~= 3.8 min and total sensor noise at 3.19 mg/dL SD with AR(2) (coloured) structure.
   A first-order lag is a low-pass filter; we compute what it does to short-period content
   and how large a blood oscillation would have to be to survive it and clear the noise.

K. IS THE SHORT-PERIOD STRUCTURE GLUCOSE OR COLOURED NOISE? Earlier work in this directory
   read the 1-min sign coherence (lag-1 ACF +0.72) as real glucose. AR(2) sensor noise is
   also autocorrelated and would produce the same thing. We fit AR(2) to the residual and
   ask whether the observed high-frequency spectrum needs any glucose at all to explain it.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts)

# ---------------- I. AGGREGATE METRICS
print("I. AGGREGATE METRICS by recording interval (decimating the same record)")
def episodes(t, y, thr, below=True, min_min=15):
    """standard-style excursions: >= min_min continuously beyond thr"""
    m = (y < thr) if below else (y > thr)
    cnt, run_start = 0, None
    for i in range(len(y)):
        if m[i]:
            if run_start is None: run_start = t[i]
        else:
            if run_start is not None:
                if (t[i-1]-run_start)/60_000.0 >= min_min - 1e-9: cnt += 1
                run_start = None
    if run_start is not None and (t[-1]-run_start)/60_000.0 >= min_min: cnt += 1
    return cnt
print(f"   {'interval':>9s} {'n':>8s} {'mean':>7s} {'CV%':>6s} {'TIR%':>6s} {'TING%':>6s} "
      f"{'<70%':>6s} {'hypo ep':>8s} {'L2 hypo':>8s} {'hyper ep':>9s}")
base = {}
for step in (1, 5, 15):
    k = np.arange(0, n, step)
    t_, y_ = ts[k], bg[k]
    row = dict(mean=y_.mean(), cv=100*y_.std()/y_.mean(),
               tir=100*np.mean((y_>=70)&(y_<=180)), ting=100*np.mean((y_>=63)&(y_<=140)),
               lo=100*np.mean(y_<70), hypo=episodes(t_,y_,70), l2=episodes(t_,y_,54),
               hyper=episodes(t_,y_,250,below=False))
    if step == 5: base = dict(row)
    print(f"   {step:8d}m {len(k):8,d} {row['mean']:7.1f} {row['cv']:6.1f} {row['tir']:6.1f} "
          f"{row['ting']:6.1f} {row['lo']:6.2f} {row['hypo']:8d} {row['l2']:8d} {row['hyper']:9d}")
for step, lbl in ((1, "1-min vs 5-min"), (15, "15-min vs 5-min")):
    k = np.arange(0, n, step); t_, y_ = ts[k], bg[k]
    h = episodes(t_,y_,70); l2 = episodes(t_,y_,54); hy = episodes(t_,y_,250,below=False)
    print(f"   {lbl:16s} mean {y_.mean()-base['mean']:+5.2f}  TIR {100*np.mean((y_>=70)&(y_<=180))-base['tir']:+5.2f}pp"
          f"  CV {100*y_.std()/y_.mean()-base['cv']:+5.2f}pp"
          f"  hypo-ep {100*(h-base['hypo'])/max(base['hypo'],1):+6.1f}%"
          f"  L2 {100*(l2-base['l2'])/max(base['l2'],1):+6.1f}%"
          f"  hyper-ep {100*(hy-base['hyper'])/max(base['hyper'],1):+6.1f}%")

# ---------------- J. MECHANISM: the interstitial low-pass filter
print("\nJ. MECHANISM — blood->interstitial first-order lag, tau = 3.8 min (Vettoretti 2019)")
tau = 3.8; noise_sd = 3.19
print(f"   {'period':>8s} {'|H|':>7s} {'attenuation':>12s} {'blood amplitude needed to clear 1 SD of noise':>48s}")
for P in (2, 5, 10, 20, 36, 60, 120, 240):
    H = 1.0/np.sqrt(1.0 + (2*np.pi*tau/P)**2)
    print(f"   {P:7d}m {H:7.3f} {20*np.log10(H):9.1f} dB {noise_sd/H:44.1f} mg/dL")
print(f"   the interstitium removes {100*(1-1.0/np.sqrt(1+(2*np.pi*tau/5)**2)):.0f}% of the amplitude of any")
print( "   5-minute-period blood oscillation BEFORE the sensor transduces it")

# ---------------- K. AR(2) noise vs glucose at high frequency
print("\nK. IS SHORT-PERIOD 1-MIN STRUCTURE GLUCOSE, OR AR(2) SENSOR NOISE?")
gapm = np.diff(ts)/60_000.0
runs, s = [], 0
for i in range(len(gapm)):
    if abs(gapm[i]-1.0) >= 0.2:
        if i-s >= 256: runs.append((s, i))
        s = i+1
if n-1-s >= 256: runs.append((s, n-1))
W = 10
resid, segs = [], []
for (a, b) in runs:
    y = bg[a:b+1]
    sm = np.convolve(y, np.ones(2*W+1)/(2*W+1), mode='valid')
    rr = y[W:len(y)-W] - sm
    resid.append(rr)
    L = 256
    for k in range(0, len(y)-L+1, L//2):
        w = y[k:k+L] - y[k:k+L].mean()
        if w.std() > 1e-9: segs.append(w*np.hanning(L))
rall = np.concatenate(resid)
r0 = float(np.var(rall)); r1 = float(np.mean(rall[1:]*rall[:-1])); r2 = float(np.mean(rall[2:]*rall[:-2]))
den = r0*r0 - r1*r1
a1 = (r1*r0 - r1*r2)/den; a2 = (r2*r0 - r1*r1)/den
sw2 = r0 - a1*r1 - a2*r2
print(f"   residual (1-min minus 21-min smooth): SD {np.sqrt(r0):.2f} mg/dL, "
      f"AR(2) a1={a1:+.3f} a2={a2:+.3f}, innovation SD {np.sqrt(max(sw2,0)):.2f}")
print(f"   Vettoretti 2019 Dexcom G6 reference: noise SD 3.19 mg/dL, AR(2), coloured")
f = np.fft.rfftfreq(256, d=1.0)   # cycles per minute
P = np.mean([np.abs(np.fft.rfft(w))**2 for w in segs], axis=0); P[0] = 0
w_ = 2*np.pi*f
Har = sw2/np.abs(1 - a1*np.exp(-1j*w_) - a2*np.exp(-2j*w_))**2
sel = f > 1.0/20.0
scale = np.median(P[sel]/np.maximum(Har[sel], 1e-12))
print(f"   {'period':>8s} {'observed PSD':>14s} {'AR(2)-noise PSD':>17s} {'ratio':>7s}")
for P_ in (20, 15, 10, 6, 4, 3, 2):
    i_ = int(np.argmin(np.abs(f - 1.0/P_)))
    print(f"   {P_:7d}m {P[i_]:14.1f} {Har[i_]*scale:17.1f} {P[i_]/max(Har[i_]*scale,1e-9):7.2f}")
print("   a ratio near 1.0 means the observed short-period content is fully accounted for by")
print("   an autocorrelated NOISE process — no glucose dynamics required to explain it")
print("\nPROVISIONAL — one person's sensor record.")
