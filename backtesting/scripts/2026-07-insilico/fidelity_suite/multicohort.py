#!/usr/bin/env python3
"""Multi-cohort fidelity engine: compares the FDA-accepted UVA/Padova (simglucose)
in-silico personae against several independent real-world AID datasets.

Real cohorts (local TimescaleDB):
  Boost        boost_cgm            9 users     (fully closed loop)
  Trio         oref_v5             29 users     (Trio / iAPS lineage)
  OpenAPS      oref_v7            110 users     (oref0 / OpenAPS Commons)
  AAPS-classic oref_v6             44 users     (AndroidAPS pre-dynISF)

Sim cohorts (sim_cohort_all.npz, from gen_sim_all_personae.py):
  Padova adult / adolescent / child  — 10 personae each, all 30 UVA/Padova subjects.

Each signature is computed PER USER (or per persona), then aggregated as a median with
a bootstrap CI over users — the per-user-then-pooled design. The output is a
signature x cohort matrix that shows where the simulator matches real data and where it
does not, and crucially whether ANY persona class matches, since the three age classes
have very different physiology.

Run:  ~/.venvs/boost-insilico/bin/python multicohort.py
"""
import os, json, numpy as np, psycopg2

DSN = "dbname=oref host=127.0.0.1 port=5432"
HERE = os.path.dirname(os.path.abspath(__file__))
SIM_ALL = os.path.join(HERE, "sim_cohort_all.npz")

BLUE, ORANGE, GREEN, VERM, PURPLE, GREY = \
    "#0072B2", "#E69F00", "#009E73", "#D55E00", "#CC79A7", "#8a8a8a"

REAL_COHORTS = {
    "Boost":        dict(engine="boost"),
    "Trio":         dict(engine="oref", table="oref_v5", isf='"sug_ISF"'),
    "OpenAPS":      dict(engine="oref", table="oref_v7", isf="sug_isf"),
    "AAPS-classic": dict(engine="oref", table="oref_v6", isf="sug_isf"),
}
SIM_CLASSES = ["adult", "adolescent", "child"]


def conn():
    return psycopg2.connect(DSN)


# ----------------------------------------------------------------- real loaders
def real_users(cfg):
    tbl = "boost_cgm" if cfg["engine"] == "boost" else cfg["table"]
    with conn() as c, c.cursor() as cur:
        cur.execute(f"SELECT DISTINCT user_id FROM {tbl} ORDER BY 1")
        return [r[0] for r in cur.fetchall()]


def real_user(cfg, uid):
    """Return dict(t=seconds, bg=mg/dL, hour=0..23) time-ordered for one user."""
    with conn() as c, c.cursor() as cur:
        if cfg["engine"] == "boost":
            cur.execute(
                "SELECT extract(epoch FROM ts_utc)::bigint, cgm_mgdl, "
                "extract(hour FROM ts_utc)::int FROM boost_cgm "
                "WHERE user_id=%s AND cgm_mgdl IS NOT NULL ORDER BY ts_utc", (uid,))
        else:
            cur.execute(
                f"SELECT ts_relative_sec, cgm_mgdl, hour FROM {cfg['table']} "
                f"WHERE user_id=%s AND cgm_mgdl IS NOT NULL ORDER BY ts_relative_sec", (uid,))
        rows = cur.fetchall()
    a = np.array(rows, dtype=float)
    return dict(t=a[:, 0], bg=a[:, 1], hour=a[:, 2])


def real_isf_weekly_cv(cfg, uid):
    """CV of weekly-median algorithm ISF (drift). ISF clipped to a physiological range."""
    with conn() as c, c.cursor() as cur:
        if cfg["engine"] == "boost":
            cur.execute(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY variable_sens) "
                "FROM boost_decisions WHERE user_id=%s AND variable_sens BETWEEN 5 AND 400 "
                "GROUP BY date_trunc('week', ts_utc) HAVING count(*)>200", (uid,))
        else:
            isf = cfg["isf"]
            cur.execute(
                f"SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY {isf}) "
                f"FROM {cfg['table']} WHERE user_id=%s AND {isf} BETWEEN 5 AND 400 "
                f"GROUP BY floor(ts_relative_sec/604800) HAVING count(*)>200", (uid,))
        wk = np.array([r[0] for r in cur.fetchall()], float)
    if len(wk) < 6:
        return np.nan
    return 100 * wk.std() / wk.mean()


# ------------------------------------------------------------------ sim loader
def sim_personae():
    """dict class -> list of dict(t,bg,hour) for each persona. The sim CGM is 3-min
    cadence (Dexcom sensor); resample onto a 5-min grid so every signature — including
    the 5-min gap filters and the sample-count windows — operates identically to real."""
    z = np.load(SIM_ALL, allow_pickle=True)
    pats = list(z["patients"])
    out = {c: [] for c in SIM_CLASSES}
    for p in pats:
        cgm = z[f"cgm_{p}"].astype(float)
        cls = str(z[f"class_{p}"])
        t3 = np.arange(len(cgm)) * 180.0
        grid = np.arange(0, t3[-1] + 1, 300.0)          # 5-min grid
        bg = np.interp(grid, t3, cgm)
        out[cls].append(dict(t=grid, bg=bg, hour=(grid / 3600.0) % 24))
    return out


# ------------------------------------------------------------- per-user signatures
def _deltas(d, lo=240, hi=360):
    dt, dbg = np.diff(d["t"]), np.diff(d["bg"])
    return dbg[(dt >= lo) & (dt <= hi)]


def _lag_corr(t, bg, lag_s, tol=90):
    """Autocorrelation at a time lag, gap-robust: match each sample to one lag_s later."""
    j = np.searchsorted(t, t + lag_s)
    j = np.clip(j, 0, len(t) - 1)
    ok = np.abs(t[j] - (t + lag_s)) <= tol
    if ok.sum() < 50:
        return np.nan
    x, y = bg[ok], bg[j][ok]
    xm, ym = x.mean(), y.mean()
    denom = np.sqrt(((x - xm) ** 2).sum() * ((y - ym) ** 2).sum())
    return float(((x - xm) * (y - ym)).sum() / denom) if denom else np.nan


def _within_band_outcome_sd(d, lo=180, hi=240, horizon=1800, tol=240, minn=200):
    t, bg = d["t"], d["bg"]
    j = np.clip(np.searchsorted(t, t + horizon), 0, len(t) - 1)
    good = np.abs(t[j] - (t + horizon)) <= tol
    inband = (bg >= lo) & (bg < hi) & good
    if inband.sum() < minn:
        return np.nan
    return float((bg[j][inband] - bg[inband]).std())


def _diurnal_amp(d):
    h, bg = d["hour"], d["bg"]
    prof = np.array([bg[(h >= k) & (h < k + 1)].mean() if np.any((h >= k) & (h < k + 1))
                     else np.nan for k in range(24)])
    prof = prof[np.isfinite(prof)]
    return float(prof.max() - prof.min()) if len(prof) else np.nan


def _hypo(d, dt_hint=300):
    t, bg = d["t"], d["bg"]
    below = bg < 70
    onset = np.where(below[1:] & ~below[:-1])[0] + 1
    rec, reb = [], []
    for i in onset:
        end = min(i + 40, len(bg) - 1)
        st, sb = t[i:end + 1], bg[i:end + 1]
        r = np.where(sb >= 100)[0]
        if not len(r):
            continue
        rec.append((st[r[0]] - st[0]) / 60.0)
        r2 = min(r[0] + 25, len(sb) - 1)
        reb.append(bool(np.any(sb[r[0]:r2 + 1] > 180)))
    if not rec:
        return np.nan, np.nan
    return float(np.median(rec)), float(np.mean(reb))


def _compression_rate(d):
    t, bg = d["t"], d["bg"]
    below = bg < 70
    onset = np.where(below[1:] & ~below[:-1])[0] + 1
    n = 0
    for i in onset:
        pre = bg[max(0, i - 4):i].mean() if i >= 1 else bg[i]
        w = min(i + 6, len(bg) - 1)
        seg = bg[i:w + 1]
        nadir = seg.min()
        if seg[-1] >= pre - 15 and pre >= 85 and (pre - nadir) > 25:
            n += 1
    span = (t[-1] - t[0]) / 86400.0 if len(t) > 1 else 1
    return 30.0 * n / max(span, 1)


def _noise_jitter(d, lo=240, hi=360):
    t, bg = d["t"], d["bg"]
    d2 = np.diff(np.diff(bg))
    g = np.diff(t)
    ok = (g[:-1] >= lo) & (g[:-1] <= hi) & (g[1:] >= lo) & (g[1:] <= hi)
    return float(np.std(d2[ok])) if np.any(ok) else np.nan


# Each signature: (key, label, per-user fn(d)->scalar, higher_is_lower_freq?) ; drift/exercise special
SIGS = [
    ("cv",          "Glucose variability (CV%)",              lambda d: 100 * d["bg"].std() / d["bg"].mean()),
    ("tail",        "Rise tail P(Δ>10 mg/dL / 5min) %",       lambda d: 100 * np.mean(_deltas(d) > 10) if len(_deltas(d)) else np.nan),
    ("acf30",       "Autocorrelation @30 min",                lambda d: _lag_corr(d["t"], d["bg"], 1800)),
    ("acf60",       "Autocorrelation @60 min",                lambda d: _lag_corr(d["t"], d["bg"], 3600)),
    ("outcome",     "Outcome SD @stuck-high +30min (mg/dL)",  _within_band_outcome_sd),
    ("diurnal",     "Diurnal amplitude (mg/dL)",              _diurnal_amp),
    ("hypo_rec",    "Hypo recovery to 100 (min)",             lambda d: _hypo(d)[0]),
    ("hypo_reb",    "Hypo rebound >180 %",                    lambda d: 100 * _hypo(d)[1] if np.isfinite(_hypo(d)[1]) else np.nan),
    ("compress",    "Compression lows / 30d",                 _compression_rate),
    ("noise",       "Sensor jitter (2nd-diff SD)",            _noise_jitter),
]


def boot_ci(vals, stat=np.median, n=2000, seed=0):
    vals = np.asarray([v for v in vals if np.isfinite(v)], float)
    if len(vals) == 0:
        return (np.nan, np.nan, np.nan)
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(vals), size=(n, len(vals)))
    bs = np.array([stat(vals[i]) for i in idx])
    return float(stat(vals)), float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))


def compute():
    result = {"signatures": [s[1] for s in SIGS] + ["ISF drift (weekly %CV)"],
              "cohorts": {}, "meta": {}}
    # real cohorts (streaming per user)
    for name, cfg in REAL_COHORTS.items():
        users = real_users(cfg)
        peruser = {k: [] for k, _, _ in SIGS}
        drift = []
        for u in users:
            d = real_user(cfg, u)
            if len(d["bg"]) < 500:
                continue
            for k, _, fn in SIGS:
                try:
                    peruser[k].append(fn(d))
                except Exception:
                    peruser[k].append(np.nan)
            drift.append(real_isf_weekly_cv(cfg, u))
        result["cohorts"][name] = {k: boot_ci(peruser[k], seed=i)
                                   for i, (k, _, _) in enumerate(SIGS)}
        result["cohorts"][name]["drift"] = boot_ci(drift, seed=99)
        result["meta"][name] = dict(n_users=len([1 for u in users]), kind="real")
        print(f"[real] {name}: {len(users)} users done", flush=True)
    # sim persona classes
    personae = sim_personae()
    for cls in SIM_CLASSES:
        ds = personae[cls]
        peruser = {k: [fn(d) for d in ds] for k, _, fn in SIGS}
        cohort = {k: boot_ci(peruser[k], seed=i) for i, (k, _, _) in enumerate(SIGS)}
        cohort["drift"] = (0.0, 0.0, 0.0)   # fixed params -> structural zero
        result["cohorts"][f"Padova {cls}"] = cohort
        result["meta"][f"Padova {cls}"] = dict(n_users=len(ds), kind="sim")
        print(f"[sim ] Padova {cls}: {len(ds)} personae done", flush=True)
    return result


if __name__ == "__main__":
    res = compute()
    with open(os.path.join(HERE, "multicohort_result.json"), "w") as f:
        json.dump(res, f, indent=2)
    print("wrote multicohort_result.json")
