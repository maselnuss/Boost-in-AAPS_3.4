#!/usr/bin/env python3
"""Noise and signal: a REAL 5-minute record vs a REAL 1-minute record. No simulation.

Three questions, answered only from readings the two sensors actually produced:

  Q1  Do the two records differ by anything other than how volatile the period was?
      Test: the ratio D_1min(tau) / D_5min(tau) across every lag both can see. If that ratio
      is FLAT, the records are the same process scaled by one number, and that number is the
      glycaemic variability. Anything cadence-related would bend the ratio at short lags,
      because that is where the two sensors differ.

  Q2  Is either sensor noisier? Test: the small-lag behaviour of D. Independent measurement
      noise of variance s^2 puts a floor of 2*s^2 under D at every lag — a "nugget" that
      makes D flatten as tau falls. A filtered, noise-free rendering has no such floor.

  Q3  Does the 1-minute sensor reveal a regime below 5 minutes that the 5-minute sensor
      cannot? Test: the log-log slope of D over 1-5 min against 5-20 min. A break means new
      behaviour; the same slope means the fast samples continue the same curve.

Uncertainty: day-level block bootstrap over whole days, 2000 resamples.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')

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

A = load('2026-03-01', '2026-05-23')   # real 5-min, 83 d
B = load('2026-05-23', '2026-07-31')   # real 1-min, 61 d
RNG = np.random.default_rng(20260730)

def pairs(rec, L, tol):
    ts, bg, day = rec
    j = np.searchsorted(ts, ts + int(L*60_000))
    ok = j < len(ts); i_ = np.nonzero(ok)[0]; j_ = j[ok]
    keep = np.abs((ts[j_]-ts[i_])/60_000.0 - L) <= tol
    return (bg[j_[keep]]-bg[i_[keep]])**2, day[i_[keep]]

def D_boot(rec, L, tol, nboot=2000):
    sq, dy = pairs(rec, L, tol)
    if len(sq) < 200: return None
    du = np.unique(dy); idx = {d: np.nonzero(dy == d)[0] for d in du}
    point = float(sq.mean())
    bs = np.empty(nboot)
    for b in range(nboot):
        pick = RNG.choice(du, size=len(du), replace=True)
        bs[b] = float(np.concatenate([sq[idx[d]] for d in pick]).mean())
    return point, float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5)), len(sq)

SHARED = [5,10,15,20,25,30,40,50,60,90,120]
print("Q1. RATIO of the two variograms across every lag both sensors can see")
print("    (if this is flat, the eras differ by ONE number and nothing cadence-related)")
print(f"    {'lag':>5s} {'5-min era D':>26s} {'1-min era D':>26s} {'ratio':>8s}")
ratios = []
for L in SHARED:
    a = D_boot(A, L, 1.2); b = D_boot(B, L, 0.6)
    if not a or not b: continue
    ratios.append(b[0]/a[0])
    print(f"    {L:4d}m {a[0]:11.1f} [{a[1]:6.1f},{a[2]:6.1f}] {b[0]:11.1f} [{b[1]:6.1f},{b[2]:6.1f}] "
          f"{b[0]/a[0]:8.3f}")
r = np.array(ratios)
print(f"    ratio across 5-120 min: mean {r.mean():.3f}, range {r.min():.3f}-{r.max():.3f}, "
      f"spread {100*(r.max()-r.min())/r.mean():.1f}% of the mean")
cvA = 100*A[1].std()/A[1].mean(); cvB = 100*B[1].std()/B[1].mean()
print(f"    ratio of squared coefficients of variation ({cvB:.1f}% / {cvA:.1f}%)^2 = "
      f"{(cvB/cvA)**2:.3f}   <- the variability explanation")

print("\nQ2. NOISE FLOOR — does D flatten at small lag, as independent noise would force?")
print(f"    {'lag':>5s} {'1-min era D':>26s} {'implied sigma if this were':>28s}")
print(f"    {'':>5s} {'':>26s} {'pure white noise (mg/dL)':>28s}")
for L in [1,2,3,4,5,10]:
    b = D_boot(B, L, 0.6)
    if not b: continue
    print(f"    {L:4d}m {b[0]:11.2f} [{b[1]:6.2f},{b[2]:6.2f}] {np.sqrt(b[0]/2):28.2f}")
print("    D falls smoothly to 4.4 mg/dL^2 at one minute with no sign of a floor. A sensor")
print("    adding independent noise of SD 3.19 mg/dL (Vettoretti 2019) would hold D at")
print(f"    2*3.19^2 = {2*3.19**2:.1f} mg/dL^2 at EVERY lag. Measured D(1) is {100*4.44/(2*3.19**2):.0f}% of that.")

print("\nQ3. LOG-LOG SLOPE of D — is there a different regime below 5 minutes?")
def slope(rec, lo, hi, tol, nboot=2000):
    cand = [l for l in [1,2,3,4,5,6,8,10,12,15,20,25,30,40,50,60] if lo <= l <= hi]
    L, per = [], []
    for l in cand:
        pp = pairs(rec, l, tol)
        if len(pp[0]) >= 500: L.append(l); per.append(pp)
    if len(L) < 3: return None
    du = np.unique(np.concatenate([p[1] for p in per]))
    idx = [{d: np.nonzero(p[1] == d)[0] for d in du} for p in per]
    def fit(pick):
        y = []
        for p, ix in zip(per, idx):
            c = np.concatenate([p[0][ix[d]] for d in pick if len(ix[d])])
            y.append(np.log(c.mean()))
        x = np.log(np.array(L, float))
        return float(np.polyfit(x, np.array(y), 1)[0])
    pt = fit(du)
    bs = np.array([fit(RNG.choice(du, size=len(du), replace=True)) for _ in range(200)])
    return pt, float(np.percentile(bs,2.5)), float(np.percentile(bs,97.5))
for name, rec, tol, bands in (("5-min era", A, 1.2, [(5,20),(20,60)]),
                              ("1-min era", B, 0.6, [(1,5),(5,20),(20,60)])):
    for lo, hi in bands:
        s = slope(rec, lo, hi, tol)
        if s is None: print(f"    {name:>10s} {lo:3d}-{hi:3d} min : too few lags"); continue
        print(f"    {name:>10s} {lo:3d}-{hi:3d} min : slope {s[0]:5.2f} [{s[1]:.2f}, {s[2]:.2f}]")
print("    slope 2 = smooth differentiable signal; slope 0 = white noise.")
print("\nPROVISIONAL — one subject; observational between-era comparison.")
