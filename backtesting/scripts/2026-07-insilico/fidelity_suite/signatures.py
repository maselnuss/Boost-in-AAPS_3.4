#!/usr/bin/env python3
"""Fidelity signatures. Each computes the SAME statistic on the real cohort (with a
bootstrap CI) and on the simulator cohort, then returns a divergence verdict.

A signature returns a dict:
  name, category, real, real_ci, sim, sim_ci, metric, verdict, note
verdict is PASS (sim reproduces real within tolerance) or FAIL (sim diverges), or
STRUCTURAL (the mechanism is absent from the model by construction — see fidelity_test.py).
"""
import numpy as np
from scipy.stats import ks_2samp, wasserstein_distance
import common as C


# ---- helpers ---------------------------------------------------------------
def _within_band_outcome_sd(ts, bg, lo, hi, horizon_s=1800, tol_s=240):
    """SD of BG(t+30min) - BG(t) for samples with BG(t) in [lo,hi). CGM-only, so it is
    computable identically on real and sim cohorts. Wide SD = outcome unpredictable."""
    ts = np.asarray(ts, float); bg = np.asarray(bg, float)
    j = np.searchsorted(ts, ts + horizon_s)
    j = np.clip(j, 0, len(ts) - 1)
    good = np.abs(ts[j] - (ts + horizon_s)) <= tol_s
    inband = (bg >= lo) & (bg < hi) & good
    d = bg[j][inband] - bg[inband]
    return d


# ---- S1: marginal variability (CV) -----------------------------------------
def s1_cv(real, sim):
    real_cv = [C.cv(bg) for _, bg in real.values()]
    sim_cv = [C.cv(cgm) for cgm in sim.values()]
    rp, rlo, rhi = C.boot_ci(real_cv, np.median, seed=1)
    sp, slo, shi = C.boot_ci(sim_cv, np.median, seed=2)
    # PASS if the sim median CV lands inside the real cohort's CI
    verdict = "PASS" if rlo <= sp <= rhi else "FAIL"
    return dict(name="Glucose variability (CV%)", category="distribution",
                real=rp, real_ci=(rlo, rhi), sim=sp, sim_ci=(slo, shi),
                metric=f"median CV real {rp:.0f}% vs sim {sp:.0f}%",
                verdict=verdict,
                note="CV is the standard glucose-variability index; the sim runs smoother.")


# ---- S2: short-horizon delta tails (unannounced-meal spikes) ----------------
def _deltas_from_series(ts, bg, lo=240, hi=360):
    dt, dbg = np.diff(ts), np.diff(bg)
    return dbg[(dt >= lo) & (dt <= hi)]


def s2_delta_tails(real, sim):
    rd = np.concatenate([_deltas_from_series(ts, bg) for ts, bg in real.values()])
    sd = np.concatenate([C.sim_deltas_5min(cgm) for cgm in sim.values()])
    r_tail = 100 * np.mean(rd > 10)      # P(rise > 10 mg/dL per 5 min)
    s_tail = 100 * np.mean(sd > 10)
    r_sd, s_sd = rd.std(), sd.std()
    ks = ks_2samp(rd, sd).statistic
    verdict = "FAIL" if (r_tail / max(s_tail, 1e-9) > 1.5 or ks > 0.1) else "PASS"
    return dict(name="Short-horizon delta tails (5 min)", category="dynamics",
                real=r_tail, real_ci=None, sim=s_tail, sim_ci=None,
                metric=f"P(rise>10): real {r_tail:.1f}% vs sim {s_tail:.1f}%; "
                       f"SD {r_sd:.1f} vs {s_sd:.1f}; KS {ks:.2f}",
                verdict=verdict,
                note="Fat positive tails are unannounced-meal onsets the sim never sees.")


# ---- S3: autocorrelation / smoothness --------------------------------------
def s3_acf(real, sim):
    lags = [6, 12]   # 30 and 60 min at 5-min cadence
    r = np.mean([C.acf(bg, lags) for _, bg in real.values()], axis=0)
    s = np.mean([C.acf(C.sim_5min(cgm), lags) for cgm in sim.values()], axis=0)
    gap = float(np.max(np.abs(r - s)))
    verdict = "FAIL" if gap > 0.15 else "PASS"
    return dict(name="Autocorrelation (30/60 min)", category="dynamics",
                real=tuple(round(x, 2) for x in r), real_ci=None,
                sim=tuple(round(x, 2) for x in s), sim_ci=None,
                metric=f"ACF@30/60 real {r[0]:.2f}/{r[1]:.2f} vs sim {s[0]:.2f}/{s[1]:.2f}",
                verdict=verdict,
                note="How fast the glucose curve decorrelates; a proxy for smoothness.")


# ---- S4: outcome unpredictability (efficacy determinism) --------------------
def s4_outcome_sd(real, sim):
    """Within a BG band, SD of where you are 30 min later. Real is wide (efficacy and
    absorption vary); sim is narrow (deterministic dynamics + sensor noise only)."""
    LO, HI = 180, 240
    r = np.concatenate([_within_band_outcome_sd(ts, bg, LO, HI)
                        for ts, bg in (real[u] for u in real)])
    # sim CGM is 1-min; build (t,bg) at 1-min then evaluate
    sim_ds = []
    for cgm in sim.values():
        b5 = C.sim_5min(cgm)
        sim_ds.append(_within_band_outcome_sd(C.sim_ts_5min(cgm), b5, LO, HI))
    s = np.concatenate(sim_ds)
    rp, rlo, rhi = C.boot_ci(r, np.std, seed=3)
    sp, slo, shi = C.boot_ci(s, np.std, seed=4)
    ratio = rp / max(sp, 1e-9)
    verdict = "FAIL" if ratio > 1.4 else "PASS"
    return dict(name="Outcome unpredictability (BG 180-240, +30 min)", category="efficacy",
                real=rp, real_ci=(rlo, rhi), sim=sp, sim_ci=(slo, shi),
                metric=f"outcome SD real {rp:.0f} vs sim {sp:.0f} mg/dL  (x{ratio:.1f})",
                verdict=verdict,
                note="Real next-30-min outcome from a stuck-high band is far more spread "
                     "than the sim's. See fidelity_test.py Probe B: sim glucodynamic "
                     "variance across identical repeats is exactly 0.")


# ---- S5: non-stationarity / insulin-sensitivity drift ----------------------
def s5_drift(real, sim):
    """Real weekly-median insulin sensitivity drifts over the year; the sim's patient
    parameters are fixed, so its sensitivity drift is structurally zero."""
    drifts = []
    for u in real:
        with C.conn() as c, c.cursor() as cur:
            cur.execute(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY variable_sens) "
                "FROM boost_decisions WHERE user_id=%s AND variable_sens IS NOT NULL "
                "GROUP BY date_trunc('week', ts_utc) HAVING count(*) > 200", (u,))
            wk = np.array([r[0] for r in cur.fetchall()], float)
        if len(wk) >= 6:
            drifts.append(100 * wk.std() / wk.mean())   # CV of weekly-median sensitivity
    rp, rlo, rhi = C.boot_ci(drifts, np.median, seed=5)
    return dict(name="Insulin-sensitivity drift (weekly, %CV)", category="non-stationarity",
                real=rp, real_ci=(rlo, rhi), sim=0.0, sim_ci=(0.0, 0.0),
                metric=f"weekly-sensitivity CV real {rp:.0f}% vs sim 0% (fixed params)",
                verdict="STRUCTURAL",
                note="The virtual patient's parameters do not change over time; real "
                     "insulin sensitivity drifts week to week. The sim is stationary.")


# ---- S6: exercise counterweight (structural, from Probe A) ------------------
def s6_exercise(real, sim):
    return dict(name="Post-meal-exercise counterweight", category="exercise",
                real="crash rate falls with IOB (32/20/17% by tertile)", real_ci=None,
                sim="not representable", sim_ci=None,
                metric="model input is (CHO, insulin); no exercise term in the ODE",
                verdict="STRUCTURAL",
                note="See fidelity_test.py Probe A and the mechanism report. The "
                     "insulin-independent exercise drain has no input path in the model.")


# ---- S7: diurnal amplitude ---------------------------------------------------
def _diurnal_amplitude(ts, bg):
    """Peak-to-trough of the hour-of-day mean BG profile. Timezone-invariant (a TZ
    shift only rotates the phase), so real and sim are comparable without local time."""
    hod = (np.asarray(ts, float) / 3600.0) % 24
    prof = np.array([bg[(hod >= h) & (hod < h + 1)].mean() if np.any((hod >= h) & (hod < h + 1))
                     else np.nan for h in range(24)])
    prof = prof[np.isfinite(prof)]
    return float(prof.max() - prof.min()) if len(prof) else np.nan


def s7_diurnal(real, sim):
    r = [_diurnal_amplitude(ts, bg) for ts, bg in real.values()]
    s = [_diurnal_amplitude(C.sim_ts_5min(c), C.sim_5min(c)) for c in sim.values()]
    rp, rlo, rhi = C.boot_ci(r, np.median, seed=6)
    sp, slo, shi = C.boot_ci(s, np.median, seed=7)
    verdict = "PASS" if rlo <= sp <= rhi else "FAIL"
    return dict(name="Diurnal amplitude (peak-trough of hourly mean)", category="distribution",
                real=rp, real_ci=(rlo, rhi), sim=sp, sim_ci=(slo, shi),
                metric=f"diurnal swing real {rp:.0f} vs sim {sp:.0f} mg/dL",
                verdict=verdict,
                note="Real days swing more across the clock (dawn plus routine meals at "
                     "steady times); the sim's swing is meal-driven only, and jittered meal "
                     "times smear it. The model has no circadian term.")


# ---- S8: hypo-recovery shape -------------------------------------------------
def _hypo_recovery(ts, bg, dt=300):
    """For each crossing below 70, minutes to recover to >=100, and whether BG then
    overshoots >180 within 2 h. Returns (median_recovery_min, rebound_fraction)."""
    ts = np.asarray(ts, float); bg = np.asarray(bg, float)
    below = bg < 70
    onset = np.where(below[1:] & ~below[:-1])[0] + 1
    rec_times, rebounds = [], []
    for i in onset:
        # recovery: first index >=100 after i, within 3 h
        end = min(i + int(3 * 3600 / dt), len(bg) - 1)
        seg_t, seg_b = ts[i:end + 1], bg[i:end + 1]
        rec = np.where(seg_b >= 100)[0]
        if not len(rec):
            continue
        j = rec[0]
        rec_times.append((seg_t[j] - seg_t[0]) / 60.0)
        # rebound: does BG exceed 180 within 2 h of recovery?
        r2 = min(j + int(2 * 3600 / dt), len(seg_b) - 1)
        rebounds.append(bool(np.any(seg_b[j:r2 + 1] > 180)))
    if not rec_times:
        return np.nan, np.nan
    return float(np.median(rec_times)), float(np.mean(rebounds))


def s8_hypo_recovery(real, sim):
    rr = [_hypo_recovery(ts, bg) for ts, bg in real.values()]
    ss = [_hypo_recovery(C.sim_ts_5min(c), C.sim_5min(c)) for c in sim.values()]
    r_reb = [x[1] for x in rr if np.isfinite(x[1])]
    s_reb = [x[1] for x in ss if np.isfinite(x[1])]
    rp, rlo, rhi = C.boot_ci([x[0] for x in rr if np.isfinite(x[0])], np.median, seed=8)
    sp, slo, shi = C.boot_ci([x[0] for x in ss if np.isfinite(x[0])], np.median, seed=9)
    r_rebm = 100 * np.mean(r_reb) if r_reb else np.nan
    s_rebm = 100 * np.mean(s_reb) if s_reb else np.nan
    # FAIL if recovery time or rebound fraction diverge materially
    verdict = "FAIL" if (abs(rp - sp) > 15 or abs((r_rebm or 0) - (s_rebm or 0)) > 15) else "PASS"
    return dict(name="Hypo recovery (time to 100, rebound)", category="dynamics",
                real=f"{rp:.0f} min, reb {r_rebm:.0f}%", real_ci=None,
                sim=f"{sp:.0f} min, reb {s_rebm:.0f}%", sim_ci=None,
                metric=f"recovery real {rp:.0f} vs sim {sp:.0f} min; "
                       f"rebound>180 real {r_rebm:.0f}% vs sim {s_rebm:.0f}%",
                verdict=verdict,
                note="Real lows recover via rescue carbohydrate and then overshoot; the sim "
                     "has no rescue carbs, so it recovers only by withdrawing insulin.")


# ---- S9: compression lows (sensor artefact) ---------------------------------
def _compression_lows(ts, bg, dt=300):
    """Count sharp dips below 70 that recover to within 15 mg/dL of the pre-dip level
    inside 30 min with a steep descent — the signature of a sensor compression low, not
    a physiological hypo. Returns incidence per 30 days."""
    ts = np.asarray(ts, float); bg = np.asarray(bg, float)
    below = bg < 70
    onset = np.where(below[1:] & ~below[:-1])[0] + 1
    n = 0
    for i in onset:
        pre = bg[max(0, i - 4):i].mean() if i >= 1 else bg[i]
        w = min(i + int(30 * 60 / dt), len(bg) - 1)
        seg = bg[i:w + 1]
        nadir = seg.min()
        # steep descent (> 1.5 mg/dL/min into the dip) and fast full recovery
        drop_rate = (pre - nadir) / max((int(30 * 60 / dt)) * dt / 60.0, 1)
        recovered = seg[-1] >= pre - 15 and pre >= 85
        if recovered and (pre - nadir) > 25 and drop_rate > 1.0:
            n += 1
    span_days = (ts[-1] - ts[0]) / 86400.0 if len(ts) > 1 else 1
    return 30.0 * n / max(span_days, 1)


def s9_compression(real, sim):
    r = [_compression_lows(ts, bg) for ts, bg in real.values()]
    s = [_compression_lows(C.sim_ts_5min(c), C.sim_5min(c)) for c in sim.values()]
    rp, rlo, rhi = C.boot_ci(r, np.median, seed=10)
    sp = float(np.median(s))
    return dict(name="Compression lows (per 30 days)", category="sensor",
                real=rp, real_ci=(rlo, rhi), sim=sp, sim_ci=None,
                metric=f"compression-low rate real {rp:.1f} vs sim {sp:.1f} per 30 days",
                verdict="STRUCTURAL" if sp < 0.5 else ("FAIL" if rp > 1.5 * max(sp, 1e-9) else "PASS"),
                note="Sharp reversing dips from sensor compression. The Dexcom sensor model "
                     "adds noise but has no compression mechanism, so the sim rate is ~0.")


# ---- S10: sensor-noise texture ----------------------------------------------
def s10_noise(real, sim):
    """SD of the second difference of the 5-min series: a high-frequency jitter measure.
    Tests whether the sim's sensor-noise texture matches real CGM. Gap-aware on the real
    side: only triples of consecutive ~5-min-spaced samples contribute, so sensor
    dropouts do not masquerade as noise."""
    def jitter_gapped(ts, bg, lo=240, hi=360):
        ts = np.asarray(ts, float); bg = np.asarray(bg, float)
        d1 = np.diff(bg); gaps = np.diff(ts)
        d2 = np.diff(d1)
        ok = (gaps[:-1] >= lo) & (gaps[:-1] <= hi) & (gaps[1:] >= lo) & (gaps[1:] <= hi)
        return float(np.std(d2[ok])) if np.any(ok) else np.nan
    def jitter_grid(bg):
        return float(np.std(np.diff(np.diff(bg))))
    r = [jitter_gapped(ts, bg) for ts, bg in real.values()]
    s = [jitter_grid(C.sim_5min(c)) for c in sim.values()]
    rp, rlo, rhi = C.boot_ci(r, np.median, seed=11)
    sp, slo, shi = C.boot_ci(s, np.median, seed=12)
    verdict = "PASS" if rlo <= sp <= rhi else "FAIL"
    return dict(name="Sensor-noise texture (2nd-diff SD)", category="sensor",
                real=rp, real_ci=(rlo, rhi), sim=sp, sim_ci=(slo, shi),
                metric=f"jitter real {rp:.1f} vs sim {sp:.1f} mg/dL",
                verdict=verdict,
                note="High-frequency measurement jitter. Tests whether the Dexcom noise "
                     "model's texture matches real sensor noise.")


SIGNATURES = [s1_cv, s2_delta_tails, s3_acf, s4_outcome_sd, s5_drift, s6_exercise,
              s7_diurnal, s8_hypo_recovery, s9_compression, s10_noise]
