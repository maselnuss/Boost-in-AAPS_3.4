#!/usr/bin/env python3
"""Actionability of the 1-min gain, using the CORRECTED index-based 5-min grid.

Supersedes scripts 14, 15 and 16, whose 5-minute view was crippled by timestamp jitter.
Question: does the earlier signal let a decision be taken DIFFERENTLY, for an AID controller
or for a user? Averaged over all five grid phases.
"""
import sys, numpy as np, psycopg2
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from aaps_cadence_lib import block_bootstrap_ci

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts); ndays = (ts[-1]-ts[0])/86_400_000.0
HOR, MAG, TH = 30, 20, 7

def run(direction, clin, below, name):
    sgn = -1.0 if direction == "rise" else 1.0
    ep = []
    i = 60
    while i < n-HOR-1:
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: i += 1; continue
        k = np.arange(i, j+1); exc = sgn*(bg[i]-bg[k])
        if exc.max() < MAG: i += 1; continue
        fwd = np.arange(i, min(i+HOR*2, n))
        hit = np.where(bg[fwd] < clin if below else bg[fwd] > clin)[0]
        if not len(hit): i = j; continue
        ep.append((i, k, exc, ts[fwd[hit[0]]])); i = j
    L1, L5, blind1, blind5, gains, tot = [], [], 0, 0, [], 0
    for (i, k, exc, tc) in ep:
        w1 = np.where(exc >= TH)[0]
        t1 = ts[k[w1[0]]] if len(w1) else None
        for ph in range(5):
            tot += 1
            sel = ((k-i-ph) % 5 == 0)
            w5 = np.where((exc >= TH) & sel)[0]
            t5 = ts[k[w5[0]]] if len(w5) else None
            o1 = t1 is not None and t1 < tc
            o5 = t5 is not None and t5 < tc
            if o1: L1.append((tc-t1)/60_000.0)
            else: blind1 += 1
            if o5: L5.append((tc-t5)/60_000.0)
            else: blind5 += 1
            if o1 and o5: gains.append((t5-t1)/60_000.0)
    print(f"=== {name}: {len(ep)} episodes = {len(ep)/ndays:.2f}/day ===")
    print(f"   warning before threshold: 5-min median {np.median(L5):5.1f} min   "
          f"1-min median {np.median(L1):5.1f} min")
    g = np.array(gains)
    print(f"   extension: median {np.median(g):+.1f} min, mean {g.mean():+.2f}  "
          f"({100*np.mean(g>0):.0f}% of episode-phases earlier)")
    print(f"   DECISION CHANGE — no warning at all before the threshold: "
          f"5-min {100*blind5/tot:.1f}% vs 1-min {100*blind1/tot:.1f}%")
    net = (blind5-blind1)/5.0
    print(f"   net episodes warned by 1-min but not 5-min: {net:.1f} over {ndays:.0f} days "
          f"= 1 per {ndays/max(net,1e-9):.0f} days" if net > 0 else
          f"   net episodes warned by 1-min but not 5-min: {net:.1f} (none)")
    print(f"   gain >= one 5-min control cycle (an AID would act an epoch earlier): "
          f"{100*np.mean(g >= 5):.1f}% of episode-phases = {np.sum(g>=5)/5/ndays:.2f}/day")
    print()

run("fall", 70.0, True,  "FALLS reaching hypoglycaemia (<70)")
run("rise", 180.0, False, "RISES reaching hyperglycaemia (>180)")
print("Actuator onsets for reference: rapid insulin ~15 min (peak 60-90); basal suspend via")
print("IOB decay ~30 min; oral glucose ~10-15 min; a person reading an alarm ~0 min.")
print("\nPROVISIONAL — one person's sensor record.")
