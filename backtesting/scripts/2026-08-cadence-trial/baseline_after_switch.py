#!/usr/bin/env python3
"""Is the post-switch baseline actually under the safety floor, and can a short window show it?

The participant moved from U200 to the same analogue diluted to U100 strength, so the same mass is
now recorded as twice the units and the TDD-derived scaling had to re-settle. The time-below-range
that breached the floor in the pre-registered protocol is attributed to that transition, and the
proposal is to baseline the cadence trial on the stabilised period instead.

That is a reasonable read, and this checks it rather than accepting it, because the question the
protocol actually has to answer is not "is the recent mean lower" — a short window will move on its
own — but "can a window this short establish that the floor is met". Those are different, and the
second is the one a stopping rule depends on.

Three things are computed:

  1. The daily series across the transition, so the change is visible rather than asserted.
  2. Whether the stabilised period differs from the pre-switch period, with a CI, and whether the
     TDD scaling has in fact settled.
  3. The precision of a short-window TBR estimate against the floor. A between-day SD of about
     4 pp means a three-day mean carries a 95% interval roughly +/-4.5 pp wide, which spans the
     floor from either side; the number of days needed to separate the observed rate from 4% is
     reported directly.

Usage:
  python3 baseline_after_switch.py --user tim --switch 2026-08-05 --stable-from 2026-08-07
"""
from __future__ import annotations

import argparse
import os

import numpy as np
import pandas as pd
import psycopg2

HERE = os.path.dirname(os.path.abspath(__file__))
DSN = "dbname=oref host=127.0.0.1 port=5432"

TBR70_FLOOR = 4.0      # consensus absolute, %
TBR54_FLOOR = 1.0      # consensus absolute, %


def daily(user, since):
    conn = psycopg2.connect(DSN)
    d = pd.read_sql(
        """SELECT ts_utc, cgm_mgdl, tdd, tdd_ratio FROM boost_decisions
           WHERE user_id=%s AND ts_utc >= %s AND cgm_mgdl BETWEEN 40 AND 400
           ORDER BY ts_utc""", conn, params=(user, since))
    t = pd.read_sql(
        """SELECT ts_utc, insulin FROM boost_treatments
           WHERE user_id=%s AND ts_utc >= %s AND insulin > 0""", conn, params=(user, since))
    conn.close()
    if d.empty:
        return d
    d["day"] = pd.to_datetime(d.ts_utc, utc=True).dt.date
    t["day"] = pd.to_datetime(t.ts_utc, utc=True).dt.date
    ins = t.groupby("day").insulin.sum()
    rows = []
    for day, g in d.groupby("day"):
        v = g.cgm_mgdl.values.astype(float)
        # A PARTIAL day is not a day. The run-day is in progress when this is executed, and a
        # half day of good control reads as a perfect one: on first run 2026-08-09 held 130 CGM
        # points and scored 100% TIR, pulling the stabilised mean down with it. Require both a
        # full complement of readings and a span covering most of the 24 hours.
        span_h = (g.ts_utc.max() - g.ts_utc.min()).total_seconds() / 3600.0
        if len(v) < 250 or span_h < 20.0:
            continue
        rows.append(dict(
            day=day, n=len(v),
            tir=100 * float(((v >= 70) & (v <= 180)).mean()),
            tbr70=100 * float((v < 70).mean()),
            tbr54=100 * float((v < 54).mean()),
            mean_bg=float(v.mean()),
            tdd=float(np.nanmedian(g.tdd.values.astype(float))) if g.tdd.notna().any() else np.nan,
            ratio=float(np.nanmedian(g.tdd_ratio.values.astype(float))) if g.tdd_ratio.notna().any() else np.nan,
            units=float(ins.get(day, np.nan)),
        ))
    return pd.DataFrame(rows)


def boot_mean_ci(x, n=20000, seed=20260809):
    rng = np.random.default_rng(seed)
    if len(x) < 2:
        return (np.nan, np.nan)
    b = rng.choice(x, (n, len(x)), replace=True).mean(axis=1)
    return tuple(np.percentile(b, [2.5, 97.5]))


def days_needed(sd, observed, floor, z=1.96):
    """Days for the CI half-width to clear the gap between the observed rate and the floor."""
    gap = abs(floor - observed)
    if gap <= 0:
        return None
    return int(np.ceil((z * sd / gap) ** 2))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--user", default="tim")
    ap.add_argument("--switch", default="2026-08-05")
    ap.add_argument("--stable-from", default="2026-08-07")
    ap.add_argument("--history-days", type=int, default=90)
    ap.add_argument("--out")
    a = ap.parse_args()

    start = (pd.Timestamp(a.switch, tz="UTC") - pd.Timedelta(days=a.history_days)).isoformat()
    df = daily(a.user, start)
    if df.empty:
        print("no data"); return
    sw = pd.Timestamp(a.switch).date()
    st = pd.Timestamp(a.stable_from).date()
    pre = df[df.day < sw]
    trans = df[(df.day >= sw) & (df.day < st)]
    stable = df[df.day >= st]

    hist_sd = pre.tbr70.std(ddof=1) if len(pre) > 5 else float("nan")

    L, P = [], None
    P = L.append
    P("# Baseline after the U200 to U100 switch\n")
    P(f"\nDaily outcomes from {df.day.min()} to {df.day.max()}. Switch dated {sw}; the period from "
      f"{st} is treated as stabilised.\n")

    P("\n## The transition, day by day\n")
    P("\n| day | readings | units | TDD | ratio | TIR | TBR<70 | TBR<54 | period |")
    P("|---|---|---|---|---|---|---|---|---|")
    for _, r in df[df.day >= sw - pd.Timedelta(days=7).to_pytimedelta()].iterrows():
        per = "pre" if r.day < sw else ("transition" if r.day < st else "**stabilised**")
        P(f"| {r.day} | {r.n:.0f} | {r.units:.1f} | {r.tdd:.1f} | {r.ratio:.2f} | {r.tir:.1f}% | "
          f"{r.tbr70:.1f}% | {r.tbr54:.1f}% | {per} |")

    P("\n## Has it settled?\n")
    P("\n| period | days | TIR | TBR<70 (95% CI) | TBR<54 | mean units/day | TDD ratio |")
    P("|---|---|---|---|---|---|---|")
    for name, g in (("pre-switch", pre), ("transition", trans), ("stabilised", stable)):
        if g.empty:
            P(f"| {name} | 0 | | | | | |"); continue
        # At n=3 a bootstrap can only resample the three observed days, so it cannot represent a
        # bad day and reports an interval far tighter than the truth. Use the historical
        # between-day SD for short periods and reserve the bootstrap for periods long enough to
        # carry their own spread.
        if len(g) >= 10:
            lo, hi = boot_mean_ci(g.tbr70.values)
        else:
            hw = 1.96 * hist_sd / np.sqrt(len(g))
            lo, hi = g.tbr70.mean() - hw, g.tbr70.mean() + hw
        P(f"| {name} | {len(g)} | {g.tir.mean():.1f}% | **{g.tbr70.mean():.1f}%** "
          f"[{lo:.1f}, {hi:.1f}] | {g.tbr54.mean():.1f}% | {g.units.mean():.1f} | "
          f"{g.ratio.mean():.2f} |")
    P(f"\nIntervals for periods shorter than ten days use the historical between-day SD "
      f"({hist_sd:.1f} pp), not a bootstrap of the period itself, which at n=3 would resample only "
      f"the three observed days and understate the spread.\n")

    if len(stable) >= 2 and len(pre) >= 5:
        d70 = stable.tbr70.mean() - pre.tbr70.mean()
        P(f"\nChange in TBR<70 from pre-switch to stabilised: **{d70:+.1f} pp**.\n")

    P("\n## Can a window this short establish the floor is met?\n")
    sd = pre.tbr70.std(ddof=1)
    P(f"\nBetween-day SD of TBR<70 over the pre-switch record is **{sd:.1f} pp**. The precision of a "
      f"mean over n days is that divided by the square root of n, so:\n")
    P("\n| days of baseline | 95% half-width on TBR<70 |")
    P("|---|---|")
    for n in (3, 7, 14, 21, 28):
        P(f"| {n} | +/-{1.96 * sd / np.sqrt(n):.1f} pp |")
    if len(stable):
        obs = stable.tbr70.mean()
        need = days_needed(sd, obs, TBR70_FLOOR)
        P(f"\nThe stabilised period currently observes {obs:.1f}% against a floor of "
          f"{TBR70_FLOOR:.0f}%. Separating those two with 95% confidence needs about **{need} days** "
          f"at this variability" if need else "\nThe observed rate sits on the floor, so no finite "
          "number of days separates them.")
        P(f", against the {len(stable)} available.\n")
        halfw = 1.96 * sd / np.sqrt(max(len(stable), 1))
        verdict = ("The interval already excludes the floor." if obs + halfw < TBR70_FLOOR else
                   "**The interval still spans the floor, so the floor cannot yet be declared met.**")
        P(f"\n{verdict} A lower point estimate is not the same as a demonstrated one, and the "
          f"stopping rule is written against a demonstrated rate.\n")

    open(a.out or os.path.join(HERE, "BASELINE_AFTER_SWITCH.md"), "w").write("\n".join(L))
    print("\n".join(L))


if __name__ == "__main__":
    main()
