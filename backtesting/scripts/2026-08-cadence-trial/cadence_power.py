#!/usr/bin/env python3
"""Sample size for the cadence trial, from this participant's own day-to-day variability.

A day-randomised within-user design is powered by how much the daily outcome moves for reasons
unrelated to the arm. That is measurable from the existing record rather than assumed, so this
computes it directly and reports the minimum detectable difference for a range of trial lengths.

Two things this deliberately does NOT do:

  - It does not use a paired-day SD. Days are randomised independently to an arm, not paired, so
    the relevant quantity is the between-day SD of the outcome, and the standard error of a
    difference of means over n days per arm is SD * sqrt(2/n).
  - It does not assume the daily outcomes are independent. Glucose control is autocorrelated day to
    day (a bad site, an illness, a work pattern), which inflates the true standard error above the
    independent-sampling figure. The lag-1 autocorrelation is measured and used to apply a variance
    inflation factor, so the reported MDE is the honest one rather than the optimistic one.

Usage:
  python3 cadence_power.py --user tim --days 180 [--out CADENCE_POWER.md]
"""
from __future__ import annotations

import argparse
import os

import numpy as np
import pandas as pd
import psycopg2

HERE = os.path.dirname(os.path.abspath(__file__))
DSN = "dbname=oref host=127.0.0.1 port=5432"

# Consensus targets. TING is the tighter band this project reports alongside TIR.
BANDS = {
    "TIR 70-180": (70.0, 180.0),
    "TING 63-140": (63.0, 140.0),
    "TBR <70": (None, 70.0),
    "TBR <54": (None, 54.0),
}


def daily_outcomes(user: str, days: int) -> pd.DataFrame:
    conn = psycopg2.connect(DSN)
    d = pd.read_sql(
        """SELECT ts_utc, cgm_mgdl FROM boost_decisions
           WHERE user_id = %s AND cgm_mgdl BETWEEN 40 AND 400
             AND ts_utc > now() - (%s || ' days')::interval
           ORDER BY ts_utc""",
        conn, params=(user, days))
    conn.close()
    if d.empty:
        return d
    d["day"] = pd.to_datetime(d.ts_utc, utc=True).dt.date
    rows = []
    for day, g in d.groupby("day"):
        v = g.cgm_mgdl.values.astype(float)
        if len(v) < 200:            # need most of a day to call it a day
            continue
        r = {"day": day, "n": len(v)}
        for name, (lo, hi) in BANDS.items():
            if lo is None:
                r[name] = 100.0 * float((v < hi).mean())
            else:
                r[name] = 100.0 * float(((v >= lo) & (v <= hi)).mean())
        rows.append(r)
    return pd.DataFrame(rows)


def lag1(x: np.ndarray) -> float:
    if len(x) < 5:
        return 0.0
    a, b = x[:-1] - x[:-1].mean(), x[1:] - x[1:].mean()
    den = np.sqrt((a * a).sum() * (b * b).sum())
    return float((a * b).sum() / den) if den > 0 else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--user", default="tim")
    ap.add_argument("--days", type=int, default=180)
    ap.add_argument("--arm-days", default="14,21,28,42,56",
                    help="days PER ARM to report")
    ap.add_argument("--out")
    a = ap.parse_args()

    df = daily_outcomes(a.user, a.days)
    if df.empty:
        print("no data"); return

    L, P = [], None
    P = L.append
    P("# Sample size for the cadence trial\n")
    P(f"\nFrom {len(df)} complete days of this participant's own record over the last {a.days} "
      f"days. A day-randomised within-user comparison is powered by the between-day spread of the "
      f"outcome, which is measured here rather than assumed.\n")

    P("\n## Day-to-day variability\n")
    P("\n| outcome | mean | between-day SD | lag-1 autocorrelation | variance inflation |")
    P("|---|---|---|---|---|")
    stats = {}
    for name in BANDS:
        v = df[name].values.astype(float)
        r1 = lag1(v)
        # Standard first-order inflation for a correlated series: (1 + r) / (1 - r), floored at 1.
        vif = max(1.0, (1.0 + r1) / (1.0 - r1)) if r1 < 0.95 else 20.0
        stats[name] = (v.mean(), v.std(ddof=1), r1, vif)
        P(f"| {name} | {v.mean():.1f}% | {v.std(ddof=1):.1f} pp | {r1:+.2f} | x{vif:.2f} |")

    P("\n## Minimum detectable difference\n")
    P("\nTwo-sided, 5% significance, 80% power, equal days per arm, and the autocorrelation "
      "inflation applied. Read as: a true arm difference smaller than this will usually be missed, "
      "so the trial cannot settle it however the data are analysed.\n")
    arm_days = [int(x) for x in a.arm_days.split(",")]
    P("\n| outcome | " + " | ".join(f"{n} d/arm" for n in arm_days) + " |")
    P("|---" * (len(arm_days) + 1) + "|")
    for name in BANDS:
        _m, sd, _r, vif = stats[name]
        cells = []
        for n in arm_days:
            se = sd * np.sqrt(2.0 / n) * np.sqrt(vif)
            cells.append(f"{2.802 * se:.1f} pp")     # (1.96 + 0.842) * SE
        P(f"| {name} | " + " | ".join(cells) + " |")

    P("\n## Reading this\n")
    P("\nThe primary contrast is one-minute against the same sensor used at five minutes, so both "
      "arms run inside the same sensor sessions and the days above are days of trial, not days of "
      "wear. Each calendar day of the phase contributes to one arm, so a phase of N days yields "
      "about N/2 days per arm.\n")
    P("\nThe TBR figures are here to size the SAFETY comparison, not to power a benefit claim. The "
      "stopping rules do not wait for significance: they are absolute thresholds and they bind "
      "whatever this table says.\n")

    open(a.out or os.path.join(HERE, "CADENCE_POWER.md"), "w").write("\n".join(L))
    print("\n".join(L))


if __name__ == "__main__":
    main()
