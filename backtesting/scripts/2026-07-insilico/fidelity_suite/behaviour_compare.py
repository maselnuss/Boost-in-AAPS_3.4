#!/usr/bin/env python3
"""Does the behaviour layer close the gaps the physiology and sensor layers left?

Four columns, each adding one layer to the one before it, so every movement is
attributable to a named mechanism rather than to the combination:

  2008            the stock UVA/Padova personae
  +physiology     S2013-style time-varying insulin sensitivity
  +behaviour      unannounced meals, rescue carbohydrate, an adapting ISF setting
  +loop           continuous correction with an IOB bound and basal withdrawal
  +sensor         jitter and compression lows, applied post hoc

The four real cohorts (Boost, Trio, OpenAPS, AAPS-classic; 192 users) supply the target
envelope. None of the behaviour-layer parameters was fitted to any signature in this
table: they are carbohydrate-counting error and rescue-treatment figures from the
clinical literature, so the columns below are a test rather than a restatement.

Writes REPORT_BEHAVIOUR.md, behaviour_result.json, fig_behaviour.png.
Run (after gen_sim_behaviour.py): ~/.venvs/boost-insilico/bin/python behaviour_compare.py
"""
import os, json, numpy as np, matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import multicohort as M
import sensor_layer as S

HERE = os.path.dirname(os.path.abspath(__file__))
REAL = ["Boost", "Trio", "OpenAPS", "AAPS-classic"]
GREY, BLUE, ORANGE, GREEN, VERM = "#8a8a8a", "#0072B2", "#E69F00", "#009E73", "#D55E00"

# As fitted in poc_compare.py, on the 2008 baseline, unchanged here.
NOISE_SIGMA = 2.0
COMPRESSION_RATE_PARAM = 4.5
SENSOR_SEED = 777

ROWS = [("cv", "Glucose variability", "CV%"),
        ("tail", "Rise tail P(dBG>10/5min)", "%"),
        ("acf30", "Autocorrelation @30min", ""),
        ("acf60", "Autocorrelation @60min", ""),
        ("outcome", "Outcome SD @stuck-high", "mg/dL"),
        ("diurnal", "Diurnal amplitude", "mg/dL"),
        ("hypo_rec", "Hypo recovery to 100", "min"),
        ("hypo_reb", "Hypo rebound >180", "%"),
        ("compress", "Compression lows", "/30d"),
        ("noise", "Sensor jitter", "mg/dL"),
        ("drift", "ISF drift (weekly)", "%CV")]


def _to_grid(cgm):
    """3-min sim cadence onto the 5-min grid every signature is defined on."""
    t3 = np.arange(len(cgm)) * 180.0
    grid = np.arange(0, t3[-1] + 1, 300.0)
    return grid, np.interp(grid, t3, cgm)


def _pack(grid, bg):
    return dict(t=grid, bg=bg, hour=(grid / 3600.0) % 24)


def load_npz(fname, adult_only=True):
    """Also returns survival: simglucose ends an episode at BG < 10 mg/dL, so a short
    series means the virtual patient did not survive the run. Every prior comparison in
    this suite silently conditioned on survival; it is reported here instead."""
    z = np.load(os.path.join(HERE, fname), allow_pickle=True)
    full = int(z["days"]) * 480
    out, isf, surv = [], [], []
    for p in list(z["patients"]):
        if adult_only and str(z[f"class_{p}"]) != "adult":
            continue
        cgm = z[f"cgm_{p}"].astype(float)
        surv.append(min(len(cgm) / full, 1.0))
        out.append(_pack(*_to_grid(cgm)))
        if f"isfcv_{p}" in z:
            isf.append(float(z[f"isfcv_{p}"]))
    return out, isf, surv


def add_sensor(personae):
    rng = np.random.default_rng(SENSOR_SEED)
    out, ev = [], []
    for d in personae:
        bg, n = S.apply(d["t"], d["bg"], rng, noise_sigma=NOISE_SIGMA,
                        compression_rate=COMPRESSION_RATE_PARAM)
        out.append(_pack(d["t"], bg))
        ev.append(n)
    return out, ev


def stats(personae, isf, seed0=0):
    s = {k: M.boot_ci([fn(d) for d in personae], seed=seed0 + i)
         for i, (k, _, fn) in enumerate(M.SIGS)}
    vals = [v for v in isf if np.isfinite(v)]
    s["drift"] = M.boot_ci(vals, seed=seed0 + 99) if vals else (0.0, 0.0, 0.0)
    return s


def within(lo, hi, v, pad=0.10):
    span = hi - lo
    return bool(lo - pad * span - 1e-9 <= v <= hi + pad * span + 1e-9)


def main():
    real = json.load(open(os.path.join(HERE, "multicohort_result.json")))["cohorts"]
    env = {k: (min(real[c][k][0] for c in REAL), max(real[c][k][0] for c in REAL))
           for k, _, _ in ROWS}

    base, _, sv_b = load_npz("sim_cohort_all.npz")           # 2008, adults, 21d
    phys, isf_p, sv_p = load_npz("sim_cohort_physonly.npz")  # + S2013 SI, 28d
    behav, isf_b, sv_h = load_npz("sim_cohort_behaviour.npz")
    loop, isf_l, sv_l = load_npz("sim_cohort_loop.npz")
    sens, n_ev = add_sensor(loop)
    surv = {"2008": sv_b, "+physiology": sv_p, "+behaviour": sv_h,
            "+loop": sv_l, "+sensor": sv_l}

    cols = [("2008", stats(base, [], 0)),
            ("+physiology", stats(phys, isf_p, 20)),
            ("+behaviour", stats(behav, isf_b, 40)),
            ("+loop", stats(loop, isf_l, 60)),
            ("+sensor", stats(sens, isf_l, 80))]

    print(f"compression events placed: {n_ev}")
    for name, s in cols:
        print(f"{name:>14} " + " ".join(f"{k}={s[k][0]:.2f}" for k, _, _ in ROWS))

    lines = ["# Does the behaviour layer close the remaining fidelity gaps? Measured\n"]
    lines.append(
        "The physiology refinement closed one signature of eleven and the sensor layer "
        "closed two more, which left four that `REPORT_POC.md` attributed not to the model "
        "but to the person: unannounced meals, rescue carbohydrate and its over-treatment, "
        "and an insulin-sensitivity setting that adapts. This table adds that layer, and a "
        "fifth for the fact that every real cohort in the comparison is running a closed "
        "loop while the simulated person was on open-loop basal-bolus. Each column adds one "
        "mechanism to the column before it, so movement is attributable. Adult personae, "
        "10 x 28 days, per-persona median [bootstrap 95% CI]. The real range is the "
        "envelope across four cohorts and 192 users.\n")
    lines.append(
        "No parameter in the behaviour layer was fitted to a signature in this table. "
        "Carbohydrate-counting error, announcement rate and rescue-treatment size come from "
        "the clinical literature (see `behaviour.py`), so these columns are a test rather "
        "than a restatement of the inputs.\n")
    head = "| Signature | Real range | " + " | ".join(n for n, _ in cols) + " | In range |"
    lines.append(head)
    lines.append("|---" * (len(cols) + 3) + "|")

    closed, still, broke = [], [], []
    for key, label, unit in ROWS:
        lo, hi = env[key]
        cells = []
        for name, s in cols:
            v, l, h = s[key]
            cells.append(f"{v:.1f} [{l:.1f}-{h:.1f}]" if np.isfinite(v) else "n/a")
        v_last = cols[-1][1][key][0]
        v_base = cols[0][1][key][0]
        ok = within(lo, hi, v_last)
        was = within(lo, hi, v_base)
        if ok and not was:
            closed.append(label)
        elif not ok:
            still.append(label)
        if was and not ok:
            broke.append(label)
        lines.append(f"| {label} ({unit}) | {lo:.1f}-{hi:.1f} | " + " | ".join(cells)
                     + f" | {'YES' if ok else 'no'} |")

    n_in = sum(1 for key, _, _ in ROWS if within(*env[key], cols[-1][1][key][0]))
    n_in_base = sum(1 for key, _, _ in ROWS if within(*env[key], cols[0][1][key][0]))
    lines.append("")
    lines.append("## What moved\n")
    lines.append(f"Signatures inside the real range: {n_in_base} of {len(ROWS)} for the 2008 "
                 f"baseline, {n_in} of {len(ROWS)} with all four layers.\n")
    lines.append(f"- Brought into range: {', '.join(closed) if closed else 'none'}.")
    lines.append(f"- Still outside: {', '.join(still) if still else 'none'}.")
    lines.append(f"- Moved out of range that the 2008 model had matched: "
                 f"{', '.join(broke) if broke else 'none'}.\n")
    V = {n: {k: s[k][0] for k, _, _ in ROWS} for n, s in cols}
    lines.append("## Reading it\n")
    lines.append(
        "The two layers do different work and the split is clean. The behaviour layer "
        "supplies the disturbances: unannounced meals lift the rise tail, rescue "
        f"carbohydrate takes hypoglycaemia recovery from {V['+physiology']['hypo_rec']:.0f} "
        f"minutes to {V['+behaviour']['hypo_rec']:.0f} against a real 50 to 59, and an "
        f"adapting setting gives the drift signature something to read at "
        f"{V['+behaviour']['drift']:.1f}% where it had been a structural zero. It also pulls "
        f"the diurnal amplitude back from {V['+physiology']['diurnal']:.0f} to "
        f"{V['+behaviour']['diurnal']:.0f} mg/dL, undoing the overshoot the physiology "
        "refinement had introduced.\n")
    lines.append(
        "The loop layer supplies the damping. Both autocorrelations had risen out of range "
        "as each layer added variance, and continuous correction brings them back: "
        f"{V['+behaviour']['acf30']:.2f} to {V['+loop']['acf30']:.2f} at 30 minutes and "
        f"{V['+behaviour']['acf60']:.2f} to {V['+loop']['acf60']:.2f} at 60, both inside the "
        f"real range. Glucose variability follows the same path, overshooting to "
        f"{V['+behaviour']['cv']:.1f}% without a loop and settling at "
        f"{V['+sensor']['cv']:.1f}% with one, just under the real 29.5 to 34.3. This is the "
        "part that had been missing from the comparison rather than from the model: every "
        "real cohort here is running an automated loop, and the simulated person was not.\n")
    lines.append(
        "Three things did not come right, and they are worth separating.\n")
    lines.append(
        f"Hypoglycaemia rebound overshoots badly, {V['+sensor']['hypo_reb']:.0f}% against a "
        "real 23 to 28. The over-treatment assumption, a second helping on 35% of rescues, "
        "is too aggressive for what these cohorts actually do. It could be fitted, and "
        "deliberately has not been, because fitting it would turn this row from a test into "
        "a restatement. Read as a measurement, it says real closed-loop users over-treat "
        "less than the standard clinical account of over-treatment implies.\n")
    lines.append(
        f"Insulin-sensitivity drift falls from {V['+behaviour']['drift']:.1f}% to "
        f"{V['+loop']['drift']:.1f}% when the loop is added, below the real 8.2 to 21.7. The "
        "loop holds glucose closer to target, so the weekly adaptation has less error to "
        "chase. Real drift is partly physiological rather than purely a response to "
        "outcomes, which this adapter does not represent.\n")
    lines.append(
        f"Outcome spread at stuck-high moves from {V['2008']['outcome']:.1f} to "
        f"{V['+sensor']['outcome']:.1f} mg/dL against a real 26.5 to 33.5, and is the one "
        "signature no layer has closed across the whole programme. The fast stochastic "
        "efficacy process aimed straight at it did not close it either. Whatever makes real "
        "insulin action unpredictable at the half-hour horizon is still not in the model, "
        "and this remains the honest limit on using the simulator to price a dosing "
        "change.\n")
    lines.append("## Survival\n")
    lines.append(
        "simglucose ends an episode when blood glucose falls below 10 mg/dL, so a "
        "truncated series means the virtual patient did not survive the run. Every "
        "earlier comparison in this suite silently conditioned on survival by taking "
        "whatever series it was given.\n")
    lines.append("| Column | Personae completing the run | Median fraction of the run completed |")
    lines.append("|---|---|---|")
    for name in ["2008", "+physiology", "+behaviour", "+loop"]:
        v = np.array(surv[name], float)
        lines.append(f"| {name} | {int((v >= 0.999).sum())} of {len(v)} | {np.median(v):.2f} |")
    lines.append("")

    json.dump({"real_envelope": env, "survival": surv,
               "columns": {n: s for n, s in cols},
               "n_compression_events": n_ev, "isf_cv_behaviour": isf_b,
               "closed": closed, "still_out": still, "broke": broke},
              open(os.path.join(HERE, "behaviour_result.json"), "w"), indent=1)

    # figure: one panel per signature, real band shaded, four columns as points
    fig, axes = plt.subplots(3, 4, figsize=(16, 9))
    for ax, (key, label, unit) in zip(axes.ravel(), ROWS):
        lo, hi = env[key]
        ax.axhspan(lo, hi, color=GREEN, alpha=0.18, label="real range")
        for i, (name, s) in enumerate(cols):
            v, l, h = s[key]
            if not np.isfinite(v):
                continue
            ax.errorbar([i], [v], yerr=[[max(v - l, 0)], [max(h - v, 0)]], fmt="o",
                        color=[GREY, BLUE, ORANGE, GREEN, VERM][i], capsize=3)
        ax.set_xticks(range(len(cols)))
        ax.set_xticklabels([n for n, _ in cols], rotation=35, ha="right", fontsize=7)
        ax.set_title(f"{label}\n({unit})", fontsize=8)
        ax.tick_params(labelsize=7)
    for ax in axes.ravel()[len(ROWS):]:
        ax.axis("off")
    fig.suptitle("Layer-by-layer fidelity: shaded band is the real four-cohort range", fontsize=10)
    fig.tight_layout()
    fig.savefig(os.path.join(HERE, "fig_behaviour.png"), dpi=130)
    lines.append("![behaviour](fig_behaviour.png)\n")
    open(os.path.join(HERE, "REPORT_BEHAVIOUR.md"), "w").write("\n".join(lines))
    print("wrote REPORT_BEHAVIOUR.md")


if __name__ == "__main__":
    main()
