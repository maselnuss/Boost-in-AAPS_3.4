#!/usr/bin/env python3
"""What IS the minute-by-minute delta, physically? Signal or quantisation noise?

Tim's question (2026-07-30): during a climb, do 1-min deltas rise then fall smoothly —
carrying information — or do they flip +/- minute to minute, i.e. noise?

This is the foundational measurement. Everything in scripts 06/07 came out at chance, and
if 1-min deltas are quantisation-limited that is WHY, mechanistically, rather than one
more empirical null. It also tells us where 1-min data DOES win, which is the constructive
half: a sensor reporting integers cannot resolve a change smaller than 1 mg/dL, so the
question is which real events exceed that floor per minute and which do not.

PROVISIONAL: one user's glucose (the only 1-min arm in the cohort).
"""
import sys, numpy as np, psycopg2, collections
sys.path.insert(0, '.')
from aaps_cadence_lib import block_bootstrap_ci, verdict

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    rows = cur.fetchall()
ts = np.array([int(r[0]) for r in rows], np.int64)
bg = np.array([float(r[1]) for r in rows], float)

# strictly-consecutive 1-min pairs only
gap = np.diff(ts) / 60_000.0
ok = np.abs(gap - 1.0) < 0.2
d1 = np.diff(bg)[ok]
print(f"consecutive 1-min pairs: {len(d1):,}\n")

print("A. RAW 1-MIN CHANGE DISTRIBUTION  (the sensor reports INTEGER mg/dL)")
cnt = collections.Counter(d1.astype(int))
tot = len(d1)
for v in sorted(cnt):
    if abs(v) <= 4:
        print(f"   {v:+3d} mg/dL  {cnt[v]:7d}  {100*cnt[v]/tot:5.1f}%")
big = sum(c for v, c in cnt.items() if abs(v) > 4)
print(f"   |>4|      {big:7d}  {100*big/tot:5.1f}%")
print(f"   -> {100*cnt.get(0,0)/tot:.1f}% of minutes show NO change at all; "
      f"{100*sum(cnt.get(v,0) for v in (-1,0,1))/tot:.1f}% are within +/-1\n")

print("B. IS IT SIGNAL OR ALTERNATION?  sign-flip rate of consecutive non-zero 1-min changes")
def flip_rate(mask_idx):
    s = np.sign(d1[mask_idx])
    s = s[s != 0]
    return float(np.mean(s[1:] != s[:-1])) if len(s) > 1 else np.nan
# climbing = 5-min-smoothed slope clearly positive
sm = np.convolve(bg, np.ones(5)/5, mode='same')
slope5 = np.full(len(bg), np.nan)
slope5[5:] = (sm[5:] - sm[:-5])            # mg/dL per 5 min
sl = slope5[1:][ok]
climb = np.isfinite(sl) & (sl >= 3.0)
flat  = np.isfinite(sl) & (np.abs(sl) < 1.0)
fast  = np.isfinite(sl) & (sl >= 8.0)
print(f"   during CLIMB   (>=3 mg/dL/5min): {100*flip_rate(climb):.1f}%   n={climb.sum():,}")
print(f"   during FAST    (>=8 mg/dL/5min): {100*flip_rate(fast):.1f}%   n={fast.sum():,}")
print(f"   during FLAT    (|slope|<1):      {100*flip_rate(flat):.1f}%   n={flat.sum():,}")
print("   (pure independent noise -> ~50%; a clean monotone climb -> ~0%)\n")

print("C. AUTOCORRELATION of the 1-min change series")
x = d1 - d1.mean()
for lag in (1, 2, 3, 5, 10):
    r = float(np.corrcoef(x[:-lag], x[lag:])[0, 1])
    print(f"   lag {lag:2d} min: {r:+.3f}")
print("   (negative lag-1 = alternation, the signature of quantisation round-trip)\n")

print("D. VARIANCE DECOMPOSITION — how much of the 1-min change is trend vs residual?")
# local trend = centred 15-min linear fit; residual = what 1-min adds beyond it
W = 15
tr = np.full(len(bg), np.nan)
for i in range(W, len(bg)-W):
    y = bg[i-W:i+W+1]
    xx = np.arange(-W, W+1, dtype=float)
    tr[i] = float((xx*(y-y.mean())).sum()/ (xx**2).sum())    # mg/dL per min
trend_1min = tr[1:][ok]
m = np.isfinite(trend_1min)
resid = d1[m] - trend_1min[m]
print(f"   SD of raw 1-min change      : {d1[m].std():.2f} mg/dL")
print(f"   SD of the 15-min trend part : {trend_1min[m].std():.2f} mg/dL/min")
print(f"   SD of the residual          : {resid.std():.2f} mg/dL")
snr = trend_1min[m].std()/resid.std()
print(f"   -> signal:noise at 1 min = {snr:.2f}")
# same at 5 min
d5 = bg[5:] - bg[:-5]
tr5 = tr[5:]*5.0
m5 = np.isfinite(tr5)
r5 = d5[m5[:len(d5)]] - tr5[m5][:len(d5[m5[:len(d5)]])]
print(f"   -> signal:noise at 5 min = {tr5[m5].std()/r5.std():.2f}\n")

print("E. WHERE DOES 1-MIN WIN?  minutes needed to see a change above the 1 mg/dL floor")
print(f"   {'true rate':>22s} {'per minute':>11s} {'min to clear 1 mg/dL':>21s}")
for per5 in (1, 2, 3, 5, 8, 12, 20, 40):
    per_min = per5/5.0
    print(f"   {per5:3d} mg/dL / 5 min {per_min:10.2f} {1.0/per_min:20.1f}")
print("   A climb only becomes visible minute-to-minute once it exceeds ~5 mg/dL/5min;")
print("   below that the per-minute change is smaller than the sensor's own step.\n")

# empirical: detection lead for LARGE fast events (where 1-min should win)
print("F. FAST EVENTS — does 1-min detect a sharp FALL sooner than 5-min?")
drops = []
for i in range(60, len(bg)-30):
    if bg[i] - bg[min(i+20, len(bg)-1)] >= 25.0:      # >=25 mg/dL fall within 20 min
        drops.append(i)
seen = []; last = -999
ev = [i for i in drops if (i-last > 30 and (last := i) or True)]
lead = []
for i in ev:
    j1 = next((k for k in range(i, min(i+25, len(bg)-1)) if bg[k]-bg[i] <= -5), None)
    j5 = next((k for k in range(i, min(i+25, len(bg)-1)) if (k-i) % 5 == 0 and bg[k]-bg[i] <= -5), None)
    if j1 is not None and j5 is not None:
        lead.append((ts[j5]-ts[j1])/60_000.0)
if lead:
    lead = np.array(lead)
    print(f"   n={len(lead)} sharp falls; 1-min sees a -5 mg/dL move "
          f"{np.median(lead):.1f} min sooner (mean {lead.mean():.1f}), "
          f"earlier on {100*np.mean(lead>0):.0f}% of events")
print("\nPROVISIONAL — one user, descriptive.")
