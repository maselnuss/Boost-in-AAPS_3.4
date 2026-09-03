#!/usr/bin/env python3
"""Run the simulator-fidelity suite: every signature, real vs simulator, verdict table,
one figure, and a written REPORT. Reproducible from the DB + cached sim cohort.

Run:  ~/.venvs/boost-insilico/bin/python run_suite.py
"""
import os, numpy as np, matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import common as C
import signatures as S

HERE = os.path.dirname(os.path.abspath(__file__))


def load_real():
    users = C.real_users()
    return {u: C.real_cgm(u) for u in users}


def figure(real, sim, path):
    fig, ax = plt.subplots(2, 2, figsize=(11, 8))
    # (a) CV distribution
    rcv = [C.cv(bg) for _, bg in real.values()]
    scv = [C.cv(cgm) for cgm in sim.values()]
    ax[0, 0].hist(rcv, bins=np.arange(15, 55, 3), color=C.BLUE, alpha=0.75, label="real")
    ax[0, 0].hist(scv, bins=np.arange(15, 55, 3), color=C.ORANGE, alpha=0.75, label="sim")
    ax[0, 0].set_title("Glucose variability (CV%)"); ax[0, 0].set_xlabel("CV %"); ax[0, 0].legend()
    # (b) 5-min delta distribution (log y)
    rd = np.concatenate([S._deltas_from_series(ts, bg) for ts, bg in real.values()])
    sd = np.concatenate([C.sim_deltas_5min(cgm) for cgm in sim.values()])
    b = np.arange(-40, 41, 2)
    ax[0, 1].hist(rd, bins=b, density=True, histtype="step", color=C.BLUE, lw=2, label="real")
    ax[0, 1].hist(sd, bins=b, density=True, histtype="step", color=C.ORANGE, lw=2, label="sim")
    ax[0, 1].set_yscale("log"); ax[0, 1].set_title("5-min glucose delta (density)")
    ax[0, 1].set_xlabel("mg/dL per 5 min"); ax[0, 1].legend()
    # (c) ACF curves
    lags = np.arange(0, 25)
    r = np.mean([C.acf(bg, lags) for _, bg in real.values()], axis=0)
    s = np.mean([C.acf(C.sim_5min(cgm), lags) for cgm in sim.values()], axis=0)
    ax[1, 0].plot(lags * 5, r, color=C.BLUE, lw=2, label="real")
    ax[1, 0].plot(lags * 5, s, color=C.ORANGE, lw=2, label="sim")
    ax[1, 0].set_title("Autocorrelation"); ax[1, 0].set_xlabel("lag (min)"); ax[1, 0].legend()
    # (d) outcome spread from the 180-240 band
    rr = np.concatenate([S._within_band_outcome_sd(ts, bg, 180, 240) for ts, bg in real.values()])
    ss = []
    for cgm in sim.values():
        ss.append(S._within_band_outcome_sd(C.sim_ts_5min(cgm), C.sim_5min(cgm), 180, 240))
    ss = np.concatenate(ss)
    bb = np.arange(-120, 121, 8)
    ax[1, 1].hist(rr, bins=bb, density=True, histtype="step", color=C.BLUE, lw=2, label="real")
    ax[1, 1].hist(ss, bins=bb, density=True, histtype="step", color=C.ORANGE, lw=2, label="sim")
    ax[1, 1].set_title("Where you are 30 min after BG 180-240")
    ax[1, 1].set_xlabel("ΔBG over 30 min (mg/dL)"); ax[1, 1].legend()
    for a in ax.flat:
        a.spines[["top", "right"]].set_visible(False)
    fig.tight_layout(); fig.savefig(path, dpi=130); plt.close(fig)


def figure2(real, sim, path):
    fig, ax = plt.subplots(2, 2, figsize=(11, 8))
    # (a) diurnal hour-of-day mean profile
    def prof(ts, bg):
        hod = (np.asarray(ts, float) / 3600.0) % 24
        return np.array([bg[(hod >= h) & (hod < h + 1)].mean() for h in range(24)])
    rp = np.nanmean([prof(ts, bg) for ts, bg in real.values()], axis=0)
    sp = np.nanmean([prof(C.sim_ts_5min(c), C.sim_5min(c)) for c in sim.values()], axis=0)
    ax[0, 0].plot(range(24), rp, color=C.BLUE, lw=2, label="real")
    ax[0, 0].plot(range(24), sp, color=C.ORANGE, lw=2, label="sim")
    ax[0, 0].set_title("Diurnal profile (mean BG by hour, UTC)")
    ax[0, 0].set_xlabel("hour of day"); ax[0, 0].set_ylabel("mg/dL"); ax[0, 0].legend()
    # (b) hypo recovery time distribution
    def rec_times(ts, bg, dt=300):
        ts = np.asarray(ts, float); bg = np.asarray(bg, float)
        below = bg < 70; onset = np.where(below[1:] & ~below[:-1])[0] + 1
        out = []
        for i in onset:
            end = min(i + int(3 * 3600 / dt), len(bg) - 1)
            seg_t, seg_b = ts[i:end + 1], bg[i:end + 1]
            r = np.where(seg_b >= 100)[0]
            if len(r):
                out.append((seg_t[r[0]] - seg_t[0]) / 60.0)
        return out
    rr = np.concatenate([rec_times(ts, bg) for ts, bg in real.values()]) if real else np.array([])
    ssv = np.concatenate([rec_times(C.sim_ts_5min(c), C.sim_5min(c)) for c in sim.values()])
    bb = np.arange(0, 185, 10)
    ax[0, 1].hist(rr, bins=bb, density=True, histtype="step", color=C.BLUE, lw=2, label="real")
    ax[0, 1].hist(ssv, bins=bb, density=True, histtype="step", color=C.ORANGE, lw=2, label="sim")
    ax[0, 1].set_title("Hypo recovery time (<70 to >=100)")
    ax[0, 1].set_xlabel("minutes"); ax[0, 1].legend()
    # (c) compression lows per 30 days, per user vs sim
    rc = [S._compression_lows(ts, bg) for ts, bg in real.values()]
    sc = [S._compression_lows(C.sim_ts_5min(c), C.sim_5min(c)) for c in sim.values()]
    ax[1, 0].bar([0], [np.median(rc)], width=0.6, color=C.BLUE, label="real")
    ax[1, 0].bar([1], [np.median(sc)], width=0.6, color=C.ORANGE, label="sim")
    ax[1, 0].set_xticks([0, 1]); ax[1, 0].set_xticklabels(["real", "sim"])
    ax[1, 0].set_title("Compression lows (median per 30 days)"); ax[1, 0].set_ylabel("events")
    # (d) sensor-noise jitter per user
    def jit(ts, bg, lo=240, hi=360):
        ts = np.asarray(ts, float); bg = np.asarray(bg, float)
        d2 = np.diff(np.diff(bg)); g = np.diff(ts)
        ok = (g[:-1] >= lo) & (g[:-1] <= hi) & (g[1:] >= lo) & (g[1:] <= hi)
        return np.std(d2[ok]) if np.any(ok) else np.nan
    rj = [jit(ts, bg) for ts, bg in real.values()]
    sj = [np.std(np.diff(np.diff(C.sim_5min(c)))) for c in sim.values()]
    ax[1, 1].hist(rj, bins=np.arange(0, 12, 1), color=C.BLUE, alpha=0.75, label="real")
    ax[1, 1].hist(sj, bins=np.arange(0, 12, 1), color=C.ORANGE, alpha=0.75, label="sim")
    ax[1, 1].set_title("Sensor-noise jitter (2nd-diff SD)")
    ax[1, 1].set_xlabel("mg/dL"); ax[1, 1].legend()
    for a in ax.flat:
        a.spines[["top", "right"]].set_visible(False)
    fig.tight_layout(); fig.savefig(path, dpi=130); plt.close(fig)


def main():
    print("loading real cohort ...", flush=True)
    real = load_real()
    print(f"  {len(real)} users", flush=True)
    sim = C.sim_cohort()
    print(f"loaded sim cohort: {len(sim)} patients", flush=True)

    results = []
    for fn in S.SIGNATURES:
        r = fn(real, sim)
        results.append(r)
        print(f"  [{r['verdict']:10}] {r['name']}: {r['metric']}", flush=True)

    figure(real, sim, os.path.join(HERE, "fig_fidelity.png"))
    figure2(real, sim, os.path.join(HERE, "fig_fidelity2.png"))

    # verdict table
    n_fail = sum(r["verdict"] == "FAIL" for r in results)
    n_struct = sum(r["verdict"] == "STRUCTURAL" for r in results)
    n_pass = sum(r["verdict"] == "PASS" for r in results)
    print(f"\nSUMMARY: {n_pass} PASS, {n_fail} FAIL, {n_struct} STRUCTURAL "
          f"(of {len(results)} signatures)")
    write_report(results, real, sim, n_pass, n_fail, n_struct)


def _fmt(v):
    if isinstance(v, tuple):
        return "/".join(f"{x}" for x in v)
    if isinstance(v, float):
        return f"{v:.1f}"
    return str(v)


def write_report(results, real, sim, n_pass, n_fail, n_struct):
    p = os.path.join(HERE, "REPORT.md")
    lines = []
    lines.append("# Simulator-fidelity suite: where UVA/Padova diverges from real data\n")
    lines.append(f"**Cohort:** {len(real)} real users (~1 year each, 2025-08 to 2026-07) "
                 f"vs {len(sim)} UVA/Padova virtual patients (21 days, randomised announced meals).  ")
    lines.append(f"**Result:** {n_fail} FAIL, {n_struct} STRUCTURAL, {n_pass} PASS "
                 f"of {len(results)} signatures.\n")
    lines.append("Each signature computes the same statistic on both cohorts. PASS = the "
                 "simulator reproduces the real statistic; FAIL = it diverges; STRUCTURAL = "
                 "the mechanism is absent from the model by construction.\n")
    lines.append("| Signature | Category | Real | Sim | Verdict |")
    lines.append("|---|---|---|---|---|")
    for r in results:
        real_s = _fmt(r["real"]) + (f" [{r['real_ci'][0]:.0f}-{r['real_ci'][1]:.0f}]"
                                    if r.get("real_ci") else "")
        sim_s = _fmt(r["sim"]) + (f" [{r['sim_ci'][0]:.0f}-{r['sim_ci'][1]:.0f}]"
                                  if r.get("sim_ci") else "")
        lines.append(f"| {r['name']} | {r['category']} | {real_s} | {sim_s} | **{r['verdict']}** |")
    lines.append("\n![fidelity](fig_fidelity.png)\n")
    lines.append("![fidelity 2](fig_fidelity2.png)\n")
    lines.append("## What this means\n")
    lines.append(
        "The simulator does not fail everywhere. It reproduces short-horizon autocorrelation "
        "and the gross diurnal swing, so for smooth, benign, announced-meal stretches it is a "
        "fair stand-in and remains usable for dosing-logic regression and sanity checks. It "
        "fails in a consistent direction on everything that makes our problem hard:\n")
    lines.append(
        "- **It runs too smooth.** Lower CV, thin delta tails, slower decorrelation. The fat "
        "positive delta tails it misses are exactly the unannounced-meal onsets that dominate "
        "our real highs, and its controller is told the carbs in advance.\n"
        "- **Its insulin always works.** From a stuck-high band the sim reliably falls about "
        "20 mg/dL over 30 min with little spread; reality is a coin-toss between climbing "
        "further and crashing (1.5x the spread, and Probe B shows the glucodynamics are "
        "deterministic to 0.00 across identical repeats). The efficacy blind spot is not in "
        "the model.\n"
        "- **It never changes.** Real insulin sensitivity drifts ~22% week to week; the "
        "virtual patient's parameters are fixed. And it has no exercise input at all.\n"
        "- **Its lows behave differently.** Real hypos recover about twice as fast and "
        "overshoot far more often, because people eat to treat them; the sim has no rescue "
        "carbohydrate and recovers only by withdrawing insulin.\n"
        "- **Its sensor is too clean.** Real CGM carries roughly twice the high-frequency "
        "jitter of the Dexcom noise model, and produces reversing compression lows the model "
        "has no mechanism for at all.\n")
    lines.append(
        "So a controller A/B on this simulator would score both controllers safe in precisely "
        "the regimes where real controllers crash (exercise), over-correct (efficacy), get "
        "caught out by an unannounced meal or a sensitivity shift, or react to a sensor "
        "artefact. The 'no counterfactual' caveat stays, now measured signature by signature "
        "rather than asserted. The suite is extensible: each new signature is one function in "
        "`signatures.py`.\n")
    lines.append("## Per-signature notes\n")
    for r in results:
        lines.append(f"- **{r['name']}** — {r['metric']}. {r['note']}")
    lines.append("\n## Reproduce\n")
    lines.append("```\n~/.venvs/boost-insilico/bin/python gen_sim_cohort.py --days 21\n"
                 "~/.venvs/boost-insilico/bin/python run_suite.py\n```\n")
    open(p, "w").write("\n".join(lines))
    print(f"wrote {p}")


if __name__ == "__main__":
    main()
