#!/usr/bin/env python3
"""Sensor realism layer: a post-hoc transform on a clean 5-min CGM array.

The UVA/Padova (simglucose) Dexcom sensor model under-represents two things real CGM
sensors do: (a) high-frequency measurement noise (real jitter is roughly 2x the model's),
and (b) compression lows, a sharp reversing sensor artefact from lying on the sensor
site, which the model has no mechanism for at all (structural zero).

Both are applied here, post-hoc, on top of whichever glucose trace is handed in (2008
baseline or the PoC-realistic cohort). This keeps the sensor artefact model separable
from the physiology layer (gen_sim_realistic.py), so its two free parameters
(noise_sigma, compression rate) can be tuned independently and cheaply, without
re-running the ODE simulation.

Note: this only injects FALSE lows (sensor reads low, interstitial glucose does not
change) -- it does not touch the true glucose value used elsewhere; it is meant to be
applied to a trace that is then treated as "the CGM the algorithm/analysis sees".
"""
import numpy as np

# Shape of a compression dip across 7 consecutive 5-min samples (35 min), as a fraction
# of peak depth per sample: fast onset, brief nadir, fast-ish recovery. Matches the
# _compression_rate detector in multicohort.py, which looks at a 6-sample-wide window
# (30 min) from onset and requires: pre-dip baseline >= 85, drop > 25 mg/dL below that
# baseline, and recovery to within 15 mg/dL of baseline by the end of the window.
DIP_PROFILE = np.array([0.0, 0.55, 1.00, 0.85, 0.55, 0.25, 0.0])


def add_sensor_noise(cgm, rng, sigma=2.0):
    """IID measurement noise. Note: the 2nd-difference SD of iid noise with std sigma
    is sigma*sqrt(6) (2nd-diff operator [1,-2,1], sum of squares = 6), so a sigma of
    ~2 mg/dL contributes ~5 mg/dL of 2nd-diff jitter on its own; the underlying signal
    contributes some more, so the achieved total must be verified empirically, not
    assumed from this formula alone."""
    return cgm + rng.normal(0.0, sigma, size=len(cgm))


def inject_compression_lows(cgm, rng, rate_per_30d=3.0, min_baseline=85.0,
                            max_baseline=140.0, nadir_lo=42.0, nadir_hi=62.0,
                            min_gap_samples=10):
    """Inject synthetic compression-low events into a clean 5-min CGM array.

    Each event is a brief, sharp downward excursion that does not reflect a real
    glucose change: a fast dip below a pre-event baseline, briefly, then a recovery
    back towards that baseline within the window the detector inspects. The
    `_compression_rate` detector in multicohort.py only counts an event where the
    trace actually crosses below 70 mg/dL (it reuses the hypo onset detector), so
    candidates are only accepted when the local baseline sits in a moderate band
    (min_baseline..max_baseline) that a plausible dip can drive under 70 mg/dL without
    an implausibly large excursion, and the target nadir (nadir_lo..nadir_hi) is
    picked directly rather than a fixed depth, so the dip always crosses the
    detector's threshold.

    Returns (new_cgm, n_events_placed).
    """
    out = cgm.copy()
    span_days = (len(cgm) * 300.0) / 86400.0  # 5-min grid
    n_target = int(round(rate_per_30d * span_days / 30.0))
    placed = []
    tries = 0
    max_tries = max(400, n_target * 400)
    while len(placed) < n_target and tries < max_tries:
        tries += 1
        i0 = int(rng.integers(6, len(out) - 8))
        if any(abs(i0 - e) < min_gap_samples for e in placed):
            continue
        pre = out[max(0, i0 - 4):i0].mean()
        if pre < min_baseline or pre > max_baseline:
            continue
        nadir_target = float(rng.uniform(nadir_lo, nadir_hi))
        depth = pre - nadir_target
        if depth <= 26.0:
            continue
        out[i0:i0 + 7] -= depth * DIP_PROFILE
        placed.append(i0)
    return out, len(placed)


def apply(t, cgm, rng, noise_sigma=2.0, compression_rate=3.0):
    """Full sensor-realism layer: compression lows first (a sensor-site artefact on
    the true trace), then measurement noise on top (present at every sample,
    including the injected dips). Returns (realistic_cgm, n_compression_events)."""
    dipped, n_events = inject_compression_lows(cgm, rng, rate_per_30d=compression_rate)
    noisy = add_sensor_noise(dipped, rng, sigma=noise_sigma)
    return noisy, n_events
