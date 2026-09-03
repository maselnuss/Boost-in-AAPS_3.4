#!/usr/bin/env python3
"""
Validate the Python port in `aaps_cadence_lib.py` against the SHIPPED Kotlin
bucketer, by driving `UkfBucketingParityTest` (plugins/main test source, already
in the tree) over the same corpus and diffing the two bucketed grids.

  step 1  --emit    write corpus.flat  (traces = whole days, newest-first)
  step 2  (gradle)  ./gradlew :plugins:main:testFullDebugUnitTest \
                        --tests '*UkfBucketingParityTest*'
                      BUCKET_CORPUS=<corpus>  BUCKET_OUT=<out>
  step 3  --diff    compare Kotlin output against the Python port

Both sides run with referenceTime = -1 per trace, which is the ONLY convention
the shipped test exposes; that is enough to validate the bucketing arithmetic.
The persistent-referenceTime phase behaviour is then simulated in Python only
(03/04), on the now-validated port.
"""
from __future__ import annotations

import argparse
import io
import os
import subprocess
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aaps_cadence_lib import GV, AutosensDataStore  # noqa: E402

DSN = os.environ.get("BOOST_DSN", "dbname=oref host=127.0.0.1 port=5432")


def q(sql: str) -> pd.DataFrame:
    out = subprocess.run(["psql", DSN, "-At", "-F", "\t", "-c", sql],
                         capture_output=True, text=True, check=True).stdout
    return pd.read_csv(io.StringIO(out), sep="\t", header=None)


def load_traces() -> dict[str, list[GV]]:
    """One trace per (user, day): 6 one-minute days from I, 3 five-minute days
    from I's own pre-change era, and 3 days each from two 5-min reference users."""
    traces: dict[str, list[GV]] = {}
    spec = [
        ("I", "2026-06-16", "2026-06-22", "imin"),   # 1-min era
        ("I", "2026-04-06", "2026-04-09", "ifive"),  # same user, 5-min era
        ("A", "2026-06-16", "2026-06-19", "afive"),
        ("tim", "2026-06-16", "2026-06-19", "tfive"),
    ]
    for uid, d0, d1, tag in spec:
        df = q(f"SELECT (extract(epoch from ts_utc)*1000)::bigint, cgm_mgdl FROM boost_cgm "
               f"WHERE user_id='{uid}' AND cgm_mgdl IS NOT NULL "
               f"AND ts_utc >= '{d0}' AND ts_utc < '{d1}' ORDER BY ts_utc")
        df.columns = ["ts", "bg"]
        df["day"] = pd.to_datetime(df.ts, unit="ms", utc=True).dt.floor("D").astype(str)
        for day, g in df.groupby("day"):
            if len(g) < 100:
                continue
            g = g.sort_values("ts", ascending=False)          # NEWEST FIRST
            traces[f"{tag}_{day[:10]}"] = [GV(int(t), float(v)) for t, v in zip(g.ts, g.bg)]
    return traces


def emit(path: str) -> None:
    traces = load_traces()
    with open(path, "w") as f:
        for name, rs in traces.items():
            f.write(f"T {name} {len(rs)}\n")
            for r in rs:
                f.write(f"{r.timestamp} {r.value}\n")
    print(f"wrote {len(traces)} traces -> {path}")
    for n, rs in traces.items():
        print(f"  {n:24s} n={len(rs):6d}")


def read_kotlin(path: str) -> dict[str, list[tuple[int, float]]]:
    out: dict[str, list[tuple[int, float]]] = {}
    with open(path) as f:
        lines = f.read().splitlines()
    i = 0
    while i < len(lines):
        parts = lines[i].split()
        assert parts[0] == "B", lines[i]
        name, n = parts[1], int(parts[2])
        pts = []
        for k in range(1, n + 1):
            a, b = lines[i + k].split()
            pts.append((int(a), float(b)))
        out[name] = pts
        i += n + 1
    return out


def diff(corpus: str, kotlin_out: str) -> int:
    traces = load_traces()
    kot = read_kotlin(kotlin_out)
    print(f"{'trace':24s} {'n_kot':>7s} {'n_py':>7s} {'ts_mismatch':>12s} "
          f"{'val_mismatch':>13s} {'max|dv|':>8s} {'5min_path':>10s}")
    bad = 0
    for name, rs in traces.items():
        if name not in kot:
            print(f"{name:24s}  MISSING from Kotlin output")
            bad += 1
            continue
        ads = AutosensDataStore(reference_time=-1)
        ads.bg_readings = rs
        ads.create_bucketed_data()
        py = [(b.timestamp, b.value) for b in (ads.bucketed_data or [])]
        k = kot[name]
        n = min(len(py), len(k))
        ts_mm = sum(1 for i in range(n) if py[i][0] != k[i][0])
        val_mm = sum(1 for i in range(n) if abs(py[i][1] - k[i][1]) > 1e-9)
        maxdv = max((abs(py[i][1] - k[i][1]) for i in range(n)), default=0.0)
        ok = (len(py) == len(k)) and ts_mm == 0 and val_mm == 0
        bad += 0 if ok else 1
        print(f"{name:24s} {len(k):7d} {len(py):7d} {ts_mm:12d} {val_mm:13d} "
              f"{maxdv:8.3f} {str(ads.last_used_5min):>10s}   {'OK' if ok else 'MISMATCH'}")
    print()
    print("PARITY: " + ("PASS — Python port is byte-identical to shipped Kotlin"
                        if bad == 0 else f"FAIL on {bad} trace(s)"))
    return bad


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--emit")
    ap.add_argument("--diff", nargs=2, metavar=("CORPUS", "KOTLIN_OUT"))
    a = ap.parse_args()
    if a.emit:
        emit(a.emit)
    elif a.diff:
        sys.exit(1 if diff(*a.diff) else 0)
    else:
        ap.print_help()
