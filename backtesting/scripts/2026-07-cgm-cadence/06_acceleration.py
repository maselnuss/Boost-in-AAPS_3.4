#!/usr/bin/env python3
"""Point to point acceleration, measured at each era's native cadence.

Velocity is the first difference of consecutive readings and acceleration is the difference of
consecutive velocities, so over one sampling interval h

    a(t) = (x(t) - 2 x(t-h) + x(t-2h)) / h^2

This is the literal two point construction. It is reported here in mg/dL per 5 min per 5 min
so that the two cadences can be read against each other.

Three questions:

  A. How large is it at each cadence, and how much of it survives as usable structure?
  B. Is it consistent with the variogram? For any process the mean square of the second
     difference is exactly 4 D(h) - D(2h), so the variogram measured in script 02 predicts the
     acceleration magnitude with no free parameters. Agreement means the acceleration carries
     nothing the variogram did not already describe.
  C. Is it noise? The second difference of white noise has a lag one autocorrelation of exactly
     -2/3. A value near that is the signature of differencing noise rather than measuring
     curvature.

The overlapping window form used by the controller is computed alongside for comparison. That
form anchors both terms at the newest reading and averages rates over 2.5 to 7.5 minutes and
2.5 to 17.5 minutes back, so its windows overlap and it is a good deal smoother.
"""
import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cadence_lib as L

WHITE_NOISE_ACF1 = -2.0/3.0

def point_to_point_accel(ts, bg, nominal):
    """Second difference over one sampling interval, in mg/dL per 5 min per 5 min."""
    n = len(bg)
    a = np.full(n, np.nan)
    a[2:] = bg[2:] - 2*bg[1:-1] + bg[:-2]
    span1 = np.full(n, np.inf); span2 = np.full(n, np.inf)
    span1[1:] = (ts[1:] - ts[:-1])/60_000.0
    span2[2:] = (ts[2:] - ts[1:-1])/60_000.0
    bad = (np.abs(span1 - nominal) > 0.3*nominal) | (np.abs(span2 - nominal) > 0.3*nominal)
    a[bad] = np.nan
    return a * (5.0/nominal)**2

def controller_accel(ts, bg, nominal):
    """The overlapping window construction: 100 * (delta - shortAvgDelta) / max(|short|, 2)."""
    n = len(bg)
    def mean_rate(lo_min, hi_min):
        out = np.full(n, np.nan)
        klo = max(int(round(lo_min/nominal)), 1); khi = max(int(round(hi_min/nominal)), klo+1)
        acc = np.zeros(n); cnt = np.zeros(n)
        for k in range(klo, khi+1):
            if k >= n: break
            mins = (ts[k:] - ts[:-k])/60_000.0
            rate = np.full(n, np.nan)
            rate[k:] = (bg[k:] - bg[:-k])/mins*5.0
            rate[k:][np.abs(mins - k*nominal) > 0.3*k*nominal] = np.nan
            ok = np.isfinite(rate)
            acc[ok] += rate[ok]; cnt[ok] += 1
        out[cnt > 0] = acc[cnt > 0]/cnt[cnt > 0]
        return out
    delta = mean_rate(2.5, 7.5)
    short = mean_rate(2.5, 17.5)
    return 100.0*(delta - short)/np.maximum(np.abs(short), 2.0)

def acf1(x):
    v = x[np.isfinite(x)]
    v = v - v.mean()
    if len(v) < 100: return np.nan
    return float(np.mean(v[1:]*v[:-1])/np.var(v))

print("06. POINT TO POINT ACCELERATION\n")
E = L.load_eras()
V = L.read("02_variogram.json")
out = {}
for k, e in E.items():
    ts, bg, day, nom = e["ts"], e["bg"], e["day"], e["nominal"]
    a = point_to_point_accel(ts, bg, nom)
    c = controller_accel(ts, bg, nom)
    fin = np.isfinite(a)
    raw = (a[fin]/(5.0/nom)**2)                     # back to mg/dL over the interval
    sign_flip = float(np.mean(np.sign(a[fin][1:])*np.sign(a[fin][:-1]) < 0))
    o = dict(label=e["label"], nominal=nom, n=int(fin.sum()),
             sd_per5per5=float(np.nanstd(a)), sd_raw_mgdl=float(raw.std()),
             median_abs_raw=float(np.median(np.abs(raw))),
             pct_zero_raw=float(100*np.mean(raw == 0)),
             acf1=acf1(a), sign_flip_pct=100*sign_flip,
             controller_acf1=acf1(c), controller_sd=float(np.nanstd(c)))
    # variogram prediction: E[(second difference)^2] = 4 D(h) - D(2h)
    vg = V["vario"][k]
    h = int(round(nom)); h2 = int(round(2*nom))
    if str(h) in vg and str(h2) in vg:
        pred_ms = 4*vg[str(h)]["D"] - vg[str(h2)]["D"]
        o["variogram_pred_ms"] = float(pred_ms)
        o["variogram_pred_sd_raw"] = float(np.sqrt(max(pred_ms, 0)))
        o["measured_ms_raw"] = float(np.mean(raw**2))
        o["pred_over_measured"] = float(pred_ms/np.mean(raw**2))
    out[k] = o
    print(f"  {e['label']}  (sampling interval {nom:.0f} min, n={o['n']:,})")
    print(f"    acceleration over one interval : SD {o['sd_raw_mgdl']:.2f} mg/dL, "
          f"median |a| {o['median_abs_raw']:.2f}, {o['pct_zero_raw']:.1f}% exactly zero")
    print(f"    same in per-5-min-per-5-min    : SD {o['sd_per5per5']:.2f}")
    if "variogram_pred_sd_raw" in o:
        print(f"    predicted by the variogram     : SD {o['variogram_pred_sd_raw']:.2f} mg/dL "
              f"(4D({h})-D({h2})), predicted/measured mean square {o['pred_over_measured']:.3f}")
    print(f"    lag-1 autocorrelation          : {o['acf1']:+.3f}  "
          f"(white noise differenced twice gives {WHITE_NOISE_ACF1:+.3f})")
    print(f"    consecutive values change sign : {o['sign_flip_pct']:.1f}%")
    print(f"    controller form, lag-1 acf     : {o['controller_acf1']:+.3f}  "
          f"(SD {o['controller_sd']:.1f} per cent)\n")

print("  Interpretation of the lag-1 autocorrelation")
for k in ("e5", "e1"):
    o = out[k]
    frac_noise = min(max(o["acf1"]/WHITE_NOISE_ACF1, 0.0), 1.0)
    o["noise_like_fraction"] = float(frac_noise)
    print(f"    {o['label']:>14s}: {o['acf1']:+.3f} against {WHITE_NOISE_ACF1:+.3f} for pure noise, "
          f"so the series sits {100*frac_noise:.0f} per cent of the way to the noise value")
L.save("06_acceleration.json", out)

# ---------------------------------------------------------------- scale dependence
print("\n  D. IS ACCELERATION A CADENCE-INDEPENDENT QUANTITY?")
print("     For a process with variogram exponent alpha, the mean square of the second")
print("     difference goes as h^alpha, so acceleration, which divides by h^2, goes as")
print("     h^(alpha/2 - 2). Unless alpha = 4 the number depends on the interval you chose.")
meas_ratio = out["e1"]["sd_per5per5"]/out["e5"]["sd_per5per5"]
print(f"     measured SD ratio, 1-min over 5-min : {meas_ratio:.2f}")
sc = {}
for lab, alpha in (("this record, 5 to 20 min", V["slopes"]["e5"]["5-20"]["slope"]),
                   ("this record, 1 to 5 min", V["slopes"]["e1"]["1-5"]["slope"]),
                   ("white noise", 0.0)):
    pred = 5.0**(2.0 - alpha/2.0)
    sc[lab] = dict(alpha=float(alpha), predicted_ratio=float(pred))
    print(f"     predicted from alpha = {alpha:4.2f} ({lab:<24s}) : {pred:6.2f}")
sc["twice differentiable signal"] = dict(alpha=None, predicted_ratio=1.0)
print(f"     {'a twice differentiable signal':<44s} :   1.00")
out["scale_dependence"] = dict(measured_ratio=float(meas_ratio), by_alpha=sc)
print("     The last row is not the power law evaluated at alpha = 2. At exactly 2 the leading")
print("     term cancels and the second difference is governed by the next one, which goes as")
print("     h^4, so acceleration converges on a real value as the interval shrinks and the ratio")
print("     is 1.00. That is what a physically meaningful acceleration would look like. The")
print("     measured ratio is close to the value implied by this record's own roughness instead.")

# ---------------------------------------------------------------- predictive value
print("\n  E. DOES ACCELERATION ADD ANYTHING TO PREDICTION?")
from sklearn.metrics import roc_auc_score
TASKS = [("low <70", 70.0, "min", "above"), ("high >180", 180.0, "max", "below")]
H = 30
pv = {}
for k, e in E.items():
    ts, bg, day, nom = e["ts"], e["bg"], e["day"], e["nominal"]
    Xb, names = L.build_features(ts, bg, nom)
    acc = point_to_point_accel(ts, bg, nom)
    ctl = controller_accel(ts, bg, nom)
    sets = {"velocity only": Xb,
            "+ point to point acceleration": np.column_stack([Xb, acc]),
            "+ controller acceleration": np.column_stack([Xb, ctl]),
            "+ both": np.column_stack([Xb, acc, ctl])}
    pv[k] = {}
    print(f"\n     {e['label']}")
    for tname, thr, kind, elig in TASKS:
        ext = L.future_extreme(ts, bg, H, kind)
        lab = np.where(np.isfinite(ext), (ext < thr) if kind == "min" else (ext > thr), np.nan)
        el = (bg >= thr) if elig == "above" else (bg <= thr)
        pv[k][tname] = {}
        print(f"       {tname} within {H} min")
        for sname, X in sets.items():
            m = np.isfinite(X).all(1) & np.isfinite(lab) & el
            y = lab[m].astype(float); g = day[m]
            if len(y) < 500 or len(np.unique(y)) < 2:
                print(f"         {sname:<32s} too few"); continue
            p = L.cv_classify(X[m], y, g)
            ok = np.isfinite(p); idx = np.nonzero(ok)[0]
            fa = lambda s: float(roc_auc_score(y[s], p[s])) if len(np.unique(y[s])) > 1 else np.nan
            auc = fa(idx); lo, hi = L.day_bootstrap(fa, g[ok], 300)
            lft = L.lift_at_decile(y, p, idx)
            pv[k][tname][sname] = dict(auc=auc, lo=lo, hi=hi, lift=lft, n=int(len(y)))
            print(f"         {sname:<32s} AUC {L.ci_str(auc, lo, hi, 4)}  lift {lft:.2f}x")
        base = pv[k][tname].get("velocity only")
        for sname, r in pv[k][tname].items():
            if sname == "velocity only" or not base: continue
            r["gain"] = float(r["auc"] - base["auc"])
            r["overlaps_baseline"] = bool(L.overlaps((base["lo"], base["hi"]), (r["lo"], r["hi"])))
out["predictive_value"] = pv
print("\n")
L.save("06_acceleration.json", out)
