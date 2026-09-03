#!/usr/bin/env python3
"""Pull user I's full CGM history and compute AIMI-era vs Boost-era outcomes.

Context. User I ran the AIMI fork until 2026-07-28 06:42Z, then migrated to Boost
(3.4.2.1, V2/V1 engines), then to Boost V6 on 3.4.2.2 + UKF at 2026-07-30 09:27Z.
Their Nightscout retains devicestatus only from ~2026-07-21, so the AIMI era has NO
parseable decision records — but CGM (entries) goes back further, so glycaemic
OUTCOMES for the AIMI period are recoverable even though the algorithm's own
reasoning is not.

Site base+token are read at RUNTIME from the private registry; never hardcoded.
Sends a browser User-Agent: some cohort sites sit behind a Cloudflare rule that
403s default Python agents (CF error 1010).

Outcomes are computed per calendar month and per era. Uncertainty on the era
difference is a day-level block bootstrap (days are the resampling unit; 1-min CGM
is heavily autocorrelated and a per-point CI would be far too narrow).
"""
import json, os, sys, time, urllib.request, urllib.parse, datetime as dt
import numpy as np

REG = os.path.expanduser("~/.config/boost_backtest/sites.json")
HERE = os.path.dirname(os.path.abspath(__file__))
TAG = "I"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
AIMI_END = dt.datetime(2026, 7, 28, 6, 42, tzinfo=dt.UTC)     # last AIMI devicestatus
RNG = np.random.default_rng(20260730)
NBOOT = 2000


def site():
    s = {x["tag"]: x for x in json.load(open(REG))["sites"]}[TAG]
    return s["base"].rstrip("/"), s["token"]


def fetch(base, token, lo_ms, hi_ms, count=20000, tries=4):
    q = urllib.parse.urlencode({
        "token": token, "count": count, "find[type]": "sgv",
        "find[date][$gte]": lo_ms, "find[date][$lt]": hi_ms,
    })
    for a in range(tries):
        try:
            req = urllib.request.Request(f"{base}/api/v1/entries.json?{q}",
                                         headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=240) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            if a == tries - 1:
                print(f"    FAILED {type(e).__name__}: {e}")
                return []
            time.sleep(2 ** a * 3)
    return []


def main():
    base, token = site()
    # 7-day chunks back from now to the start of March (retention probe showed data
    # exists before 2026-03-01 but not before 2026-01-01).
    end = dt.datetime.now(dt.UTC)
    start = dt.datetime(2026, 3, 1, tzinfo=dt.UTC)
    got = {}
    cur = end
    while cur > start:
        prev = max(cur - dt.timedelta(days=7), start)
        page = fetch(base, token, int(prev.timestamp() * 1000), int(cur.timestamp() * 1000))
        for x in page:
            if x.get("date") and x.get("sgv"):
                got[x["date"]] = float(x["sgv"])
        print(f"  {prev:%Y-%m-%d} .. {cur:%Y-%m-%d}: +{len(page):6d}  total {len(got):7d}")
        cur = prev
    if not got:
        print("no data"); return

    ts = np.array(sorted(got))
    bg = np.array([got[t] for t in ts])
    print(f"\ntotal CGM points: {len(bg):,}   "
          f"{dt.datetime.fromtimestamp(ts.min()/1000, dt.UTC):%Y-%m-%d} .. "
          f"{dt.datetime.fromtimestamp(ts.max()/1000, dt.UTC):%Y-%m-%d}")
    np.savez_compressed(os.path.join(HERE, "userI_cgm.npz"), ts=ts, bg=bg)

    def metrics(v):
        return dict(n=len(v), tir=100*np.mean((v >= 70) & (v <= 180)),
                    ting=100*np.mean((v >= 63) & (v <= 140)),
                    tbr70=100*np.mean(v < 70), tbr54=100*np.mean(v < 54),
                    tar180=100*np.mean(v > 180), tar250=100*np.mean(v > 250),
                    mean=float(np.mean(v)), cv=100*np.std(v)/np.mean(v))

    def show(label, m):
        print(f"{label:22s} n={m['n']:7d}  TIR {m['tir']:5.1f}  TING {m['ting']:5.1f}  "
              f"<70 {m['tbr70']:5.2f}  <54 {m['tbr54']:5.2f}  >180 {m['tar180']:5.1f}  "
              f">250 {m['tar250']:5.1f}  mean {m['mean']:5.0f}  CV {m['cv']:4.1f}")

    print("\nPER CALENDAR MONTH (all AIMI except the last three days of July)")
    hdr = f"{'period':22s} {'n':>9s}  {'TIR':>5s}  {'TING':>5s}  {'<70':>5s}  {'<54':>5s}  {'>180':>5s}  {'>250':>5s}  {'mean':>5s}  {'CV':>4s}"
    print(hdr)
    days = np.array([dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts])
    months = sorted({(d.year, d.month) for d in days})
    for y, mo in months:
        m = np.array([(d.year, d.month) == (y, mo) for d in days])
        if m.sum() > 500:
            show(f"{y}-{mo:02d}", metrics(bg[m]))

    aimi_ms = AIMI_END.timestamp() * 1000
    a, b = ts < aimi_ms, ts >= aimi_ms
    print()
    show("AIMI (to 07-28)", metrics(bg[a]))
    show("Boost (07-28 on)", metrics(bg[b]))

    # last-3-months AIMI window, and a like-for-like recency slice
    lo = (AIMI_END - dt.timedelta(days=90)).timestamp() * 1000
    a90 = a & (ts >= lo)
    print()
    show("AIMI last 90d", metrics(bg[a90]))
    n_boost_days = len({dt.datetime.fromtimestamp(t/1000, dt.UTC).date() for t in ts[b]})
    lo2 = (AIMI_END - dt.timedelta(days=n_boost_days)).timestamp() * 1000
    show(f"AIMI last {n_boost_days}d", metrics(bg[a & (ts >= lo2)]))

    # day-level block bootstrap on the era difference (matched recency)
    def by_day(mask):
        out = {}
        for t, v in zip(ts[mask], bg[mask]):
            out.setdefault(dt.datetime.fromtimestamp(t/1000, dt.UTC).date(), []).append(v)
        return [np.array(v) for v in out.values()]

    A, B = by_day(a & (ts >= lo2)), by_day(b)
    if len(A) >= 3 and len(B) >= 3:
        print(f"\nBoost minus AIMI (matched {n_boost_days}d recency), day-level bootstrap "
              f"{NBOOT} draws, {len(B)} vs {len(A)} days:")
        keys = ["tir", "ting", "tbr70", "tbr54", "tar180", "mean", "cv"]
        pt = {k: metrics(np.concatenate(B))[k] - metrics(np.concatenate(A))[k] for k in keys}
        draws = {k: [] for k in keys}
        for _ in range(NBOOT):
            sa = np.concatenate([A[i] for i in RNG.integers(0, len(A), len(A))])
            sb = np.concatenate([B[i] for i in RNG.integers(0, len(B), len(B))])
            ma, mb = metrics(sa), metrics(sb)
            for k in keys:
                draws[k].append(mb[k] - ma[k])
        for k in keys:
            lo_, hi_ = np.percentile(draws[k], [2.5, 97.5])
            verdict = "distinguishable" if (lo_ > 0 or hi_ < 0) else "UNPROVEN"
            print(f"  {k:7s} {pt[k]:+7.2f}  [{lo_:+7.2f}, {hi_:+7.2f}]  {verdict}")
        print("\n  NB tiny Boost n (days) — treat as descriptive, not an algorithm verdict.")


if __name__ == "__main__":
    main()
