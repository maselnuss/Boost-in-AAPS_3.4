#!/usr/bin/env python3
"""
Q1 + Q3 — cycle compression, and gate-opening rates per HOUR and per RISE EPISODE.

The Boost meal state machine ages in INVOCATIONS, not wall-clock
(MealHypothesis.kt: CONFIRM_MIN_OBSERVING_AGE = 2, FALL_BACK_TO_IDLE_AGE = 2,
RECOVERING_REENGAGE_MIN_AGE = 1; MealSignalScore.kt:
ML_MEAL_RENORMALIZE_AFTER_CYCLES = 3).  Age semantics from the shipped code:

  cycle i    IDLE -> OBSERVING, age := 0        (entering cycle)
  cycle i+1  OBSERVING branch sees age 0 -> 1
  cycle i+2  sees age 1 -> 2
  cycle i+3  sees age 2 -> AGE GATE OPEN (earliest possible CONFIRMED)

so the age gate opens 3 invocations after entry, whatever the wall-clock cost of
an invocation.  This script measures, on real rise episodes in user I's data:

  * wall-clock minutes to the age gate at 1-min vs 5-min
  * how much the EVIDENCE has actually moved by then: the fraction of the 5-min
    delta window that is still the SAME glucose as at OBSERVING entry, and the
    change in `delta` itself
  * the primer gate's opening rate per hour and per rise episode.  Per-cycle
    rates are meaningless when one arm has 5x the cycles.

Episode definition is a transparent glucose-only PROXY for the IDLE->OBSERVING
transition (the real transition needs the meal score, which depends on COB / ML /
exercise inputs not reconstructable from the CGM table).  Every claim resting on
it is labelled PROVISIONAL in REPORT.md.

Effect sizes carry EPISODE-level or DAY-level block-bootstrap 95% CIs.
"""
from __future__ import annotations

import os
import sys
from collections import defaultdict

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aaps_cadence_lib import (PRIMER_ACCEL_THRESHOLD,  # noqa: E402
                              PRIMER_DELTA_MIN, PRIMER_MIN_RECENT_LOW_MGDL,
                              block_bootstrap_ci, verdict)
import importlib  # noqa: E402
sig = importlib.import_module("03_signal_equivalence")

RISE_ENTER = 3.0        # mg/dL per 5 min — OBSERVING-entry proxy
QUIET_MIN = 15          # min of sub-threshold delta required before an entry
MAX_EPISODE_MIN = 120
AGE_GATE_CYCLES = 3     # invocations from entry to the age gate opening
PRIMER_WINDOW_CYCLES = 3  # cycles i, i+1, i+2 are OBSERVING; i+3 may CONFIRM


def episodes_from_grid(B):
    """Rise episodes on the 5-min bucket grid (the only grid the engine sees)."""
    t, d = B["now"], B["delta"]
    quiet_buckets = QUIET_MIN // 5
    eps, i = [], quiet_buckets
    while i < len(t):
        if d[i] >= RISE_ENTER and np.all(d[i - quiet_buckets:i] < RISE_ENTER):
            j = i
            while j + 1 < len(t) and d[j + 1] > 0 and (t[j + 1] - t[i]) <= MAX_EPISODE_MIN * 60000:
                j += 1
            eps.append((int(t[i]), int(t[j])))
            i = j + quiet_buckets + 1
        else:
            i += 1
    return eps


def primer_open(delta, accl, recent_low):
    """The glucose-driven part of the primer gate (DetermineBasalBoostV5.kt:376):
       delta >= 3.0 AND deltaAccl > 10.0 AND recentLowBg >= 80.
       The state/asleep/once-per-session conditions are handled by the caller."""
    return (delta >= PRIMER_DELTA_MIN) & (accl > PRIMER_ACCEL_THRESHOLD) & \
           (recent_low >= PRIMER_MIN_RECENT_LOW_MGDL)


def recent_low_series(now_ms, glucose):
    """min BG over the trailing 60 min, per cycle (opb.recentLowBG)."""
    out = np.empty(len(now_ms))
    lo = 0
    for i in range(len(now_ms)):
        while now_ms[lo] < now_ms[i] - 60 * 60000:
            lo += 1
        out[i] = glucose[lo:i + 1].min()
    return out


def main() -> None:
    days = sig.one_min_days()
    print("=" * 78)
    print("Q1 + Q3  CYCLE COMPRESSION AND GATE RATES")
    print("=" * 78)
    print(f"user I, {len(days)} complete 1-min days "
          f"({days[0].day.iloc[0].date()} .. {days[-1].day.iloc[0].date()})")
    print("n=1 user for the 1-min arm. Detection/timing only — no dosing outcome "
          "is implied and no counterfactual BG exists.\n")

    print("--- 0. What the cycle constants cost in WALL-CLOCK (deterministic) ---")
    print(f"  {'constant':40s} {'cycles':>7s} {'@5-min':>9s} {'@1-min':>9s}")
    for name, cyc in (("CONFIRM_MIN_OBSERVING_AGE = 2", 3),
                      ("  ...with sustained-score early path", 2),
                      ("  ...with aggressiveEarlyConfirm", 1),
                      ("FALL_BACK_TO_IDLE_AGE = 2", 3),
                      ("RECOVERING_REENGAGE_MIN_AGE = 1", 2),
                      ("ML_MEAL_RENORMALIZE_AFTER_CYCLES = 3", 3)):
        print(f"  {name:40s} {cyc:7d} {cyc*5:6d} min {cyc*1:6d} min")
    print()

    recs = []
    for d in days:
        ts, bg = d.ts.values.astype(np.int64), d.bg.values.astype(float)
        A0 = sig.arm(ts, bg, 1, 0)
        if A0 is not None:
            A0["rlow"] = recent_low_series(A0["now"], A0["glucose"])
            A0["open"] = primer_open(A0["delta"], A0["accl"], A0["rlow"])
        for p in range(5):
            A, B = A0, sig.arm(ts, bg, 5, p)
            if A is None or B is None:
                continue
            B["rlow"] = recent_low_series(B["now"], B["glucose"])
            B["open"] = primer_open(B["delta"], B["accl"], B["rlow"])
            recs.append(dict(day=str(d.day.iloc[0].date()), phase=p, A=A, B=B,
                             eps=episodes_from_grid(B)))

    day_blocks = list(defaultdict(list, {k: [r for r in recs if r["day"] == k]
                                         for k in {r["day"] for r in recs}}).values())

    # ---- 1. cycles and evidence during the OBSERVING window --------------
    print("--- 1. Real rise episodes: cycles, wall-clock and NEW EVIDENCE to the age gate ---")
    ep_rows = []
    for r in recs:
        for (t0, t1) in r["eps"]:
            row = dict(day=r["day"], phase=r["phase"], t0=t0)
            ok = True
            for k, lab in (("A", "1min"), ("B", "5min")):
                a = r[k]
                i = int(np.searchsorted(a["now"], t0, side="left"))
                if i + AGE_GATE_CYCLES >= len(a["now"]):
                    ok = False
                    break
                j = i + AGE_GATE_CYCLES
                wall = (a["now"][j] - a["now"][i]) / 60000.0
                row[f"{lab}_wall"] = wall
                # Fraction of the 5-min `delta` window at the age gate that is
                # still the SAME glucose the engine already had at entry.
                row[f"{lab}_overlap"] = 100.0 * max(0.0, (5.0 - wall) / 5.0)
                row[f"{lab}_dmove"] = abs(float(a["delta"][j] - a["delta"][i]))
                # primer opportunity window (cycles i .. i+2, state still OBSERVING)
                w = slice(i, i + PRIMER_WINDOW_CYCLES)
                row[f"{lab}_primer_open"] = bool(a["open"][w].any())
                row[f"{lab}_primer_span"] = (a["now"][i + PRIMER_WINDOW_CYCLES - 1]
                                             - a["now"][i]) / 60000.0
            if ok:
                ep_rows.append(row)
    ep = pd.DataFrame(ep_rows)
    print(f"  rise episodes found: {len(ep)} (across {ep.day.nunique()} days x 5 phases)")
    print()

    ep_blocks = list({k: g for k, g in ep.groupby("day")}.values())
    def m(col):
        return block_bootstrap_ci(ep_blocks, lambda bs, c=col: float(
            pd.concat(bs)[c].mean()), n_boot=2000)

    print(f"  {'quantity':44s} {'1-min':>22s} {'5-min':>22s}")
    for col, label in (("wall", "wall-clock to age gate (min)"),
                       ("overlap", "delta window still SAME glucose as entry (%)"),
                       ("dmove", "|change in delta| by the age gate (mg/dL/5min)"),
                       ("primer_span", "primer OBSERVING window (min)")):
        a = m(f"1min_{col}")
        b = m(f"5min_{col}")
        print(f"  {label:44s} {a[0]:8.2f} [{a[1]:.2f},{a[2]:.2f}]  "
              f"{b[0]:8.2f} [{b[1]:.2f},{b[2]:.2f}]")
    for col, label in (("wall", "wall-clock to age gate (min)"),
                       ("overlap", "delta window overlap with entry (%)"),
                       ("dmove", "|change in delta| by the age gate")):
        pt, lo, hi = block_bootstrap_ci(ep_blocks, lambda bs, c=col: float(
            pd.concat(bs)[f"1min_{c}"].mean() - pd.concat(bs)[f"5min_{c}"].mean()), n_boot=2000)
        print(f"  DELTA {label:38s} {pt:+8.2f} [{lo:+.2f},{hi:+.2f}]  {verdict(lo, hi, 0.0)}")
    print()

    # ---- 2. primer gate: per hour and per episode ------------------------
    print("--- 2. Primer gate (delta>=3.0 AND delta_accl>10 AND recentLow>=80) ---")
    print("    Per-cycle rates are NOT reported: the 1-min arm has 5x the cycles.")
    def per_hour(bs, k):
        n_open = sum(int(r[k]["open"].sum()) for b in bs for r in b)
        hours = sum((r[k]["now"][-1] - r[k]["now"][0]) / 3600000.0 for b in bs for r in b)
        return n_open / max(hours, 1e-9)
    def open_minutes_per_hour(bs, k):
        """Wall-clock minutes per hour during which the gate condition HOLDS
           (cadence-free: each cycle covers its own inter-cycle interval)."""
        tot_open = tot_h = 0.0
        for b in bs:
            for r in b:
                a = r[k]
                dt = np.diff(a["now"]) / 60000.0
                tot_open += float(dt[a["open"][:-1]].sum())
                tot_h += float(dt.sum()) / 60.0
        return tot_open / max(tot_h, 1e-9)
    for k, lab in (("A", "1-min arm"), ("B", "5-min arm")):
        c, clo, chi = block_bootstrap_ci(day_blocks, lambda bs, kk=k: per_hour(bs, kk), n_boot=1500)
        t, tlo, thi = block_bootstrap_ci(day_blocks, lambda bs, kk=k: open_minutes_per_hour(bs, kk), n_boot=1500)
        print(f"  {lab:12s} cycles-with-gate-open per hour = {c:6.2f} [{clo:.2f},{chi:.2f}]   "
              f"gate-open MINUTES per hour = {t:5.2f} [{tlo:.2f},{thi:.2f}]")
    pt, lo, hi = block_bootstrap_ci(
        day_blocks, lambda bs: open_minutes_per_hour(bs, "A") - open_minutes_per_hour(bs, "B"),
        n_boot=1500)
    print(f"  time-weighted difference (1-min - 5-min) = {pt:+.3f} min/h  [{lo:+.3f},{hi:+.3f}]   "
          f"{verdict(lo, hi, 0.0)}")
    print()

    print("  Per RISE EPISODE — does the gate open at all inside the OBSERVING window?")
    for lab, col in (("1-min arm", "1min_primer_open"), ("5-min arm", "5min_primer_open")):
        pt, lo, hi = block_bootstrap_ci(ep_blocks, lambda bs, c=col: 100.0 * float(
            pd.concat(bs)[c].mean()), n_boot=2000)
        print(f"    {lab:12s} {pt:5.1f}% of episodes  [{lo:.1f}, {hi:.1f}]")
    pt, lo, hi = block_bootstrap_ci(ep_blocks, lambda bs: 100.0 * float(
        pd.concat(bs)["1min_primer_open"].mean() - pd.concat(bs)["5min_primer_open"].mean()),
        n_boot=2000)
    print(f"    difference (1-min - 5-min) = {pt:+.1f} pp  [{lo:+.1f}, {hi:+.1f}]   "
          f"{verdict(lo, hi, 0.0)}")
    print()

    # ---- 3. within-user pre/post the real 2026-05-21 cadence change ------
    print("--- 3. Within-user natural experiment: user I pre/post 2026-05-21 ---")
    print("    (rise-episode physiology, to show the underlying signal did not change)")
    pre = sig.q("SELECT (extract(epoch from ts_utc)*1000)::bigint, cgm_mgdl FROM boost_cgm "
                "WHERE user_id='I' AND cgm_mgdl IS NOT NULL AND ts_utc < '2026-05-21' "
                "ORDER BY ts_utc")
    pre.columns = ["ts", "bg"]
    pre["day"] = pd.to_datetime(pre.ts, unit="ms", utc=True).dt.floor("D")
    pre_days = [g.reset_index(drop=True) for _, g in pre.groupby("day") if 250 <= len(g) <= 400]
    pre_blocks, post_blocks = [], []
    # Both eras are put through the SAME 5-min bucketing branch, so the
    # arithmetic is identical and only the underlying glucose differs.
    for lab, dset, step in (("pre", pre_days, 1), ("post", days, 5)):
        for d in dset:
            ts, bg = d.ts.values.astype(np.int64), d.bg.values.astype(float)
            B = sig.arm(ts, bg, step, 0, path="5min")
            if B is None:
                continue
            e = episodes_from_grid(B)
            rec = dict(n_eps=len(e), hours=(B["now"][-1] - B["now"][0]) / 3600000.0,
                       med_delta=float(np.median(np.abs(B["delta"]))),
                       p99_delta=float(np.percentile(np.abs(B["delta"]), 99)))
            (pre_blocks if lab == "pre" else post_blocks).append(rec)
    for lab, bks in (("pre  (5-min CGM)", pre_blocks), ("post (1-min CGM)", post_blocks)):
        e, elo, ehi = block_bootstrap_ci(bks, lambda bs: 24.0 * sum(r["n_eps"] for r in bs)
                                         / max(sum(r["hours"] for r in bs), 1e-9), n_boot=2000)
        md, mlo, mhi = block_bootstrap_ci(bks, lambda bs: float(np.mean([r["med_delta"] for r in bs])), n_boot=2000)
        print(f"  {lab:18s} n_days={len(bks):3d}  rise episodes/day = {e:5.2f} "
              f"[{elo:.2f},{ehi:.2f}]   median|delta| = {md:.2f} [{mlo:.2f},{mhi:.2f}]")
    print("    Both eras are evaluated on the SAME 5-min bucket grid the engine uses,")
    print("    so any difference here is physiology/sensor, not cadence arithmetic.")
    print()


if __name__ == "__main__":
    main()
