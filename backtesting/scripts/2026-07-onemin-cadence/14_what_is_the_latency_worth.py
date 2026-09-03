#!/usr/bin/env python3
"""The latency gain is symmetric. What is it WORTH — to a person, and to a controller?

Sections 4.8-4.9 established that 1-min sampling delivers a 20 mg/dL excursion 3-7 min sooner
in both directions. That is a fact about the feed. Whether it is useful depends on two things
this script measures:

L. HOW OFTEN does the opportunity arise, and how many of those events go on to matter
   (cross 70 downward, or 180/250 upward)?
M. WHAT DOES THE DELAY COST in glucose? BG at the moment a 1-min consumer detects, versus at
   the moment a 5-min consumer detects, on the same event.
N. HOW MUCH OF THE AVAILABLE WARNING TIME is it? For events that go on to cross a clinically
   meaningful threshold, we measure the time from detection to that crossing at each cadence.
   If warning time is 25 min and you gain 5, that is a 20% extension. If warning time is 5 min
   and you gain 5, you have doubled it. This ratio, not the absolute minutes, is what decides
   whether a response channel can use the gain.

The response channels have their own time constants, which bound what any of this can buy:
rapid insulin onset ~15 min and peak ~60-90 min; a basal suspend acts through IOB decay over
~30+ min; oral glucose raises BG in ~10-15 min; a human reading an alarm acts in seconds.
"""
import sys, numpy as np, psycopg2
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')
from aaps_cadence_lib import block_bootstrap_ci
import datetime as dt

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts); ndays = (ts[-1]-ts[0])/86_400_000.0
day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
HOR = 30
print(f"{n:,} readings over {ndays:.1f} days\n")

def analyse(direction, MAG, TH_1MIN, TH_5MIN, clin_thr, clin_below):
    sgn = -1.0 if direction == "rise" else 1.0
    rows = []
    i = 60
    while i < n-HOR-1:
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: i += 1; continue
        k = np.arange(i, j+1)
        exc = sgn*(bg[i] - bg[k])
        if exc.max() < MAG: i += 1; continue
        # detection at each cadence, using that cadence's matched threshold
        w1 = np.where(exc >= TH_1MIN)[0]
        g5 = ((ts[k]-ts[i]) % 300_000) == 0
        w5 = np.where((exc >= TH_5MIN) & g5)[0]
        if not len(w1) or not len(w5): i += 1; continue
        d1, d5 = k[w1[0]], k[w5[0]]
        # does it go on to cross the clinically meaningful threshold, and when?
        fwd = np.arange(i, min(i+HOR*2, n))
        hit = np.where(bg[fwd] < clin_thr if clin_below else bg[fwd] > clin_thr)[0]
        t_cross = ts[fwd[hit[0]]] if len(hit) else None
        rows.append(dict(i=i, d1=d1, d5=d5,
                         bg1=bg[d1], bg5=bg[d5],
                         lag1=(ts[d1]-ts[i])/60_000.0, lag5=(ts[d5]-ts[i])/60_000.0,
                         cross=t_cross,
                         warn1=(t_cross-ts[d1])/60_000.0 if t_cross else None,
                         warn5=(t_cross-ts[d5])/60_000.0 if t_cross else None,
                         day=day[i]))
        i = j                                  # non-overlapping episodes
    return rows

for direction, MAG, t1, t5, clin, below, lbl in (
        ("fall", 20, 7, 7, 70.0, True,  "reaches hypoglycaemia (<70)"),
        ("rise", 20, 7, 7, 180.0, False, "reaches hyperglycaemia (>180)")):
    R = analyse(direction, MAG, t1, t5, clin, below)
    print(f"=== {direction.upper()}S of >= {MAG} mg/dL, matched thresholds ({t1}/{t5} mg/dL) ===")
    print(f"L. {len(R)} non-overlapping episodes = {len(R)/ndays:.2f} per day")
    got = [r for r in R if r['cross'] is not None]
    print(f"   of which {len(got)} ({100*len(got)/max(len(R),1):.0f}%) go on to {lbl}"
          f" = {len(got)/ndays:.2f} per day")
    d = np.array([r['lag5']-r['lag1'] for r in R])
    print(f"   latency gain: median {np.median(d):.1f} min, mean {d.mean():.1f}, "
          f"{100*np.mean(d>0):.0f}% of episodes earlier")
    cost = np.array([abs(r['bg5']-r['bg1']) for r in R])
    print(f"M. glucose moved during the delay: median {np.median(cost):.1f} mg/dL, "
          f"p90 {np.percentile(cost,90):.1f}, max {cost.max():.0f}")
    if got:
        w1 = np.array([r['warn1'] for r in got]); w5 = np.array([r['warn5'] for r in got])
        keep = w5 > 0
        w1, w5 = w1[keep], w5[keep]
        print(f"N. warning time before {'70' if below else '180'} is crossed (n={len(w1)}):")
        print(f"   5-min feed: median {np.median(w5):5.1f} min   1-min feed: median {np.median(w1):5.1f} min")
        print(f"   extension:  median {np.median(w1-w5):+.1f} min = "
              f"{100*np.median(w1-w5)/max(np.median(w5),1e-9):+.0f}% more warning")
        print(f"   episodes where the 5-min feed gave LESS THAN 15 min of warning: "
              f"{100*np.mean(w5<15):.0f}%  (and less than 5 min: {100*np.mean(w5<5):.0f}%)")
        # bootstrap the extension by day
        days_u = sorted(set(r['day'] for r in got))
        blocks = [np.array([r['warn1']-r['warn5'] for r in got if r['day']==dd and r['warn5']>0])
                  for dd in days_u]
        blocks = [b for b in blocks if len(b)]
        if len(blocks) > 5:
            m, lo, hi = block_bootstrap_ci(blocks, lambda bs: float(np.median(np.concatenate(bs))))
            print(f"   extension 95% CI (day block bootstrap): {m:+.1f} [{lo:+.1f}, {hi:+.1f}] min")
    print()

print("Reference time constants for the response channels (literature values, not measured here):")
for ch, onset, peak in (("rapid-acting insulin", 15, 75), ("basal suspend via IOB decay", 30, 90),
                        ("oral glucose", 10, 15), ("human reading an alarm", 0, 1)):
    print(f"   {ch:30s} onset ~{onset:3d} min, effect ~{peak:3d} min")
print("\nPROVISIONAL — one person's sensor record; no outcome data.")
