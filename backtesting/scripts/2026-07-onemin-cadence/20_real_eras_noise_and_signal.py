#!/usr/bin/env python3
"""Noise and signal in a REAL 5-minute record versus a REAL 1-minute record. No simulation.

This subject wore a 5-minute sensor, then a 1-minute sensor, then briefly a 5-minute sensor
again. Nothing here is decimated or interpolated: every number comes from readings the
sensors actually produced.

METHOD: the variogram (structure function)

    D(tau) = E[ (x(t+tau) - x(t))^2 ]

D is defined in minutes of lag, so it is directly comparable between cadences without any
resampling. For a smooth process observed with additive noise of variance s^2,

    D(tau) = 2*s^2  +  (signal structure, -> 0 as tau -> 0)

so the INTERCEPT at zero lag is twice the noise variance — the "nugget" — and the RISE with
lag is the signal. This separates the two questions the eras are being asked:

    how much NOISE does each sensor add?      -> the nugget
    how much SIGNAL does each sensor reveal?  -> the rise, and its shape at short lags

Glycaemic variability differs between the eras and scales the signal term. It does not touch
the nugget, and we additionally report D normalised by each era's own long-lag level so that
shapes can be compared regardless of how volatile the period was.

THE DECISIVE TEST: fit the 5-minute era's variogram over the lags it can see (5 min and up),
extrapolate inward to 1-4 minutes, and compare against what the 1-minute sensor actually
measures there. Agreement means the 5-minute record already implies everything happening
between its samples. Excess means the faster sensor is delivering something — and whether
that something is signal or noise is read off the shape.
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

ERAS = [
    ("5-min era (83 d)",  *load('2026-03-01', '2026-05-23'), 5.0),
    ("1-min era (61 d)",  *load('2026-05-23', '2026-07-31'), 1.0),
    ("5-min interlude",   *load('2026-06-08', '2026-06-13'), 5.0),
]

def variogram(ts, bg, lags, tol_min):
    out = {}
    for L in lags:
        j = np.searchsorted(ts, ts + int(L*60_000))
        ok = j < len(ts)
        i_ = np.nonzero(ok)[0]; j_ = j[ok]
        act = (ts[j_]-ts[i_])/60_000.0
        keep = np.abs(act - L) <= tol_min
        if keep.sum() < 200: continue
        d = bg[j_[keep]] - bg[i_[keep]]
        out[L] = (float(np.mean(d**2)), int(keep.sum()))
    return out

LAGS_FINE = [1,2,3,4,5,6,8,10,12,15,20,25,30,40,50,60,90,120]
V = {}
print("0. THE RECORDS (nothing removed, nothing simulated)")
print(f"   {'era':>20s} {'readings':>9s} {'days':>6s} {'med gap':>8s} {'mean':>7s} {'SD':>6s} {'CV%':>6s}")
for name, ts, bg, nom in ERAS:
    print(f"   {name:>20s} {len(ts):9,d} {(ts[-1]-ts[0])/86_400_000:6.1f} "
          f"{np.median(np.diff(ts))/60_000:8.2f} {bg.mean():7.1f} {bg.std():6.1f} "
          f"{100*bg.std()/bg.mean():6.1f}")
    V[name] = variogram(ts, bg, LAGS_FINE, 0.6 if nom < 2 else 1.2)

print("\n1. VARIOGRAM D(tau), mg/dL^2 — mean squared change over a lag of tau minutes")
print(f"   {'lag':>5s} " + "".join(f"{n:>22s}" for n, *_ in ERAS))
for L in LAGS_FINE:
    row = f"   {L:4d}m "
    for name, *_ in ERAS:
        row += f"{V[name][L][0]:>16.1f} ({V[name][L][1]//1000:>3d}k)" if L in V[name] else f"{'-':>22s}"
    print(row)

print("\n2. NOISE — the nugget. Fit D(tau) = 2s^2 + c*tau^2 over short lags; intercept is 2s^2.")
print(f"   {'era':>20s} {'fit lags':>10s} {'2s^2':>8s} {'sigma (mg/dL)':>14s}")
nug = {}
for name, ts, bg, nom in ERAS:
    lags = [L for L in V[name] if L <= 20 and L >= nom]
    if len(lags) < 3: print(f"   {name:>20s}  (too few lags)"); continue
    x = np.array(lags, float); y = np.array([V[name][L][0] for L in lags])
    A = np.column_stack([np.ones_like(x), x**2])
    coef, *_ = np.linalg.lstsq(A, y, rcond=None)
    s = np.sqrt(max(coef[0], 0)/2.0); nug[name] = coef[0]
    print(f"   {name:>20s} {f'{int(min(lags))}-{int(max(lags))}m':>10s} {coef[0]:8.2f} {s:14.2f}")
print("   (published reference: Vettoretti 2019 gives a noise SD of 3.19 mg/dL for a")
print("    factory-calibrated 5-min sensor, with AR(2) structure)")

print("\n3. SIGNAL — D with the nugget removed, normalised by each era's own level at 60 min")
print("   This is shape only, so differing glycaemic variability cannot drive it.")
print(f"   {'lag':>5s} " + "".join(f"{n:>20s}" for n, *_ in ERAS))
for L in [5,10,15,20,30,40,60,90,120]:
    row = f"   {L:4d}m "
    for name, *_ in ERAS:
        if L in V[name] and name in nug and 60 in V[name]:
            num = V[name][L][0]-nug[name]; den = V[name][60][0]-nug[name]
            row += f"{num/den:>20.3f}" if den > 0 else f"{'-':>20s}"
        else: row += f"{'-':>20s}"
    print(row)

print("\n4. THE DECISIVE TEST — extrapolate the 5-min era inward and compare with what the")
print("   1-min sensor actually measured at 1-4 minutes.")
a_name, b_name = ERAS[0][0], ERAS[1][0]
lags = [L for L in V[a_name] if 5 <= L <= 20]
x = np.array(lags, float); y = np.array([V[a_name][L][0] for L in lags])
A = np.column_stack([np.ones_like(x), x**2]); coefA, *_ = np.linalg.lstsq(A, y, rcond=None)
# put the 5-min era on the 1-min era's signal scale using the 60-min level
scale = (V[b_name][60][0]-nug[b_name]) / (V[a_name][60][0]-nug[a_name])
print(f"   signal-scale factor applied to the 5-min era (from the 60-min level): {scale:.3f}")
print(f"   {'lag':>5s} {'1-min MEASURED':>16s} {'5-min PREDICTED':>17s} {'excess':>9s} {'excess as sigma':>16s}")
for L in [1,2,3,4,5]:
    if L not in V[b_name]: continue
    meas = V[b_name][L][0]
    pred = nug[b_name] + scale*coefA[1]*L**2
    exc = meas - pred
    print(f"   {L:4d}m {meas:16.2f} {pred:17.2f} {exc:+9.2f} {np.sqrt(abs(exc)/2):16.2f}")
print("\n   A near-zero excess means the 5-minute record already implies the sub-5-minute")
print("   behaviour: the faster sensor is not revealing structure the slower one lacks.")
print("\nPROVISIONAL — one subject; observational between-era comparison.")
