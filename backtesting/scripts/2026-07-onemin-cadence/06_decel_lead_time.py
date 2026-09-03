#!/usr/bin/env python3
"""Does a SHORT-WINDOW deceleration signal at 1-min lead the shipped deltaDeclining?

DESIGN QUESTION (Tim, 2026-07-30). Anchor the meal state machine's age gates to
wall-clock so a 1-min loop keeps the validated 15-min hysteresis, but allow the fall-back
to IDLE to fire EARLIER when minute-by-minute data shows the climb decelerating. Slow to
commit, fast to abandon.

The shipped detector cannot do the second half. `deltaDeclining` reads
[longAvgDelta, shortAvgDelta, delta] — three TIME-WINDOW averages (~40/10/5 min) from one
cycle, not a 3-cycle history — and requires strict monotonic decline across them. Those
windows are wall-clock, so at 1-min cadence they are cleaner but NOT shorter: the latency
is unchanged. A faster exit therefore needs a new short-window signal that only exists at
high cadence.

This measures whether such a signal is worth having, BEFORE any code:
  1. LEAD  — how many minutes earlier does it fire than deltaDeclining, at the same event?
  2. COST  — how often does it fire during a climb that then CONTINUES (a premature exit)?

Lead without precision is worthless: a detector that fires 6 min earlier but also fires
mid-climb just abandons real meals sooner.

PRIOR (must not be ignored): backtesting/scripts/2026-07-fastcarb-confirm rejected a
deceleration guard — only 10% of crash events were decelerating+modest, guard flagged 77
shots at crash:needed 18:12, "Do NOT build the decelerating guard". That tested TRIMMING
THE CONFIRMED SHOT on 5-min data as a crash predictor. This is a different test (exiting
the state, 1-min data, withholding future dose) but the 10% base rate stands: this can
only ever be hysteresis recovery, never a crash defence.

Confidence: PROVISIONAL. One user's glucose (the only 1-min arm in the cohort), detection
and timing only. No dosing outcome, no counterfactual.
"""
import os, sys, datetime as dt
import numpy as np
import psycopg2

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from aaps_cadence_lib import grid_reanchored, deltas_vectorised, delta_declining, block_bootstrap_ci, verdict

DSN = "dbname=oref host=127.0.0.1 port=5432"
USER = "I"
ONE_MIN_FROM = "2026-05-24"          # cadence changed 2026-05-23; take whole days after

# --- what counts as a rise episode worth exiting from
RISE_DELTA = 3.0                      # mg/dL per 5 min, the primer's own floor
MIN_RISE_MGDL = 25.0                  # episode must actually go somewhere
MAX_EPISODE_MIN = 90

# --- candidate short-window detector (only estimable at high cadence)
SHORT_W_MIN = 3                       # compare last 3 min against the previous 3 min
DECEL_DROP = 1.0                      # mg/dL per 5 min the slope must fall by


def load():
    with psycopg2.connect(DSN) as c, c.cursor() as cur:
        cur.execute(
            "select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
            "where user_id=%s and cgm_mgdl is not null and ts_utc >= %s order by ts_utc",
            (USER, ONE_MIN_FROM))
        rows = cur.fetchall()
    ts = np.array([int(r[0]) for r in rows], dtype=np.int64)
    bg = np.array([float(r[1]) for r in rows], dtype=float)
    return ts, bg


def slope_per5(ts, bg, i, minutes):
    """mg/dL per 5 min over the `minutes` ending at index i, from RAW 1-min data.

    Window bounds by searchsorted, NOT a full-array boolean mask — the latter makes the
    whole sweep O(n^2) (77k readings => ~6e9 element tests).
    """
    lo = int(np.searchsorted(ts, ts[i] - minutes * 60_000, side="left"))
    if i - lo < 1:
        return None
    x = (ts[lo:i + 1] - ts[lo]) / 60_000.0
    y = bg[lo:i + 1]
    if x[-1] - x[0] < minutes * 0.6:
        return None
    sxx = float(((x - x.mean()) ** 2).sum())
    if sxx <= 0:
        return None
    return float(((x - x.mean()) * (y - y.mean())).sum() / sxx * 5.0)


def main():
    ts, bg = load()
    days = len({dt.datetime.fromtimestamp(t / 1000, dt.UTC).date() for t in ts})
    print(f"user {USER}: {len(bg):,} readings over {days} days from {ONE_MIN_FROM}\n")

    # Shipped front end at every minute, VECTORISED. The re-anchored grid (see Finding 0 —
    # clone() drops referenceTime, so buckets sit at now, now-5, now-10 ... every cycle) is
    # exactly linear interpolation at 5-min offsets with Kotlin-style rounding, which is what
    # grid_reanchored() does one cycle at a time. Building the whole (n, 9) matrix at once and
    # handing it to deltas_vectorised gives identical numbers without the per-cycle Python loop.
    n = len(ts)
    K = 9
    offs = (np.arange(K) * 300_000).astype(np.int64)
    targets = ts[:, None] - offs[None, :]                       # (n, 9) bucket timestamps
    vals = np.floor(np.interp(targets.ravel(), ts, bg) + 0.5).reshape(n, K)
    vals[targets < ts[0]] = np.nan                              # not enough history yet
    delta, short_avg, long_avg = deltas_vectorised(vals)
    delta5 = delta
    # deltaDeclining: strict monotonic decline across long -> short -> delta
    declining = (long_avg > short_avg) & (short_avg > delta)
    bad = ~np.isfinite(delta5)
    declining[bad] = False
    delta5 = np.where(bad, np.nan, delta5)

    # Spot-check the vectorised path against the per-cycle reference on 200 random cycles.
    rng = np.random.default_rng(20260730)
    checked = mism = 0
    for i in rng.integers(300, n, size=200):
        w = slice(max(0, i - 200), i + 1)
        b = grid_reanchored(ts[w][::-1], bg[w][::-1])
        if len(b) < K:
            continue
        ref = np.array([[x.recalculated for x in b[:K]]], dtype=float)
        rd, rs, rl = deltas_vectorised(ref)
        checked += 1
        if not (np.isclose(rd[0], delta5[i]) and np.isclose(rs[0], short_avg[i]) and np.isclose(rl[0], long_avg[i])):
            mism += 1
    print(f"vectorised front end vs per-cycle reference: {checked - mism}/{checked} identical"
          + ("" if mism == 0 else f"  ** {mism} MISMATCH **") + "\n")
    if mism:
        raise SystemExit("front-end mismatch — refusing to report on an unverified pipeline")

    # Candidate: short-window slope falling vs the preceding equal window
    fast = np.zeros(n, bool)
    for i in range(n):
        if i < 60:
            continue
        s_now = slope_per5(ts, bg, i, SHORT_W_MIN)
        j = np.searchsorted(ts, ts[i] - SHORT_W_MIN * 60_000)
        s_prev = slope_per5(ts, bg, j, SHORT_W_MIN) if j > 0 else None
        if s_now is None or s_prev is None:
            continue
        fast[i] = (s_prev - s_now) >= DECEL_DROP and s_now < s_prev

    # ---- episodes: a sustained rise, then the moment the climb actually ends (the peak)
    episodes = []
    i = 60
    while i < n - 1:
        if not (delta5[i] >= RISE_DELTA):
            i += 1
            continue
        start = i
        j = i
        peak_i, peak_v = i, bg[i]
        while j < n - 1 and (ts[j] - ts[start]) <= MAX_EPISODE_MIN * 60_000:
            if bg[j] > peak_v:
                peak_v, peak_i = bg[j], j
            if bg[j] < peak_v - 8.0 and (ts[j] - ts[peak_i]) > 5 * 60_000:
                break
            j += 1
        if peak_v - bg[start] >= MIN_RISE_MGDL and peak_i > start:
            episodes.append((start, peak_i, j))
        i = max(j, start + 1)

    print(f"rise episodes (>= {MIN_RISE_MGDL:.0f} mg/dL climb): {len(episodes)}\n")

    # ---- LEAD: first fire of each detector between episode start and peak+10min
    leads, both = [], 0
    fast_only = decl_only = neither = 0
    for (s, p, e) in episodes:
        hi = min(n - 1, np.searchsorted(ts, ts[p] + 10 * 60_000))
        seg = slice(s, hi + 1)
        fi = np.where(fast[seg])[0]
        di = np.where(declining[seg])[0]
        if len(fi) and len(di):
            both += 1
            leads.append((ts[s + di[0]] - ts[s + fi[0]]) / 60_000.0)
        elif len(fi):
            fast_only += 1
        elif len(di):
            decl_only += 1
        else:
            neither += 1

    print("WHICH DETECTOR FIRES, per episode")
    print(f"  both            {both:5d}")
    print(f"  fast only       {fast_only:5d}")
    print(f"  deltaDeclining only {decl_only:5d}")
    print(f"  neither         {neither:5d}\n")

    if leads:
        leads = np.array(leads)
        # block bootstrap by DAY of episode start
        day_of = {}
        k = 0
        for (s, p, e) in episodes:
            hi = min(n - 1, np.searchsorted(ts, ts[p] + 10 * 60_000))
            seg = slice(s, hi + 1)
            if len(np.where(fast[seg])[0]) and len(np.where(declining[seg])[0]):
                d = dt.datetime.fromtimestamp(ts[s] / 1000, dt.UTC).date()
                day_of.setdefault(d, []).append(leads[k]); k += 1
        blocks = [np.array(v) for v in day_of.values()]
        med, lo, hi_ = block_bootstrap_ci(blocks, lambda blks: float(np.median(np.concatenate(blks))))
        print(f"LEAD of the short-window detector over deltaDeclining (min)")
        print(f"  median {med:+.2f}  [{lo:+.2f}, {hi_:+.2f}]   {verdict(lo, hi_)}")
        print(f"  fires EARLIER on {100.0*np.mean(leads > 0):.1f}% of episodes, "
              f"same minute {100.0*np.mean(leads == 0):.1f}%, later {100.0*np.mean(leads < 0):.1f}%\n")

    # ---- COST: fires while the climb CONTINUES (would be a premature exit)
    rising = delta5 >= RISE_DELTA
    cont = np.zeros(n, bool)
    for i in range(n):
        if not rising[i]:
            continue
        hz = np.searchsorted(ts, ts[i] + 15 * 60_000)
        if hz < n:
            cont[i] = bg[min(hz, n - 1)] - bg[i] >= 10.0   # still climbing 15 min later
    # ---- THE DECIDING METRIC: at each detector's FIRST fire in an episode, how much of the
    # climb is still to come? An early exit is only useful if the rise is genuinely over. This is
    # what "drop back earlier" actually costs, and lead time alone cannot show it.
    print("AT THE FIRST FIRE OF AN EPISODE — how much climb remains to the peak")
    print(f"  {'detector':16s} {'n':>5s} {'median remaining':>17s} {'p90':>7s} {'>=10 mg/dL':>11s} {'median min to peak':>19s}")
    rows = {}
    for name, det in (("short-window", fast), ("deltaDeclining", declining)):
        rem, tto = [], []
        for (s_, p_, e_) in episodes:
            hi = min(n - 1, np.searchsorted(ts, ts[p_] + 10 * 60_000))
            idx = np.where(det[s_:hi + 1])[0]
            if not len(idx):
                continue
            f = s_ + idx[0]
            rem.append(float(bg[p_] - bg[f]))
            tto.append((ts[p_] - ts[f]) / 60_000.0)
        if not rem:
            continue
        rem = np.array(rem); tto = np.array(tto)
        rows[name] = rem
        print(f"  {name:16s} {len(rem):5d} {np.median(rem):17.1f} {np.percentile(rem, 90):7.1f} "
              f"{100.0*np.mean(rem >= 10):10.1f}% {np.median(tto):19.1f}")
    if len(rows) == 2:
        a, b_ = rows["short-window"], rows["deltaDeclining"]
        print(f"\n  short-window abandons {np.median(a) - np.median(b_):+.1f} mg/dL more climb "
              f"(median), and fires with >=10 mg/dL still to come "
              f"{100*np.mean(a >= 10) - 100*np.mean(b_ >= 10):+.1f} pp more often\n")

    for name, det in (("short-window", fast), ("deltaDeclining", declining)):
        m = det & rising
        if m.sum():
            print(f"{name:15s} fires on {m.sum():6d} rising cycles; "
                  f"{100.0*np.mean(cont[m]):.1f}% were STILL climbing +10 mg/dL 15 min later")
    print("\nPROVISIONAL — one user's glucose, detection/timing only, no dosing outcome.")


if __name__ == "__main__":
    main()
