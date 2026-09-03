#!/usr/bin/env python3
"""
Cross-check the code-derived prediction against LIVE devicestatus.

Prediction from this repo's front end (verified byte-for-byte against the shipped
Kotlin bucketer in 02_bucketer_parity.py): with 1-min CGM the bucketed grid is
still exactly 5 min, so `suggested.bg` — which is `glucose_status.glucose` =
bucketedData[0].recalculated (DetermineBasalBoostV3MLG3.kt:252) — can only change
once per 5 minutes, no matter how often the loop runs.

This script measures how often `suggested.bg` actually changes for each user and
variant, and for the 1-min user checks whether the reported value tracks the raw
CGM, a 5-min grid-held value, or something lagged/smoothed.

A mismatch does NOT invalidate the code analysis; it means the live build is not
the one in this tree, and the finding must be confirmed on-device before acting.
"""
from __future__ import annotations

import io
import os
import subprocess

import numpy as np
import pandas as pd

DSN = os.environ.get("BOOST_DSN", "dbname=oref host=127.0.0.1 port=5432")
BOOST_VARIANTS = ("v1", "v2", "v3", "boost-other")


def q(sql: str) -> pd.DataFrame:
    out = subprocess.run(["psql", DSN, "-At", "-F", "\t", "-c", sql],
                         capture_output=True, text=True, check=True).stdout
    return pd.read_csv(io.StringIO(out), sep="\t", header=None)


def main() -> None:
    print("=" * 78)
    print("LIVE-BUILD CHECK — does suggested.bg move on a 5-min grid?")
    print("=" * 78)
    inv = "','".join(BOOST_VARIANTS)
    df = q(f"SELECT user_id, variant, ts_utc, cgm_mgdl FROM boost_decisions "
           f"WHERE variant IN ('{inv}') AND ts_utc > now() - interval '120 days' "
           f"ORDER BY user_id, variant, ts_utc")
    df.columns = ["user", "variant", "ts", "bg"]
    df["ts"] = pd.to_datetime(df.ts, utc=True, format="mixed")

    rows = []
    for (u, v), g in df.groupby(["user", "variant"]):
        if len(g) < 300:
            continue
        g = g.sort_values("ts")
        chg = g[g.bg.ne(g.bg.shift())]
        gap = chg.ts.diff().dt.total_seconds() / 60.0
        gap = gap[gap.between(0, 30)]
        loop = g.ts.diff().dt.total_seconds() / 60.0
        loop = loop[loop.between(0, 30)]
        if gap.empty:
            continue
        rows.append(dict(user=u, variant=v, n=len(g),
                         median_loop_gap=float(loop.median()),
                         median_bg_change_gap=float(gap.median()),
                         predicted="5.00"))
    r = pd.DataFrame(rows).sort_values(["user", "variant"])
    print(r.to_string(index=False, float_format=lambda x: f"{x:.2f}"))
    print()
    print("  Prediction: median_bg_change_gap == 5.00 for EVERY build that uses this")
    print("  tree's bucketer, regardless of CGM or loop cadence.")
    print()

    # --- what does the 1-min user's reported bg actually track? -----------
    print("--- 1-min user (I): what does suggested.bg track? ---")
    c = q("SELECT (extract(epoch from ts_utc)*1000)::bigint, cgm_mgdl FROM boost_cgm "
          "WHERE user_id='I' AND ts_utc >= '2026-07-28' ORDER BY ts_utc")
    d = q(f"SELECT (extract(epoch from ts_utc)*1000)::bigint, cgm_mgdl FROM boost_decisions "
          f"WHERE user_id='I' AND variant IN ('{inv}') ORDER BY ts_utc")
    if c.empty or d.empty:
        print("  no overlapping window")
        return
    c.columns, d.columns = ["ts", "bg"], ["ts", "bg"]
    ct, cv = c.ts.values, c.bg.values
    j = np.searchsorted(ct, d.ts.values, side="right") - 1
    ok = j >= 0
    dec, dts = d.bg.values[ok], d.ts.values[ok]
    print(f"  n={len(dec)} decisions")
    grid = ct[0] + ((dts - ct[0]) // 300000) * 300000
    cands = {
        "raw newest reading <= t": cv[np.clip(j[ok], 0, len(cv) - 1)],
        "5-min grid-held value  ": cv[np.clip(np.searchsorted(ct, grid, "right") - 1, 0, len(cv) - 1)],
    }
    for lag in (2, 3, 4):
        k = np.clip(np.searchsorted(ct, dts - lag * 60000, "right") - 1, 0, len(cv) - 1)
        cands[f"raw lagged {lag} min      "] = cv[k]
    for lab, ref in cands.items():
        e = dec - ref
        print(f"  {lab}  MAE={np.abs(e).mean():6.2f}  exact-match={100 * np.mean(e == 0):5.1f}%")
    print()
    print("  A low exact-match rate against ALL of these means the reported value is")
    print("  neither the raw reading nor a grid-held raw reading — i.e. a smoother is")
    print("  active, or the build is not this tree's front end.")


if __name__ == "__main__":
    main()
