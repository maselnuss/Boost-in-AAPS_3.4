#!/usr/bin/env python3
"""Reconstruct the calculation pipeline from an AAPS log and find where readings are lost.

The uploaded record answers a question the Nightscout record cannot: what happened between a
glucose reading arriving and a determination being published, and in particular whether cycles are
being abandoned rather than merely running late.

Three things are counted. How long a run takes, from the calculation starting to InvokeLoopWorker
finishing, which is the quantity the compute-time hypothesis rests on. What each InvokeLoopWorker
run concluded, since "already looped with that value" and "no calculation needed" are both cycles
that produced nothing and they have different causes. And how often a run is stopped before it
reaches the loop at all, which is the only way a reading can arrive and leave no trace.
"""
import re
import sys
from collections import Counter

TS = re.compile(r"^(\d\d):(\d\d):(\d\d)\.(\d\d\d)")
START = re.compile(r"Starting calculation worker: (\S+) to")
STOP = re.compile(r"Stopping calculation thread: (\S+)")
DONE = re.compile(r"Worker result SUCCESS for class app\.aaps\.workflow\.(?:iob\.)?(\w+) Data \{([^}]*)\}")


def secs(line):
    m = TS.match(line)
    if not m:
        return None
    h, mi, s, ms = (int(x) for x in m.groups())
    return h * 3600 + mi * 60 + s + ms / 1000.0


def parse(path):
    runs = []                 # one entry per calculation started
    current = None
    outcomes = Counter()
    for line in open(path, errors="ignore"):
        t = secs(line)
        if t is None:
            continue
        m = START.search(line)
        if m:
            if current is not None:          # a run that never reached the loop
                current["abandoned"] = True
                runs.append(current)
            current = dict(t0=t, cause=m.group(1), abandoned=False, reached=None, result=None)
            continue
        m = STOP.search(line)
        if m and current is not None:
            current["stopped_by"] = m.group(1)
            continue
        m = DONE.search(line)
        if m and m.group(1) == "InvokeLoopWorker" and current is not None:
            current["reached"] = t
            current["result"] = m.group(2).replace("Result : ", "").strip() or "loop invoked"
            outcomes[current["result"]] += 1
            runs.append(current)
            current = None
    if current is not None:
        current["abandoned"] = True
        runs.append(current)
    return runs, outcomes


def main():
    for path in sys.argv[1:]:
        runs, outcomes = parse(path)
        if not runs:
            continue
        done = [r for r in runs if r["reached"] is not None]
        durs = sorted(r["reached"] - r["t0"] for r in done if r["reached"] > r["t0"])
        abandoned = [r for r in runs if r["abandoned"]]
        bg_runs = [r for r in runs if r["cause"] == "EventNewBG"]
        bg_lost = [r for r in bg_runs if r["abandoned"]]
        print(f"=== {path.split('/')[-1]} ===")
        print(f"  calculations started        {len(runs)}")
        if durs:
            print(f"  start to loop, seconds      median {durs[len(durs)//2]:.1f}   "
                  f"p90 {durs[int(.9*len(durs))]:.1f}   max {max(durs):.1f}")
        print(f"  abandoned before the loop   {len(abandoned)}")
        print(f"  of those, BG-triggered      {len(bg_lost)}  <- a reading that produced nothing")
        for k, v in outcomes.most_common():
            print(f"    {v:4d}  {k}")
        causes = Counter(r["cause"] for r in runs)
        print("  what started them: " + ", ".join(f"{k} {v}" for k, v in causes.most_common()))
        print()


if __name__ == "__main__":
    main()
