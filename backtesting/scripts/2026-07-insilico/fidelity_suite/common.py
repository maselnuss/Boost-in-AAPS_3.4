#!/usr/bin/env python3
"""Shared helpers for the simulator-fidelity suite: DB loaders (real cohort), the
cached simulator cohort, and small statistics utilities (bootstrap CI, KS, ACF)."""
import os, numpy as np, psycopg2

DSN = "dbname=oref host=127.0.0.1 port=5432"
HERE = os.path.dirname(os.path.abspath(__file__))
SIM_CACHE = os.path.join(HERE, "sim_cohort.npz")

# Okabe-Ito CVD-safe palette
BLUE, ORANGE, GREEN, VERM, GREY = "#0072B2", "#E69F00", "#009E73", "#D55E00", "#8a8a8a"


def conn():
    return psycopg2.connect(DSN)


# ---------------------------------------------------------------- real cohort
def real_users():
    with conn() as c, c.cursor() as cur:
        cur.execute("SELECT DISTINCT user_id FROM boost_cgm ORDER BY 1")
        return [r[0] for r in cur.fetchall()]


def real_cgm(user):
    """Per-user CGM as (ts_epoch seconds, mgdl), time-sorted."""
    with conn() as c, c.cursor() as cur:
        cur.execute(
            "SELECT extract(epoch FROM ts_utc)::bigint, cgm_mgdl "
            "FROM boost_cgm WHERE user_id=%s AND cgm_mgdl IS NOT NULL ORDER BY ts_utc", (user,))
        rows = cur.fetchall()
    a = np.array(rows, dtype=float)
    return a[:, 0], a[:, 1]


def real_deltas(user, lo=240, hi=360):
    """5-min glucose deltas (mg/dL) where the sample gap is ~5 min (lo..hi seconds)."""
    ts, bg = real_cgm(user)
    dt = np.diff(ts)
    dbg = np.diff(bg)
    ok = (dt >= lo) & (dt <= hi)
    return dbg[ok]


def real_col(user, col, where_extra=""):
    """A populated numeric column from boost_decisions for one user, time-ordered."""
    with conn() as c, c.cursor() as cur:
        cur.execute(
            f"SELECT extract(epoch FROM ts_utc)::bigint, {col} FROM boost_decisions "
            f"WHERE user_id=%s AND {col} IS NOT NULL {where_extra} ORDER BY ts_utc", (user,))
        rows = cur.fetchall()
    if not rows:
        return np.array([]), np.array([])
    a = np.array(rows, dtype=float)
    return a[:, 0], a[:, 1]


# ---------------------------------------------------------------- sim cohort
def sim_cohort():
    """dict patient -> cgm array (mg/dL) from the cached simulator run."""
    if not os.path.exists(SIM_CACHE):
        raise FileNotFoundError(f"{SIM_CACHE} missing — run gen_sim_cohort.py first")
    z = np.load(SIM_CACHE, allow_pickle=True)
    pats = list(z["patients"])
    return {p: z[f"cgm_{p}"] for p in pats}


SIM_DT = 180  # simglucose CGM cadence (Dexcom sensor sample_time = 3 min)


def sim_5min(cgm):
    """Resample a 3-min-cadence sim CGM series onto a 5-min grid (linear interp),
    so every real-vs-sim comparison is at the same 5-min cadence."""
    cgm = np.asarray(cgm, float)
    t = np.arange(len(cgm)) * SIM_DT
    grid = np.arange(0, t[-1] + 1, 300)
    return np.interp(grid, t, cgm)


def sim_deltas_5min(cgm):
    return np.diff(sim_5min(cgm))


def sim_ts_5min(cgm):
    """Matching 5-min-grid timestamps (seconds) for sim_5min output."""
    n = len(sim_5min(cgm))
    return np.arange(n) * 300.0


# ---------------------------------------------------------------- statistics
def boot_ci(vals, stat=np.mean, n=2000, seed=0, lo=2.5, hi=97.5):
    vals = np.asarray(vals, dtype=float)
    vals = vals[np.isfinite(vals)]
    if len(vals) == 0:
        return (np.nan, np.nan, np.nan)
    rng = np.random.default_rng(seed)
    point = stat(vals)
    idx = rng.integers(0, len(vals), size=(n, len(vals)))
    bs = np.array([stat(vals[i]) for i in idx])
    return float(point), float(np.percentile(bs, lo)), float(np.percentile(bs, hi))


def acf(x, lags):
    """Autocorrelation of a 1-D series at the given integer lags."""
    x = np.asarray(x, dtype=float)
    x = x - x.mean()
    denom = np.dot(x, x)
    return np.array([np.dot(x[:-L], x[L:]) / denom if L else 1.0 for L in lags])


def cv(cgm):
    cgm = np.asarray(cgm, dtype=float)
    return 100 * cgm.std() / cgm.mean()
