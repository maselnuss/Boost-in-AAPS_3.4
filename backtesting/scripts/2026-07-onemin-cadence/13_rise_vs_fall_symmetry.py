#!/usr/bin/env python3
"""Is the latency benefit of 1-min sampling symmetric in direction?

If the one advantage of a faster feed is REPORTING LATENCY rather than signal content, the
advantage is a property of the sampling grid and should not care which way glucose is going.
Script 11 measured it for falls only. Here we run the identical matched-false-alarm design
for RISES and for FALLS side by side, on the same record, so the comparison is like for like.

A grid effect predicts equal gains. A difference would mean something direction-specific is
happening — most plausibly that rises and falls differ in how sharply they cross a threshold.
We therefore also measure the steepness of the two event classes, so that any asymmetry in
the detection result can be checked against an asymmetry in the events themselves.
"""
import sys, numpy as np, psycopg2
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts)
HOR = 30

def run(direction, MAG):
    sgn = -1.0 if direction == "rise" else 1.0      # excursion = sgn*(bg[i] - bg[k])
    ev, non = [], []
    for i in range(60, n-HOR-1):
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: continue
        exc = (sgn*(bg[i] - bg[i:j+1])).max()
        if exc >= MAG: ev.append(i)
        elif exc < MAG*0.4: non.append(i)
    ev, non = np.array(ev), np.array(non)
    def curve(stride):
        out = []
        for th in np.arange(2.0, float(MAG)+0.01, 1.0):
            def first_cross(i):
                j = min(i+HOR+1, n)
                k = np.arange(i, j)
                k = k[((ts[k]-ts[i]) % (stride*60_000)) == 0]
                w = np.where(sgn*(bg[i] - bg[k]) >= th)[0]
                return (ts[k[w[0]]]-ts[i])/60_000.0 if len(w) else None
            lags = [first_cross(i) for i in ev]
            det = [l for l in lags if l is not None]
            fa = np.mean([first_cross(i) is not None for i in non[:4000]])
            if det: out.append((fa, float(np.median(det)), len(det)/len(ev), th))
        return out
    c1, c5 = curve(1), curve(5)
    # steepness of the events themselves: max excursion per minute over the horizon
    steep = []
    for i in ev[:6000]:
        j = min(i+HOR+1, n)
        d = sgn*(bg[i] - bg[i:j])
        w = np.where(d >= MAG)[0]
        if len(w): steep.append(MAG / max((ts[i+w[0]]-ts[i])/60_000.0, 1e-9))
    return ev, non, c1, c5, np.array(steep)

for MAG in (20, 30):
    print(f"\n=== excursion >= {MAG} mg/dL within {HOR} min ===")
    res = {}
    for d in ("fall", "rise"):
        res[d] = run(d, MAG)
        ev, non, _, _, st = res[d]
        print(f"  {d+'s':6s}: {len(ev):,} event starts, {len(non):,} quiet starts, "
              f"median time-to-{MAG} {MAG/np.median(st):.1f} min "
              f"(median steepness {np.median(st):.2f} mg/dL/min)")
    print(f"  {'FA':>4s} | {'':>6s} {'1-min thr/lag/sens':>22s} {'5-min thr/lag/sens':>22s} {'gain':>7s}")
    for target in (0.02, 0.05, 0.10, 0.20):
        for d in ("fall", "rise"):
            _, _, c1, c5, _ = res[d]
            p1 = min(c1, key=lambda z: abs(z[0]-target)); p5 = min(c5, key=lambda z: abs(z[0]-target))
            if abs(p1[0]-target) > 0.05 or abs(p5[0]-target) > 0.05:
                print(f"  {target:4.0%} | {d:6s} {'(no matched threshold)':>22s}"); continue
            print(f"  {target:4.0%} | {d:6s} {p1[3]:5.0f} {p1[1]:5.1f}m {p1[2]:5.0%} "
                  f"     {p5[3]:5.0f} {p5[1]:5.1f}m {p5[2]:5.0%}      {p5[1]-p1[1]:+6.1f}m")
print("\nPROVISIONAL — one person's sensor record.")
