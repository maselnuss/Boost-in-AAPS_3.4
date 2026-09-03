#!/usr/bin/env python3
"""Challenge to script 15's "5-min feed gives no warning on 44% of hypo-bound falls".

Three ways that number could be an artefact rather than a finding:

  C1. NOMINAL RESCUES. If the 1-min feed warns only 1-2 minutes before the threshold, the
      "rescue" is not actionable and should not be counted. We report the lead-time
      distribution ON THE RESCUED EPISODES, and re-count under an actionability floor of 5,
      10 and 15 minutes of lead.
  C2. GRID PHASE. Script 15 anchored the 5-min grid at the episode start, which hands the
      5-min consumer a free sample at t=0. A real sensor has an arbitrary phase. We re-run
      over all five possible phases and report the spread.
  C3. STARTING PROXIMITY. If these episodes simply begin near 70, the threshold is crossed
      before any detector could act and the comparison is degenerate. We report BG at
      episode start and time-to-threshold for the no-warning cases.
"""
import sys, numpy as np, psycopg2
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts); ndays = (ts[-1]-ts[0])/86_400_000.0
HOR, MAG, TH, CLIN = 30, 20, 7, 70.0

def episodes(phase):
    out = []
    i = 60
    while i < n-HOR-1:
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: i += 1; continue
        k = np.arange(i, j+1); exc = bg[i] - bg[k]
        if exc.max() < MAG: i += 1; continue
        fwd = np.arange(i, min(i+HOR*2, n))
        hit = np.where(bg[fwd] < CLIN)[0]
        if not len(hit): i = j; continue
        t_cross = ts[fwd[hit[0]]]
        w1 = np.where(exc >= TH)[0]
        g5 = (((ts[k]-ts[i])/60_000.0).astype(int) % 5) == (phase % 5)
        w5 = np.where((exc >= TH) & g5)[0]
        t1 = ts[k[w1[0]]] if len(w1) else None
        t5 = ts[k[w5[0]]] if len(w5) else None
        out.append(dict(bg0=bg[i], tc=(t_cross-ts[i])/60_000.0,
                        l1=(t_cross-t1)/60_000.0 if (t1 is not None and t1 < t_cross) else None,
                        l5=(t_cross-t5)/60_000.0 if (t5 is not None and t5 < t_cross) else None))
        i = j
    return out

E = episodes(0)
print(f"n = {len(E)} hypo-bound falls over {ndays:.1f} days\n")

print("C1. Lead time on the episodes script 15 counted as RESCUED (5-min blind, 1-min warns)")
resc = [e for e in E if e['l5'] is None and e['l1'] is not None]
l = np.array([e['l1'] for e in resc])
print(f"   n={len(resc)}. 1-min lead time: median {np.median(l):.1f} min, "
      f"p25 {np.percentile(l,25):.1f}, p75 {np.percentile(l,75):.1f}, max {l.max():.0f}")
print(f"   {'floor':>8s} {'rescues surviving':>18s} {'per day':>9s} {'1 per N days':>13s}")
for floor in (0, 5, 10, 15, 20):
    s = int((l >= floor).sum())
    print(f"   {floor:6d}m {s:18d} {s/ndays:9.2f} {ndays/max(s,1):13.1f}")

print("\nC2. Grid phase — script 15 used phase 0 (a free sample at episode start)")
print(f"   {'phase':>6s} {'5-min blind':>12s} {'%':>6s} {'rescued (>=10m lead)':>21s}")
for p in range(5):
    Ep = episodes(p)
    blind = [e for e in Ep if e['l5'] is None]
    resc_p = [e for e in Ep if e['l5'] is None and e['l1'] is not None and e['l1'] >= 10]
    print(f"   {p:6d} {len(blind):12d} {100*len(blind)/max(len(Ep),1):5.0f}% {len(resc_p):21d}")

print("\nC3. Are these episodes simply starting near the threshold?")
blind = [e for e in E if e['l5'] is None]
seen  = [e for e in E if e['l5'] is not None]
for lbl, S in (("5-min BLIND", blind), ("5-min warns", seen)):
    b0 = np.array([e['bg0'] for e in S]); tc = np.array([e['tc'] for e in S])
    print(f"   {lbl:12s} n={len(S):3d}  BG at episode start: median {np.median(b0):5.1f} "
          f"[p25 {np.percentile(b0,25):.0f}, p75 {np.percentile(b0,75):.0f}]   "
          f"time to cross 70: median {np.median(tc):4.1f} min")
print("\nPROVISIONAL — one person's sensor record.")
