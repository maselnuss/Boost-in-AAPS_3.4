#!/usr/bin/env python3
"""Is the 1-min signalling gain ACTIONABLE — for an AID controller, or for a user?

Not an outcomes question. The test is whether a decision would be taken DIFFERENTLY, which
splits into two cases:

  (i) TIMING SHIFT — both cadences detect, one just sooner. For a consumer that re-decides on
      a fixed cycle, a shift smaller than one cycle changes nothing at all: the decision lands
      in the same epoch either way. So we measure how much of the gain exceeds one 5-min
      control cycle.

 (ii) DECISION CHANGE — the 5-min feed does not detect BEFORE the clinical threshold is
      crossed, or does not detect at all. Here the slower feed yields no warning to act on,
      and "earlier" becomes "at all". This is unambiguously actionable, and is the case worth
      counting.

We count both, on the episodes that go on to matter.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '/Users/timstreet/StudioProjects/Boost-AAPS-core/backtesting/scripts/2026-07-onemin-cadence')

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64); bg = np.array([float(x[1]) for x in r], float)
n = len(ts); ndays = (ts[-1]-ts[0])/86_400_000.0
HOR = 30

def go(direction, MAG, TH, clin, below, name):
    sgn = -1.0 if direction == "rise" else 1.0
    tot = timing_only = within_cycle = beyond_cycle = no_warn_5 = no_warn_1 = 0
    lead5, lead1 = [], []
    i = 60
    while i < n-HOR-1:
        j = np.searchsorted(ts, ts[i] + HOR*60_000)
        if j >= n or ts[j]-ts[i] > (HOR+3)*60_000: i += 1; continue
        k = np.arange(i, j+1); exc = sgn*(bg[i] - bg[k])
        if exc.max() < MAG: i += 1; continue
        # only episodes that go on to cross the clinical threshold
        fwd = np.arange(i, min(i+HOR*2, n))
        hit = np.where(bg[fwd] < clin if below else bg[fwd] > clin)[0]
        if not len(hit): i = j; continue
        t_cross = ts[fwd[hit[0]]]
        tot += 1
        w1 = np.where(exc >= TH)[0]
        g5 = ((ts[k]-ts[i]) % 300_000) == 0
        w5 = np.where((exc >= TH) & g5)[0]
        t1 = ts[k[w1[0]]] if len(w1) else None
        t5 = ts[k[w5[0]]] if len(w5) else None
        # warning = detected strictly BEFORE the threshold is crossed
        ok1 = t1 is not None and t1 < t_cross
        ok5 = t5 is not None and t5 < t_cross
        if ok1: lead1.append((t_cross-t1)/60_000.0)
        if ok5: lead5.append((t_cross-t5)/60_000.0)
        if not ok1: no_warn_1 += 1
        if not ok5: no_warn_5 += 1
        if ok1 and ok5:
            timing_only += 1
            gain = (t5-t1)/60_000.0
            if gain < 5.0: within_cycle += 1
            else: beyond_cycle += 1
        i = j
    print(f"=== {name} (n={tot} episodes, {tot/ndays:.2f}/day) ===")
    print(f"  (ii) DECISION CHANGE — 5-min feed gives NO warning before the threshold: "
          f"{no_warn_5} ({100*no_warn_5/max(tot,1):.0f}%)  = {no_warn_5/ndays:.2f}/day, "
          f"1 per {ndays/max(no_warn_5,1):.1f} days")
    print(f"       (1-min feed gives no warning: {no_warn_1} = {100*no_warn_1/max(tot,1):.0f}%)")
    print(f"       episodes RESCUED by 1-min (5-min blind, 1-min warns): "
          f"{max(no_warn_5-no_warn_1,0)} = {max(no_warn_5-no_warn_1,0)/ndays:.2f}/day")
    print(f"   (i) TIMING SHIFT — both detect: {timing_only}")
    print(f"       gain < one 5-min control cycle (no change for a 5-min decider): "
          f"{within_cycle} ({100*within_cycle/max(timing_only,1):.0f}%)")
    print(f"       gain >= one control cycle (a 5-min decider WOULD act an epoch earlier): "
          f"{beyond_cycle} ({100*beyond_cycle/max(timing_only,1):.0f}%) = {beyond_cycle/ndays:.2f}/day")
    if lead5: print(f"       median lead time  5-min {np.median(lead5):5.1f} min   "
                    f"1-min {np.median(lead1):5.1f} min")
    print()

go("fall", 20, 7, 70.0, True,  "FALLS reaching hypoglycaemia (<70)")
go("rise", 20, 7, 180.0, False, "RISES reaching hyperglycaemia (>180)")
go("fall", 15, 6, 70.0, True,  "FALLS reaching <70, more sensitive trigger (15/6 mg/dL)")
print("PROVISIONAL — one person's sensor record.")
