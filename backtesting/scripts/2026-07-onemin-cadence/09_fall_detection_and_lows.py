#!/usr/bin/env python3
"""Does 1-min data help the FALL side — compression-low detection and forward-low prediction?

08 showed 1-min's only real edge is on fast falls (-5 mg/dL seen 3.0 min sooner on 89% of
5,961 sharp falls). These are the two places Boost acts on falls.

PART A — the UKF compression gate at 1-min. Shipped constants:
    compressionBgCeiling  = 75.0    only act below this
    compressionIobMaxU    = 2.0     ...and only when IOB is under this
    compressionDropMgdl   = 30.0    ...and only if fallen > this from the recent baseline
    compressionWindow     = 5       baseline = max raw over the last 5 READINGS
The window is a COUNT, and its own comment says "(~25 min)" — true at 5-min cadence, but at
1-min it is 5 minutes. Same class of bug as the meal state machine's cycle-counting.
IOB is not in boost_cgm, so only the GLUCOSE SHAPE half of the gate is evaluated; the IOB
condition would further reduce both arms roughly equally.

PART B — forward-low prediction. Does adding 1-min-derived features to what the 5-min
engine already sees improve prediction of "BG < 70 within 30 min"? GroupKFold BY DAY
(the project's leakage rule, user-level being unavailable with one user), out-of-sample AUC.

PROVISIONAL: one user's glucose.
"""
import sys, numpy as np, psycopg2, datetime as dt
sys.path.insert(0, '.')
from aaps_cadence_lib import block_bootstrap_ci, verdict
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold
from sklearn.metrics import roc_auc_score

DSN = "dbname=oref host=127.0.0.1 port=5432"
with psycopg2.connect(DSN) as c, c.cursor() as cur:
    cur.execute("select extract(epoch from ts_utc)*1000, cgm_mgdl from boost_cgm "
                "where user_id='I' and cgm_mgdl is not null and ts_utc>='2026-05-24' order by ts_utc")
    r = cur.fetchall()
ts = np.array([int(x[0]) for x in r], np.int64)
bg = np.array([float(x[1]) for x in r], float)
n = len(ts)
day = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
print(f"user I: {n:,} readings, {len(set(day))} days\n")

# ---------------- PART A
CEIL, DROP, WIN = 75.0, 30.0, 5
def gate_fires(values):
    """Shipped glucose-shape test over a series: max of the last WIN READINGS."""
    fires = np.zeros(len(values), bool)
    for i in range(WIN, len(values)):
        base = values[i-WIN:i].max()
        fires[i] = values[i] < CEIL and (base - values[i]) > DROP
    return fires

f1 = gate_fires(bg)                          # as the 1-min user experiences it
dec = bg[::5]                                # the same glucose seen 5-minutely
f5 = gate_fires(dec)
days1 = len(set(day)); span_d = (ts[-1]-ts[0])/86_400_000
print("PART A — UKF compression gate (glucose-shape half), same glucose, two cadences")
print(f"  baseline window = last {WIN} readings = {WIN} min at 1-min, {WIN*5} min at 5-min")
print(f"  fires at 1-min cadence : {f1.sum():5d}  ({f1.sum()/span_d:.2f}/day)")
print(f"  fires at 5-min cadence : {f5.sum():5d}  ({f5.sum()/span_d:.2f}/day)")
if f5.sum():
    print(f"  -> the 1-min user gets {100*(f1.sum()/max(f5.sum(),1)):.0f}% of the 5-min detection rate")
# what a TIME-based window would give at 1-min
def gate_time(values, tss, minutes):
    fires = np.zeros(len(values), bool)
    lo = np.searchsorted(tss, tss - minutes*60_000, side="left")
    for i in range(1, len(values)):
        l = int(lo[i])
        if i-l < 2: continue
        base = values[l:i].max()
        fires[i] = values[i] < CEIL and (base - values[i]) > DROP
    return fires
f1t = gate_time(bg, ts, 25)
print(f"  fires at 1-min with a 25-MINUTE window: {f1t.sum():5d}  ({f1t.sum()/span_d:.2f}/day)  <- the intended behaviour\n")

# ---------------- PART B
H = 30
low = np.zeros(n, bool)
hz = np.searchsorted(ts, ts + H*60_000)
for i in range(n):
    j = min(hz[i], n)
    if j > i+1: low[i] = bg[i+1:j].min() < 70.0
def slope(minutes):
    out = np.full(n, np.nan)
    lo = np.searchsorted(ts, ts - minutes*60_000, side="left")
    for i in range(60, n):
        l = int(lo[i])
        if i-l < 1: continue
        x = (ts[l:i+1]-ts[l])/60_000.0; y = bg[l:i+1]
        if x[-1]-x[0] < minutes*0.6: continue
        sxx = float(((x-x.mean())**2).sum())
        if sxx > 0: out[i] = float(((x-x.mean())*(y-y.mean())).sum()/sxx*5.0)
    return out
s5, s15, s40 = slope(5), slope(15), slope(40)
s3 = slope(3)
a3 = np.full(n, np.nan)
b3 = np.searchsorted(ts, ts - 3*60_000, side="left")
okb = (b3 > 0) & (b3 < n); a3[okb] = s3[okb] - s3[b3[okb]]

FIVE = np.column_stack([bg, s5, s15, s40])                 # what the 5-min engine has
ONE  = np.column_stack([bg, s5, s15, s40, s3, a3])         # + genuinely 1-min-only features
m = np.isfinite(FIVE).all(1) & np.isfinite(ONE).all(1) & (np.arange(n) > 60)
X5, X1, y, g = FIVE[m], ONE[m], low[m].astype(int), day[m]
print(f"PART B — forward low (BG < 70 within {H} min). n={m.sum():,}, base rate {100*y.mean():.1f}%")
gk = GroupKFold(n_splits=5)
def cv_auc(X):
    p = np.zeros(len(y))
    for tr, te in gk.split(X, y, groups=g):
        sc = LogisticRegression(max_iter=2000, class_weight="balanced")
        mu, sd = X[tr].mean(0), X[tr].std(0)+1e-9
        sc.fit((X[tr]-mu)/sd, y[tr])
        p[te] = sc.predict_proba((X[te]-mu)/sd)[:, 1]
    return p
p5, p1 = cv_auc(X5), cv_auc(X1)
days_u = sorted(set(g))
blocks = [np.column_stack([p5[g==d], p1[g==d], y[g==d]]) for d in days_u]
blocks = [b for b in blocks if len(b) > 20 and 0 < b[:,2].sum() < len(b)]
def stat(bs, col):
    a = np.concatenate([b[:,col] for b in bs]); yy = np.concatenate([b[:,2] for b in bs]).astype(int)
    return roc_auc_score(yy, a) if 0 < yy.sum() < len(yy) else np.nan
for name, col in (("5-min features only", 0), ("+ 1-min features", 1)):
    pt, lo_, hi_ = block_bootstrap_ci(blocks, lambda bs, c=col: stat(bs, c))
    print(f"  {name:22s} OOS AUC {pt:.3f}  [{lo_:.3f}, {hi_:.3f}]")
pt, lo_, hi_ = block_bootstrap_ci(blocks, lambda bs: stat(bs,1)-stat(bs,0))
print(f"  {'DIFFERENCE':22s}         {pt:+.3f}  [{lo_:+.3f}, {hi_:+.3f}]  {verdict(lo_, hi_)}")
print("\nPROVISIONAL — one user, GroupKFold by day, detection only.")
