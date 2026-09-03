"""Shared machinery for the 1-minute vs 5-minute CGM cadence analysis.

Design rules, each of which exists because breaking it produced a wrong answer earlier:

  1. NEVER select a cadence view by timestamp modulo. Sensor timestamps jitter by seconds, so
     `(t - t0) % 300000 == 0` silently drops half the samples and cripples the slower feed.
     Cadence views are taken by INDEX, or better, from a record that really ran at that rate.
  2. Prefer REAL eras to decimation when comparing sensors. Decimating a fast record models a
     consumer sampling slowly, not a different device.
  3. Metrics must be SCALE-FREE when comparing eras, because glycaemic variability differs
     between them: normalised RMSE, lift over base rate, log-log slopes, variogram ratios.
  4. Uncertainty is a DAY-LEVEL block bootstrap. Glucose is autocorrelated; per-point
     intervals are far too narrow.
  5. Out-of-sample validation is GroupKFold over whole days.
"""
import json, os, numpy as np, psycopg2, datetime as dt
from sklearn.linear_model import LinearRegression, LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.metrics import roc_auc_score
from sklearn.preprocessing import StandardScaler

DSN = "dbname=oref host=127.0.0.1 port=5432"
USER = 'I'
RESULTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
SEED = 20260730

# The subject's two cadence eras, established from the median inter-sample gap per day.
ERAS = [
    dict(key="e5", label="5-minute era", start="2026-03-01", end="2026-05-23", nominal=5.0),
    dict(key="e1", label="1-minute era", start="2026-05-23", end="2026-07-31", nominal=1.0),
]

def load(start, end, user=USER):
    with psycopg2.connect(DSN) as c, c.cursor() as cur:
        cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                    "where user_id=%s and cgm_mgdl is not null and ts_utc>=%s and ts_utc<%s "
                    "order by ts_utc", (user, start, end))
        r = cur.fetchall()
    ts = np.array([int(x[0]) for x in r], np.int64)
    bg = np.array([float(x[1]) for x in r], float)
    day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
    return ts, bg, day

def load_eras():
    out = {}
    for e in ERAS:
        ts, bg, day = load(e["start"], e["end"])
        out[e["key"]] = dict(**e, ts=ts, bg=bg, day=day)
    return out

# ---------------------------------------------------------------- uncertainty
def day_bootstrap(stat_fn, groups, n_boot=800, seed=SEED):
    """stat_fn takes an index array; whole days are resampled with replacement."""
    rng = np.random.default_rng(seed)
    du = np.unique(groups)
    idx = {d: np.nonzero(groups == d)[0] for d in du}
    bs = []
    for _ in range(n_boot):
        pick = rng.choice(du, size=len(du), replace=True)
        sel = np.concatenate([idx[d] for d in pick])
        try:
            v = stat_fn(sel)
            if np.isfinite(v): bs.append(float(v))
        except Exception:
            pass
    if not bs: return (float("nan"), float("nan"))
    return float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))

def ci_str(pt, lo, hi, dp=3):
    return f"{pt:.{dp}f} [{lo:.{dp}f}, {hi:.{dp}f}]"

def overlaps(a, b):
    """Do two (lo, hi) intervals overlap?"""
    return not (a[1] < b[0] or b[1] < a[0])

# ---------------------------------------------------------------- variogram
def variogram_pairs(ts, bg, day, lag_min, tol_min):
    """Squared differences at a given lag, with the day of the earlier member of each pair."""
    j = np.searchsorted(ts, ts + int(lag_min*60_000))
    ok = j < len(ts)
    i_ = np.nonzero(ok)[0]; j_ = j[ok]
    keep = np.abs((ts[j_]-ts[i_])/60_000.0 - lag_min) <= tol_min
    return (bg[j_[keep]] - bg[i_[keep]])**2, day[i_[keep]]

def variogram(ts, bg, day, lags, tol_min, n_boot=600, min_pairs=200):
    out = {}
    for L in lags:
        sq, dy = variogram_pairs(ts, bg, day, L, tol_min)
        if len(sq) < min_pairs: continue
        pt = float(sq.mean())
        lo, hi = day_bootstrap(lambda s: sq[s].mean(), dy, n_boot)
        out[L] = dict(D=pt, lo=lo, hi=hi, n=int(len(sq)))
    return out

def loglog_slope(ts, bg, day, lo_lag, hi_lag, tol_min, cand=None, n_boot=300, min_pairs=500):
    """Slope of log D against log tau over a band. 2 = smooth signal, 0 = white noise."""
    cand = cand or [1,2,3,4,5,6,8,10,12,15,20,25,30,40,50,60,90,120]
    L, per = [], []
    for l in cand:
        if not (lo_lag <= l <= hi_lag): continue
        sq, dy = variogram_pairs(ts, bg, day, l, tol_min)
        if len(sq) >= min_pairs: L.append(l); per.append((sq, dy))
    if len(L) < 3: return None
    du = np.unique(np.concatenate([p[1] for p in per]))
    idx = [{d: np.nonzero(p[1] == d)[0] for d in du} for p in per]
    x = np.log(np.array(L, float))
    def fit(pick):
        y = []
        for p, ix in zip(per, idx):
            parts = [p[0][ix[d]] for d in pick if len(ix[d])]
            if not parts: return np.nan
            y.append(np.log(np.concatenate(parts).mean()))
        return float(np.polyfit(x, np.array(y), 1)[0])
    pt = fit(du)
    rng = np.random.default_rng(SEED)
    bs = [fit(rng.choice(du, size=len(du), replace=True)) for _ in range(n_boot)]
    bs = np.array([b for b in bs if np.isfinite(b)])
    return dict(slope=pt, lo=float(np.percentile(bs,2.5)), hi=float(np.percentile(bs,97.5)),
                lags=L)

# ---------------------------------------------------------------- features
def causal_slope(ts, bg, window_min, nominal):
    """OLS slope over the last `window_min` minutes, in mg/dL per 5 min.

    Implemented as a convolution with fixed weights, which is exact for an evenly spaced
    series and fast. Samples whose actual time span deviates from the nominal window by more
    than 30% are invalidated, so gaps do not silently produce a wrong slope.
    """
    k = max(int(round(window_min/nominal)), 1)
    if k < 2: return np.full(len(bg), np.nan)
    x = np.arange(k+1, dtype=float)*nominal
    w = (x - x.mean())/((x - x.mean())**2).sum()*5.0
    out = np.full(len(bg), np.nan)
    conv = np.convolve(bg, w[::-1], mode="valid")     # ends at index k..n-1
    out[k:] = conv[:len(out)-k]
    span = np.full(len(bg), np.inf)
    span[k:] = (ts[k:] - ts[:-k])/60_000.0
    out[np.abs(span - k*nominal) > 0.3*k*nominal] = np.nan
    return out

def causal_delta(ts, bg, back_min, nominal):
    k = max(int(round(back_min/nominal)), 1)
    out = np.full(len(bg), np.nan)
    out[k:] = bg[k:] - bg[:-k]
    span = np.full(len(bg), np.inf)
    span[k:] = (ts[k:] - ts[:-k])/60_000.0
    out[np.abs(span - k*nominal) > 0.3*k*nominal] = np.nan
    return out

def build_features(ts, bg, nominal):
    """Same look-back in MINUTES at both cadences; the faster record simply has more samples
    inside each window."""
    cols, names = [bg], ["bg"]
    for b in (5, 10, 15, 30, 45):
        cols.append(causal_delta(ts, bg, b, nominal)); names.append(f"delta{b}")
    for w in (15, 30, 45):
        cols.append(causal_slope(ts, bg, w, nominal)); names.append(f"slope{w}")
    return np.column_stack(cols), names

# ---------------------------------------------------------------- targets
def future_value(ts, bg, horizon_min, tol_frac=0.3):
    j = np.searchsorted(ts, ts + int(horizon_min*60_000))
    n = len(ts); jj = np.minimum(j, n-1)
    val = np.where(j < n, bg[jj], np.nan)
    span = (ts[jj] - ts)/60_000.0
    val[np.abs(span - horizon_min) > tol_frac*horizon_min] = np.nan
    return val

def future_extreme(ts, bg, horizon_min, kind, tol_frac=0.3):
    """min or max of the signal over (t, t+horizon]."""
    n = len(ts)
    j = np.searchsorted(ts, ts + int(horizon_min*60_000))
    out = np.full(n, np.nan)
    # running extremes via a simple sweep (horizons are short relative to n)
    for i in range(n):
        k = j[i]
        if k >= n: break
        if abs((ts[k]-ts[i])/60_000.0 - horizon_min) > tol_frac*horizon_min: continue
        seg = bg[i:k+1]
        out[i] = seg.min() if kind == "min" else seg.max()
    return out

# ---------------------------------------------------------------- models
def cv_regress(X, y, groups, n_splits=5):
    p = np.zeros(len(y))
    for tr, te in GroupKFold(n_splits=n_splits).split(X, y, groups=groups):
        sc = StandardScaler().fit(X[tr])
        p[te] = LinearRegression().fit(sc.transform(X[tr]), y[tr]).predict(sc.transform(X[te]))
    return p

def cv_classify(X, y, groups, n_splits=5):
    p = np.full(len(y), np.nan)
    for tr, te in GroupKFold(n_splits=n_splits).split(X, y, groups=groups):
        if len(np.unique(y[tr])) < 2: continue
        sc = StandardScaler().fit(X[tr])
        m = LogisticRegression(max_iter=3000).fit(sc.transform(X[tr]), y[tr])
        p[te] = m.predict_proba(sc.transform(X[te]))[:, 1]
    return p

def lift_at_decile(y, p, sel, frac=0.1):
    s = sel[np.isfinite(p[sel])]
    if len(s) < 100: return np.nan
    k = max(int(frac*len(s)), 50)
    top = s[np.argsort(-p[s])[:k]]
    base = y[s].mean()
    return float(y[top].mean()/base) if base > 0 else np.nan

# ---------------------------------------------------------------- io
def save(name, obj):
    os.makedirs(RESULTS, exist_ok=True)
    path = os.path.join(RESULTS, name)
    with open(path, "w") as f:
        json.dump(obj, f, indent=1, default=float)
    print(f"  -> {path}")

def read(name):
    with open(os.path.join(RESULTS, name)) as f:
        return json.load(f)
