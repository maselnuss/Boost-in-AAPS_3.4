#!/usr/bin/env python3
"""
Q1a — measure the actual cadences, with day-level block-bootstrap CIs.

Two distinct things get measured and must not be conflated:
  * CGM cadence  — gaps between `boost_cgm` rows (sensor/uploader time).
  * Loop cadence — gaps between `boost_decisions` rows of ONE variant.
    NB the extractor stores `ts_utc = devicestatus.created_at`, so this is an
    upload timestamp; pooling variants double-counts a single cycle.

Window: the DB as-is, no refresh.  Prints the window it used.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aaps_cadence_lib import block_bootstrap_ci  # noqa: E402

DSN = os.environ.get("BOOST_DSN", "dbname=oref host=127.0.0.1 port=5432")
CADENCE_CHANGE = pd.Timestamp("2026-05-21", tz="UTC")
ONE_MIN_USER = "I"
FIVE_MIN_REF = ["A", "B", "tim"]


def q(sql: str) -> pd.DataFrame:
    import subprocess, io
    out = subprocess.run(["psql", DSN, "-At", "-F", "\t", "-c", sql],
                         capture_output=True, text=True, check=True).stdout
    return pd.read_csv(io.StringIO(out), sep="\t", header=None)


def main() -> None:
    users = "','".join(FIVE_MIN_REF + [ONE_MIN_USER])
    cgm = q(f"SELECT user_id, ts_utc, cgm_mgdl FROM boost_cgm "
            f"WHERE user_id IN ('{users}') AND cgm_mgdl IS NOT NULL ORDER BY user_id, ts_utc")
    cgm.columns = ["user_id", "ts", "bg"]
    cgm["ts"] = pd.to_datetime(cgm["ts"], utc=True, format="mixed")

    print("=" * 78)
    print("Q1a  CADENCE PROFILE")
    print("=" * 78)
    print(f"DB window used (boost_cgm): {cgm.ts.min()}  ->  {cgm.ts.max()}")
    print()

    # ---- CGM cadence, per user / era ------------------------------------
    rows = []
    for uid, g in cgm.groupby("user_id"):
        g = g.sort_values("ts")
        gap = g.ts.diff().dt.total_seconds() / 60.0
        g = g.assign(gap=gap, day=g.ts.dt.floor("D"))
        eras = [("all", g)] if uid != ONE_MIN_USER else [
            ("pre 2026-05-21", g[g.ts < CADENCE_CHANGE]),
            ("post 2026-05-21", g[g.ts >= CADENCE_CHANGE]),
        ]
        for era, gg in eras:
            gg = gg[gg.gap.between(0, 30)]
            if gg.empty:
                continue
            # Day-level block bootstrap of the median gap.
            blocks = [d.gap.values for _, d in gg.groupby("day") if len(d) > 10]
            pt, lo, hi = block_bootstrap_ci(
                blocks, lambda bs: float(np.median(np.concatenate(bs))), n_boot=2000)
            rows.append(dict(user=uid, era=era, n=len(gg), days=len(blocks),
                             median_gap=pt, ci_lo=lo, ci_hi=hi,
                             readings_per_day=len(gg) / max(len(blocks), 1)))
    cg = pd.DataFrame(rows)
    print("--- CGM cadence (median gap, min; day-level block bootstrap 95% CI) ---")
    print(cg.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    print()

    # ---- Loop cadence, per user / variant --------------------------------
    dec = q(f"SELECT user_id, variant, ts_utc FROM boost_decisions "
            f"WHERE user_id IN ('{users}') ORDER BY user_id, variant, ts_utc")
    dec.columns = ["user_id", "variant", "ts"]
    dec["ts"] = pd.to_datetime(dec["ts"], utc=True, format="mixed")

    rows = []
    for (uid, var), g in dec.groupby(["user_id", "variant"]):
        if len(g) < 200:
            continue
        g = g.sort_values("ts")
        gap = g.ts.diff().dt.total_seconds() / 60.0
        g = g.assign(gap=gap, day=g.ts.dt.floor("D"))
        gg = g[g.gap.between(0, 30)]
        blocks = [d.gap.values for _, d in gg.groupby("day") if len(d) > 10]
        pt, lo, hi = block_bootstrap_ci(
            blocks, lambda bs: float(np.median(np.concatenate(bs))), n_boot=2000)
        rows.append(dict(user=uid, variant=var, n=len(gg), days=len(blocks),
                         first=g.ts.min().date(), last=g.ts.max().date(),
                         median_gap=pt, ci_lo=lo, ci_hi=hi))
    dv = pd.DataFrame(rows)
    print("--- Loop cadence per VARIANT (median devicestatus gap, min) ---")
    print(dv.to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    print()

    # Pooled-variant artefact, stated explicitly.
    rows = []
    for uid, g in dec.groupby("user_id"):
        g = g.sort_values("ts")
        gap = g.ts.diff().dt.total_seconds() / 60.0
        gg = g.assign(gap=gap)[lambda d: d.gap.between(0, 30)]
        rows.append(dict(user=uid, n=len(gg), pooled_median_gap=float(gg.gap.median())))
    print("--- Same table, variants POOLED (this is the misleading number) ---")
    print(pd.DataFrame(rows).to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    print()

    # ---- User I: per-day CGM readings, to date the cadence change ---------
    gi = cgm[cgm.user_id == ONE_MIN_USER].copy()
    gi["day"] = gi.ts.dt.floor("D")
    per_day = gi.groupby("day").size()
    changed = per_day[per_day > 800]
    print(f"--- User {ONE_MIN_USER}: days with >800 CGM readings (1-min days) ---")
    print(f"first such day: {changed.index.min().date() if len(changed) else 'none'}   "
          f"n_1min_days={len(changed)}   n_5min_days={(per_day < 400).sum()}")
    print()


if __name__ == "__main__":
    main()
