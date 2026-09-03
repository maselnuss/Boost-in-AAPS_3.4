#!/usr/bin/env python3
"""Figures for the Diabettech article on sensing and dosing cadence.

Every figure is drawn from data rather than sketched, so a reader who asks where a number came from
can be shown. Figure one is a real one minute trace from this participant's own record with the five
minute samples marked on it. Figure two takes the same trace and measures what a five minute grid
actually costs, which is the wait for the next sample rather than any loss of shape. Figure three is
the study design, which is a diagram rather than a measurement and is drawn as one.

Usage:
  python3 article_figures.py [--day 2025-11-25] [--out-dir .]
"""
from __future__ import annotations

import argparse
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt          # noqa: E402
import numpy as np                        # noqa: E402
import pandas as pd                       # noqa: E402
import psycopg2                           # noqa: E402

DSN = "dbname=oref host=127.0.0.1 port=5432"
HERE = os.path.dirname(os.path.abspath(__file__))

INK = "#1f2933"
ACCENT = "#c2410c"
MUTED = "#94a3b8"
GRID = "#e2e8f0"

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": 10, "axes.labelsize": 10, "axes.titlesize": 11,
    "xtick.labelsize": 9, "ytick.labelsize": 9, "legend.fontsize": 9,
    "axes.spines.top": False, "axes.spines.right": False,
    "axes.edgecolor": "#64748b", "axes.labelcolor": INK, "text.color": INK,
    "xtick.color": "#475569", "ytick.color": "#475569",
    "figure.dpi": 160, "savefig.bbox": "tight", "savefig.pad_inches": 0.25,
    "savefig.facecolor": "white",
})


def load_day(day, require_one_minute=True):
    """Load a day, and refuse it unless it is genuinely a one minute feed.

    This guard exists because the first version of these figures was built on two days that LOOKED
    like one minute data, in that most consecutive gaps were a minute, and were nothing of the sort.
    They were a five minute feed with duplicated entries: readings landing on the same second of the
    minute, hundreds of values repeating the one before, and five minute spacing wherever the
    duplication stopped. A figure captioned "a real one minute sensor" was drawn from it and the
    caption was believed. So the test is not "are the gaps short" but all of the following.
    """
    conn = psycopg2.connect(DSN)
    conn.autocommit = True
    d = pd.read_sql(
        """SELECT ts_utc, cgm_mgdl FROM boost_cgm
           WHERE user_id = 'tim' AND ts_utc::date = %s AND cgm_mgdl BETWEEN 40 AND 400
           ORDER BY ts_utc""", conn, params=(day,))
    conn.close()
    if d.empty:
        return d
    d["t"] = pd.to_datetime(d.ts_utc, utc=True)
    if not require_one_minute:
        return d

    gaps = (d.t.diff().dt.total_seconds() / 60.0).dropna()
    one_min_share = float((gaps.round(1) == 1.0).mean()) if len(gaps) else 0.0
    repeats = float((d.cgm_mgdl.diff() == 0).mean())
    distinct_seconds = int(d.t.dt.second.nunique())
    problems = []
    if len(d) < 1000:
        problems.append(f"only {len(d)} readings, a full one minute day is about 1440")
    if one_min_share < 0.9:
        problems.append(f"only {100 * one_min_share:.0f}% of gaps are one minute")
    if repeats > 0.10:
        problems.append(f"{100 * repeats:.0f}% of readings repeat the previous value, "
                        f"which is what duplication or backfill looks like")
    if distinct_seconds < 30:
        problems.append(f"readings land on only {distinct_seconds} distinct seconds of the minute; "
                        f"a real sensor jitters across all of them")
    if problems:
        raise SystemExit("This day is not a one minute feed, so the figures would misrepresent it:\n"
                         + "\n".join("  " + p for p in problems)
                         + "\n\nPass --allow-any to draw it anyway, and change the captions.")
    return d


def fig_trace(d, path):
    """A real one minute trace, with the five minute samples marked."""
    # The busiest six-hour stretch, so the figure shows movement rather than a flat night.
    d = d.copy()
    d["mgdl"] = d.cgm_mgdl.astype(float)
    t0 = d.t.iloc[0]
    d["min"] = (d.t - t0).dt.total_seconds() / 60.0
    best, bestvar = None, -1
    for start in range(0, int(d["min"].max()) - 360, 30):
        w = d[(d["min"] >= start) & (d["min"] < start + 360)]
        if len(w) < 200:
            continue
        v = w.mgdl.diff().abs().sum()
        if v > bestvar:
            bestvar, best = v, w
    w = best if best is not None else d
    wm = w["min"] - w["min"].iloc[0]

    fig, ax = plt.subplots(figsize=(8.2, 3.6))
    ax.plot(wm, w.mgdl, color=ACCENT, lw=1.3, label="every minute", zorder=3)
    five = w.iloc[::5]
    ax.plot(wm.iloc[::5], five.mgdl, "o", ms=4.5, color=INK,
            label="the same day, every five minutes", zorder=4)
    ax.set_xlabel("minutes")
    ax.set_ylabel("glucose (mg/dL)")
    ax.set_title("Six hours of a real one minute sensor, with the five minute samples marked")
    ax.grid(True, color=GRID, lw=0.8)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, loc="best")
    fig.savefig(path)
    plt.close(fig)
    return len(w)


def fig_latency(d, path):
    """When the five minute view costs something, and when it does not.

    The left panel is the point of the whole study. A held five minute value is almost always right,
    because glucose almost always moves slowly. It is wrong when glucose is moving fast, which is
    exactly when a loop most wants to know.
    """
    d = d.copy()
    d["mgdl"] = d.cgm_mgdl.astype(float)
    idx = np.arange(len(d))
    held = d.mgdl.values[(idx // 5) * 5]          # what a five minute loop would be looking at
    err = np.abs(d.mgdl.values - held)
    # rate of change over the preceding five minutes, in mg/dL per 5 min
    roc = pd.Series(d.mgdl.values).diff(5).abs().values

    ok = np.isfinite(roc) & np.isfinite(err)
    roc, err_ok = roc[ok], err[ok]
    bins = [0, 2, 5, 10, 15, 25, 200]
    labels = ["0 to 2", "2 to 5", "5 to 10", "10 to 15", "15 to 25", "over 25"]
    med, p90, n = [], [], []
    for lo, hi in zip(bins[:-1], bins[1:]):
        m = (roc >= lo) & (roc < hi)
        med.append(np.median(err_ok[m]) if m.sum() else np.nan)
        p90.append(np.percentile(err_ok[m], 90) if m.sum() else np.nan)
        n.append(int(m.sum()))

    fig, axes = plt.subplots(1, 2, figsize=(8.8, 3.4))
    ax = axes[0]
    x = np.arange(len(labels))
    ax.bar(x, med, color=ACCENT, alpha=0.9, label="typical (median)")
    ax.plot(x, p90, "o-", color=INK, ms=4, lw=1.2, label="worse days (90th percentile)")
    ax.set_xticks(x); ax.set_xticklabels(labels, fontsize=8)
    ax.set_xlabel("how fast glucose is moving (mg/dL per 5 min)")
    ax.set_ylabel("how far behind the\nfive minute view is (mg/dL)")
    ax.set_title("The cost is all in the fast bits")
    ax.legend(frameon=False, fontsize=8)
    ax.grid(True, axis="y", color=GRID, lw=0.8); ax.set_axisbelow(True)

    ax = axes[1]
    ax.hist(err, bins=30, color=INK, alpha=0.85)
    ax.axvline(np.median(err), color=ACCENT, lw=1.6, label=f"median {np.median(err):.1f} mg/dL")
    ax.set_xlabel("difference from the held five minute value (mg/dL)")
    ax.set_ylabel("readings")
    ax.set_title("Most of the time, nothing")
    ax.legend(frameon=False, fontsize=8)
    ax.grid(True, axis="y", color=GRID, lw=0.8); ax.set_axisbelow(True)
    fig.savefig(path)
    plt.close(fig)
    return float(np.median(err)), float(np.percentile(err, 90)), list(zip(labels, med, p90, n))


def fig_design(path):
    """The four arms.

    Drawn so the split is obvious: B, C and D share one sensor and one handset, A has its own of
    each. That separation is the whole reason A cannot be read as a cadence contrast.
    """
    arms = [
        ("A", 5, 5, 5, "#64748b", "own 5 min sensor, second handset"),
        ("B", 1, 5, 5, "#0f766e", ""),
        ("C", 1, 1, 1, ACCENT, ""),
        ("D", 1, 1, 3, "#7c3aed", ""),
    ]
    fig, ax = plt.subplots(figsize=(8.6, 3.8))
    span = 30
    for i, (name, sensor, decide, smb, colour, note) in enumerate(arms):
        y = len(arms) - i
        ax.text(-13.5, y, f"arm {name}", ha="left", va="center", fontsize=10, color=INK)
        for t in np.arange(0, span + 0.001, sensor):
            ax.plot([t], [y + 0.22], marker="|", ms=6, color=MUTED, mew=1.2)
        for t in np.arange(0, span + 0.001, decide):
            ax.plot([t], [y], marker="o", ms=3.4, color=colour)
        for t in np.arange(0, span + 0.001, smb):
            ax.plot([t], [y - 0.24], marker="^", ms=4.2, color=colour)
    # bracket the three arms that share a sensor and a handset
    ax.plot([-1.6, -1.6], [0.72, 3.28], color=INK, lw=1.1)
    ax.plot([-1.6, -1.1], [0.72, 0.72], color=INK, lw=1.1)
    ax.plot([-1.6, -1.1], [3.28, 3.28], color=INK, lw=1.1)
    ax.text(-2.2, 2.0, "one sensor,\none handset", ha="right", va="center", fontsize=8.5, color=INK)
    ax.text(-2.2, 4.0, "own sensor,\nsecond handset", ha="right", va="center", fontsize=8.5,
            color=MUTED)
    ax.text(0, len(arms) + 0.95, "sensor reading", color=MUTED, fontsize=8.5)
    ax.text(9, len(arms) + 0.95, "decision", color=INK, fontsize=8.5)
    ax.text(16.5, len(arms) + 0.95, "earliest possible bolus", color=INK, fontsize=8.5)
    ax.set_xlim(-14, span + 1)
    ax.set_ylim(0.3, len(arms) + 1.4)
    ax.set_xlabel("minutes")
    ax.set_yticks([])
    ax.spines["left"].set_visible(False)
    ax.grid(True, axis="x", color=GRID, lw=0.8)
    ax.set_axisbelow(True)
    ax.set_title("B, C and D share a sensor and differ only in software. A is a separate sensor, and a control")
    fig.savefig(path)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--day", default="2025-11-25")
    ap.add_argument("--out-dir", default=HERE)
    ap.add_argument("--allow-any", action="store_true",
                    help="draw the figures from a day that is not a one minute feed. The captions "
                         "then have to say what the data actually is.")
    a = ap.parse_args()
    d = load_day(a.day, require_one_minute=not a.allow_any)
    if d.empty:
        print("no data for that day"); return
    os.makedirs(a.out_dir, exist_ok=True)
    n = fig_trace(d, os.path.join(a.out_dir, "fig1_trace.png"))
    med, p90, bands = fig_latency(d, os.path.join(a.out_dir, "fig2_latency.png"))
    fig_design(os.path.join(a.out_dir, "fig3_design.png"))
    print(f"day {a.day}: {len(d)} readings, window {n}")
    print(f"median difference from the held five minute value: {med:.1f} mg/dL")
    print(f"90th percentile difference: {p90:.1f} mg/dL")
    for lab, m, p, n in bands:
        print(f"  moving {lab:>8} mg/dL per 5 min: median {m:5.1f}, 90th {p:5.1f}  (n={n})")


if __name__ == "__main__":
    main()
