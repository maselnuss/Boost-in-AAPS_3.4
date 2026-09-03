#!/usr/bin/env python3
"""
Q2 — are the derived signals equivalent at 1-min vs 5-min on the SAME glucose?

Two arms over user I's 1-min days, same underlying glucose:

  Arm A "1-min as shipped"    : every reading triggers a loop cycle; the front end
                                runs the real bucketer with a PERSISTENT
                                referenceTime (the app's ADS is a singleton and
                                referenceTime is set once, from a real reading),
                                so the bucket grid is phase-locked at 5 min.
  Arm B "5-min counterfactual": the same glucose decimated to 5 min at offset p;
                                one loop cycle per reading (the 5-min path).

Grid anchoring.  `AutosensDataStoreObject.clone()` does NOT copy `referenceTime`,
and `IobCobOref1Worker` swaps the live store for a clone every autosens run
(IobCobOref1Worker.kt:83, 324), so referenceTime is reset to -1 each cycle and
the 5-min grid RE-ANCHORS to the newest reading.  Buckets are therefore at
now, now-5, now-10, ... every cycle.  This is confirmed in the field by
05_live_build_check.py.  Arm A consequently has no free phase; arm B keeps a
decimation offset p = 0..4 (which 5-min sub-sample a 5-min sensor would give),
swept so nothing depends on it.  Cross-offset comparisons of two 5-min arms are
reported as an ALIASING BASELINE: the difference between two arbitrary 5-min
samplings of the same curve, which exists between any two 5-min sensors and is
not a cadence effect.

The bucketing/delta code is `aaps_cadence_lib`, proven byte-identical to the
shipped Kotlin by 02_bucketer_parity.py.

Optimisation (exact, not an approximation): in the recalculated path the bucket
at grid time T is built only from the two raw readings straddling T, so a whole
day's grid can be built once and sliced per cycle.

Every effect size carries a DAY-level block-bootstrap 95% CI.
"""
from __future__ import annotations

import io
import os
import subprocess
import sys
from collections import defaultdict

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aaps_cadence_lib import (block_bootstrap_ci, deltas_vectorised,  # noqa: E402
                              verdict)

DSN = os.environ.get("BOOST_DSN", "dbname=oref host=127.0.0.1 port=5432")
PHASES = range(5)
FIELDS = [("delta", "delta"), ("sad", "shortAvgDelta"),
          ("lad", "longAvgDelta"), ("accl", "delta_accl")]


def q(sql: str) -> pd.DataFrame:
    out = subprocess.run(["psql", DSN, "-At", "-F", "\t", "-c", sql],
                         capture_output=True, text=True, check=True).stdout
    return pd.read_csv(io.StringIO(out), sep="\t", header=None)


def one_min_days(uid: str = "I", min_n: int = 1300):
    df = q(f"SELECT (extract(epoch from ts_utc)*1000)::bigint, cgm_mgdl FROM boost_cgm "
           f"WHERE user_id='{uid}' AND cgm_mgdl IS NOT NULL AND ts_utc >= '2026-05-23' "
           f"ORDER BY ts_utc")
    df.columns = ["ts", "bg"]
    df["day"] = pd.to_datetime(df.ts, unit="ms", utc=True).dt.floor("D")
    return [g.reset_index(drop=True) for _, g in df.groupby("day") if len(g) >= min_n]


def build_grid(ts, bg, reference_time):
    ads = AutosensDataStore(reference_time=reference_time)
    ads.bg_readings = [GV(int(ts[i]), float(bg[i])) for i in range(len(ts) - 1, -1, -1)]
    ads.create_bucketed_data()
    return (ads.bucketed_data or []), bool(ads.last_used_5min)


def statuses_for_grid(grid):
    out = []
    for i in range(len(grid)):
        d = as_rounded(calculate_deltas(grid[i:i + 12]))
        out.append((grid[i].timestamp, round(grid[i].recalculated, 0),
                    d.delta, d.short_avg_delta, d.long_avg_delta,
                    delta_accl(d.delta, d.short_avg_delta)))
    return out


def arm(ts, bg, step, offset, path=None):
    """step=1 -> 1-min arm (grid re-anchored to `now` each cycle; offset unused);
       step=5 -> 5-min arm (offset = decimation offset in readings).
       `path` forces the bucketing branch ("5min" / "recalc"); by default it
       follows `isAbout5minData` (True for a 5-min stream, False for 1-min)."""
    if step == 5:
        ts, bg = ts[offset::5], bg[offset::5]
    if path is None:
        path = "5min" if step == 5 else "recalc"
    if len(ts) < 60:
        return None
    asc_t, asc_v = ts.astype(np.int64), bg.astype(float)
    n = len(asc_t)
    if path == "5min":
        # createBucketedData5min: bucket k IS reading i-k, with timestamps
        # re-spaced to exactly 5 min (AutosensDataStoreObject.kt:261-336).
        cols = [np.concatenate([np.full(k, np.nan), asc_v[:n - k]]) if k else asc_v.copy()
                for k in range(9)]
        B = np.column_stack(cols)
        warm = np.arange(n) >= 9
        B, cyc = B[warm], asc_t[warm]
        if len(cyc) < 10:
            return None
        d, sa, la = deltas_vectorised(B)
        return dict(now=cyc, vage=np.zeros(len(cyc)), bucket_ts=cyc.copy(),
                    glucose=np.round(B[:, 0], 0), delta=d, sad=sa, lad=la,
                    accl=100.0 * (d - sa) / np.maximum(np.abs(sa), 2.0),
                    used5=True, n_buckets=len(cyc))
    # Bucket k of the cycle at reading i is the value at t_i - k*5min, obtained
    # by linear interpolation between the two straddling readings, then
    # roundToLong (floor(x+0.5)).  Bucket 0 is always a direct hit.
    cols = [asc_v.copy()]
    for k in range(1, 9):
        tk = asc_t - k * 5 * 60000
        v = np.interp(tk, asc_t, asc_v)
        v = np.floor(v + 0.5)
        exact = np.isin(tk, asc_t)
        if exact.any():
            idx = np.searchsorted(asc_t, tk[exact])
            v[exact] = asc_v[np.clip(idx, 0, n - 1)]
        cols.append(v)
    B = np.column_stack(cols)
    warm = asc_t >= asc_t[0] + 45 * 60000       # need 40 min of history
    B, cyc = B[warm], asc_t[warm]
    if len(cyc) < 10:
        return None
    d, sa, la = deltas_vectorised(B)
    return dict(
        now=cyc,
        vage=np.zeros(len(cyc)),                # bucket 0 IS the newest reading
        bucket_ts=cyc.copy(),
        glucose=np.round(B[:, 0], 0),
        delta=d, sad=sa, lad=la,
        accl=100.0 * (d - sa) / np.maximum(np.abs(sa), 2.0),
        used5=False, n_buckets=len(cyc),
    )


def match(A, B):
    """For each arm-A cycle t, arm B's most recent cycle <= t."""
    j = np.searchsorted(B["now"], A["now"], side="right") - 1
    keep = j >= 0
    return {f: A[f][keep] - B[f][j[keep]] for f, _ in FIELDS}


def main() -> None:
    days = one_min_days()
    print("=" * 78)
    print("Q2  SIGNAL EQUIVALENCE — 1-min vs 5-min on the SAME glucose")
    print("=" * 78)
    print(f"user I, {len(days)} complete 1-min days "
          f"({days[0].day.iloc[0].date()} .. {days[-1].day.iloc[0].date()}), "
          f"n_readings={sum(len(d) for d in days)}")
    print("n=1 user for the 1-min arm — a CADENCE-MECHANISM result, not a user "
          "comparison. No dosing outcome is implied.\n")

    recs = []
    for d in days:
        ts, bg = d.ts.values.astype(np.int64), d.bg.values.astype(float)
        A0 = arm(ts, bg, 1, 0)
        Bs = {p: arm(ts, bg, 5, p) for p in PHASES}
        for p in PHASES:
            A, B = A0, Bs[p]
            if A is None or B is None:
                continue
            # 5-min-vs-5-min adjacent-phase baseline: two 5-min engines whose
            # sampling instants differ by 1 min. This is the aliasing null.
            pn = (p + 1) % 5
            off = [match(Bs[pn], B)] if Bs[pn] is not None else []
            recs.append(dict(day=str(d.day.iloc[0].date()), phase=p,
                             A=A, B=B, M=match(A, B), OFF=off))

    blocks = defaultdict(list)
    for r in recs:
        blocks[r["day"]].append(r)
    bl = list(blocks.values())
    print(f"day-blocks for the bootstrap: {len(bl)}  "
          f"(each = one day x 5 five-minute decimation offsets)\n")

    def cat(bs, k, f):
        return np.concatenate([r[k][f] for b in bs for r in b])

    def catm(bs, f):
        return np.concatenate([r["M"][f] for b in bs for r in b])

    def catoff(bs, f):
        return np.concatenate([m[f] for b in bs for r in b for m in r["OFF"]])

    # ---- 1. information rate --------------------------------------------
    print("--- 1. What the dose path actually receives, per day ---")
    for k, label in (("A", "1-min arm (~1440 readings/day)"),
                     ("B", "5-min arm (~ 288 readings/day)")):
        pt, lo, hi = block_bootstrap_ci(
            bl, lambda bs, kk=k: float(np.mean([r[kk]["n_buckets"] for b in bs for r in b])))
        cyc, clo, chi = block_bootstrap_ci(
            bl, lambda bs, kk=k: float(np.mean([len(r[kk]["now"]) for b in bs for r in b])))
        print(f"  {label:32s} loop cycles/day = {cyc:7.0f} [{clo:.0f}, {chi:.0f}]   "
              f"distinct glucose statuses/day = {pt:6.1f} [{lo:.1f}, {hi:.1f}]")
    print("  -> the grid RE-ANCHORS every cycle, so the 1-min arm gets ~5x as many")
    print("     distinct 5-min-window glucose statuses per day. The WINDOW STRUCTURE is")
    print("     unchanged (delta over 5 min, short over 5/10/15, long over 20..40).")
    print()

    # ---- 2. IN-PHASE comparison: the decisive test -----------------------
    print("--- 2. Matched in wall-clock: what each arm is ACTING ON at every 1-min instant ---")
    hdr = f"  {'signal':14s} {'mean|A-B|':>12s} {'max|A-B|':>10s} {'% cycles differing':>20s}"
    print(hdr)
    for f, name in FIELDS:
        pt, lo, hi = block_bootstrap_ci(
            bl, lambda bs, ff=f: float(np.mean(np.abs(catm(bs, ff)))), n_boot=1000)
        mx = float(np.max(np.abs(catm(bl, f))))
        frac, flo, fhi = block_bootstrap_ci(
            bl, lambda bs, ff=f: 100.0 * float(np.mean(np.abs(catm(bs, ff)) > 1e-9)), n_boot=1000)
        print(f"  {name:14s} {pt:12.6f} {mx:10.4f} {frac:14.4f}% [{flo:.4f},{fhi:.4f}]")
    print("    (a zero row means the 1-min engine and a 5-min engine act on the")
    print("     SAME numbers at every wall-clock instant)")
    print()

    # ---- 2b. per-evaluation marginal distributions -----------------------
    print("--- 2b. Per-evaluation marginal distributions ---")
    rows = []
    for f, name in FIELDS:
        for k, lab in (("A", "1-min"), ("B", "5-min")):
            med, mlo, mhi = block_bootstrap_ci(
                bl, lambda bs, ff=f, kk=k: float(np.median(np.abs(cat(bs, kk, ff)))), n_boot=1000)
            p99, plo, phi = block_bootstrap_ci(
                bl, lambda bs, ff=f, kk=k: float(np.percentile(np.abs(cat(bs, kk, ff)), 99)), n_boot=1000)
            rows.append(dict(signal=name, arm=lab, med_abs=med, med_lo=mlo, med_hi=mhi,
                             p99_abs=p99, p99_lo=plo, p99_hi=phi))
    print(pd.DataFrame(rows).to_string(index=False, float_format=lambda v: f"{v:.3f}"))
    for f, name in FIELDS:
        for qn, qf in (("median|.|", lambda v: float(np.median(np.abs(v)))),
                       ("p99|.|", lambda v: float(np.percentile(np.abs(v), 99)))):
            pt, lo, hi = block_bootstrap_ci(
                bl, lambda bs, ff=f, g=qf: g(cat(bs, "A", ff)) - g(cat(bs, "B", ff)), n_boot=1000)
            print(f"  {name:14s} {qn:10s} A-B = {pt:+8.4f}  [{lo:+.4f}, {hi:+.4f}]   "
                  f"{verdict(lo, hi, 0.0)}")
    print()

    # ---- 3. cross-phase aliasing baseline --------------------------------
    print("--- 3. MATCHED-BASELINE TEST: is the 1-min difference bigger than ordinary")
    print("       5-min phase aliasing? ---")
    print("    baseline = TWO 5-MIN engines sampling 1 min apart (same glucose).")
    print("    test     = 1-min engine vs 5-min engine (section 2).")
    print("    If test - baseline overlaps 0, the cadence change moves the derived")
    print("    signals no more than swapping one 5-min sensor for another would.")
    print(f"  {'signal':14s} {'test mean|d|':>13s} {'base mean|d|':>13s} "
          f"{'test-base':>11s} {'95% CI':>22s}  verdict")
    for f, name in FIELDS:
        t, tlo, thi = block_bootstrap_ci(
            bl, lambda bs, ff=f: float(np.mean(np.abs(catm(bs, ff)))), n_boot=1000)
        b_, blo, bhi = block_bootstrap_ci(
            bl, lambda bs, ff=f: float(np.mean(np.abs(catoff(bs, ff)))), n_boot=1000)
        d, dlo, dhi = block_bootstrap_ci(
            bl, lambda bs, ff=f: float(np.mean(np.abs(catm(bs, ff)))
                                       - np.mean(np.abs(catoff(bs, ff)))), n_boot=1000)
        print(f"  {name:14s} {t:13.3f} {b_:13.3f} {d:11.3f} "
              f"[{dlo:+9.3f},{dhi:+9.3f}]  {verdict(dlo, dhi, 0.0)}")
    print()

    # ---- 4. staleness ----------------------------------------------------
    print("--- 4. Freshness of the glucose the engine acts on ---")
    print("    Because the grid re-anchors to the newest reading every cycle,")
    print("    bucketedData[0] is the newest reading in BOTH arms: value age 0.")
    print("    What differs is how often a fresh value arrives:")
    for k, lab, cad in (("A", "1-min arm", 1.0), ("B", "5-min arm", 5.0)):
        pt, lo, hi = block_bootstrap_ci(bl, lambda bs, kk=k: float(np.mean(
            np.concatenate([np.diff(r[kk]["now"]) / 60000.0 for b in bs for r in b]))), n_boot=800)
        print(f"    {lab:12s} mean interval between fresh glucose values = "
              f"{pt:.2f} min [{lo:.2f}, {hi:.2f}]")
    print()

    # ---- 5. frozen glucose status ----------------------------------------
    print("--- 5. Loop cycles acting on an UNCHANGED glucose status ---")
    def frac_rep(bs, k):
        tot = rep = 0
        for b in bs:
            for r in b:
                a = r[k]
                same = ((a["glucose"][1:] == a["glucose"][:-1]) &
                        (a["delta"][1:] == a["delta"][:-1]) &
                        (a["sad"][1:] == a["sad"][:-1]) &
                        (a["lad"][1:] == a["lad"][:-1]))
                tot += len(same)
                rep += int(same.sum())
        return 100.0 * rep / max(tot, 1)
    for k, lab in (("A", "1-min arm"), ("B", "5-min arm")):
        pt, lo, hi = block_bootstrap_ci(bl, lambda bs, kk=k: frac_rep(bs, kk), n_boot=1000)
        print(f"  {lab:12s} {pt:5.1f}% of cycles identical to the previous cycle  "
              f"[{lo:.1f}, {hi:.1f}]")
    print()


if __name__ == "__main__":
    main()
