"""
Faithful Python port of the AAPS/Boost glucose front-end, for the 1-minute-CGM
cadence backtest.

Ported verbatim (line-for-line) from, on branch Boost-V7-shadow:

  plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/data/
      AutosensDataStoreObject.kt
        - IRREGULAR_DATA_SEC, referenceTime, adjustToReferenceTime,
          isAbout5minData, createBucketedData, createBucketedData5min,
          createBucketedDataRecalculated
  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPS/DeltaCalculator.kt
        - calculateDeltas + the min/max window constants
  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/
      OpenAPSBoostV5Plugin.kt
        - deltaAccl, deltaHistory, cumulativeRise30min (buildInputs)
  plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSBoostV5/MealHypothesis.kt
        - the cycle-counted state-machine constants

The port is validated against the SHIPPED Kotlin by `02_bucketer_parity.py`,
which drives `plugins/main/src/test/.../UkfBucketingParityTest.kt` over the same
corpus and diffs the two bucketed grids.

No dosing code is modified anywhere; this file is analysis-only.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Optional, Sequence

MIN5_MS = 5 * 60 * 1000
MIN2_5_MS = 2 * 60 * 1000 + 30 * 1000
IRREGULAR_DATA_SEC = 30
IRREGULAR_DATA_MS = IRREGULAR_DATA_SEC * 1000

# DeltaCalculator.kt companion object
MIN_BG_VALUE = 39.0
MIN_SHORT_DELTA_MIN = 2.5
MAX_SHORT_DELTA_MIN = 17.5
MIN_LAST_DELTA_MIN = 2.5
MAX_LAST_DELTA_MIN = 7.5
MIN_LONG_DELTA_MIN = 17.5
MAX_LONG_DELTA_MIN = 42.5

# MealHypothesis.kt — the cycle-counted constants under test
CONFIRM_MIN_OBSERVING_AGE = 2
CONFIRM_MIN_OBSERVING_AGE_SCORE_READY = CONFIRM_MIN_OBSERVING_AGE - 1
CONFIRM_MIN_OBSERVING_AGE_SCORE_READY_AGGRESSIVE = CONFIRM_MIN_OBSERVING_AGE - 2
FALL_BACK_TO_IDLE_AGE = 2
CONFIRMED_TO_COMMITTED_AGE = 0
RECOVERING_REENGAGE_MIN_AGE = 1
ML_MEAL_RENORMALIZE_AFTER_CYCLES = 3

# DetermineBasalBoostV5.kt — primer gate (2026-07-30 sizing rework)
PRIMER_ACCEL_THRESHOLD = 10.0
PRIMER_DELTA_MIN = 3.0
PRIMER_MIN_RECENT_LOW_MGDL = 80.0


@dataclass
class GV:
    """core.data.model.GV — a raw CGM reading."""
    timestamp: int          # epoch ms
    value: float            # mg/dL


@dataclass
class IMGV:
    """core.data.iob.InMemoryGlucoseValue — a bucket."""
    timestamp: int
    value: float
    filled_gap: bool = False

    @property
    def recalculated(self) -> float:
        # InMemoryGlucoseValue.recalculated == smoothed ?: value.  This harness
        # runs with NoSmoothing (identity), so recalculated == value.  Any
        # smoother acts on the SAME 5-min grid in both arms, so it cannot
        # change the cadence conclusions — see REPORT.md §Limitations.
        return self.value


@dataclass
class DeltaResult:
    delta: float
    short_avg_delta: float
    long_avg_delta: float


def _average(xs: Sequence[float]) -> float:
    if not xs:
        return 0.0
    return sum(xs) / len(xs)


# ---------------------------------------------------------------------------
# AutosensDataStoreObject
# ---------------------------------------------------------------------------

class AutosensDataStore:
    """Port of AutosensDataStoreObject's bucketing. `bg_readings` is NEWEST FIRST."""

    def __init__(self, reference_time: int = -1):
        self.reference_time = reference_time
        self.bg_readings: List[GV] = []
        self.bucketed_data: Optional[List[IMGV]] = None
        self.last_used_5min: Optional[bool] = None

    # AutosensDataStoreObject.kt:157
    def adjust_to_reference_time(self, some_time: int) -> int:
        if self.reference_time == -1:
            self.reference_time = some_time
            return some_time
        diff = abs(some_time - self.reference_time)
        diff %= MIN5_MS
        if diff > MIN2_5_MS:
            return some_time + abs(diff - MIN5_MS)   # adjust to the future
        return some_time - diff                       # adjust to the past

    # AutosensDataStoreObject.kt:168
    def is_about_5min_data(self) -> bool:
        if len(self.bg_readings) < 3:
            return True
        total_diff = 0
        for i in range(1, len(self.bg_readings)):
            bg_time = self.bg_readings[i].timestamp
            last_bg_time = self.bg_readings[i - 1].timestamp
            diff = last_bg_time - bg_time
            diff %= MIN5_MS
            # Kotlin `%` is remainder (sign of dividend); Python `%` is modulo.
            # Reproduce Kotlin's semantics explicitly.
            diff = math.fmod(last_bg_time - bg_time, MIN5_MS)
            if diff > MIN2_5_MS:
                diff -= MIN5_MS
            total_diff += diff
            if abs(diff) > IRREGULAR_DATA_MS:
                return False
        average_diff = int(total_diff / len(self.bg_readings)) // 1000
        return average_diff < 1

    # AutosensDataStoreObject.kt:193
    def create_bucketed_data(self) -> None:
        five_min = self.is_about_5min_data()
        self.last_used_5min = five_min
        if five_min:
            self._create_bucketed_data_5min()
        else:
            self._create_bucketed_data_recalculated()

    # AutosensDataStoreObject.kt:204
    def _find_newer(self, time: int) -> Optional[GV]:
        r = self.bg_readings
        last_found = r[0]
        if last_found.timestamp < time:
            return None
        for i in range(1, len(r)):
            if r[i].timestamp == time:
                return r[i]
            if r[i].timestamp > time:
                continue
            last_found = r[i - 1]
            if r[i].timestamp < time:
                break
        return last_found

    # AutosensDataStoreObject.kt:216
    def _find_older(self, time: int) -> Optional[GV]:
        r = self.bg_readings
        last_found = r[-1]
        if last_found.timestamp > time:
            return None
        for i in range(len(r) - 2, -1, -1):
            if r[i].timestamp == time:
                return r[i]
            if r[i].timestamp < time:
                continue
            last_found = r[i + 1]
            if r[i].timestamp > time:
                break
        return last_found

    # AutosensDataStoreObject.kt:228
    def _create_bucketed_data_recalculated(self) -> None:
        if len(self.bg_readings) < 3:
            self.bucketed_data = None
            return
        new_bucketed: List[IMGV] = []
        current_time = self.bg_readings[0].timestamp
        adjusted_time = self.adjust_to_reference_time(current_time)
        if adjusted_time > current_time:
            current_time = adjusted_time - MIN5_MS
        else:
            current_time = adjusted_time
        while True:
            newer = self._find_newer(current_time)
            older = self._find_older(current_time)
            if newer is None or older is None:
                break
            if older.timestamp == newer.timestamp:
                new_bucketed.append(IMGV(newer.timestamp, newer.value))
            else:
                bg_delta = newer.value - older.value
                time_diff_to_new = newer.timestamp - current_time
                time_diff_to_older = current_time - older.timestamp
                filled_gap = min(time_diff_to_older, time_diff_to_new) > IRREGULAR_DATA_MS
                current_bg = newer.value - time_diff_to_new / (newer.timestamp - older.timestamp) * bg_delta
                new_bucketed.append(IMGV(current_time, float(_kround(current_bg)), filled_gap))
            current_time -= MIN5_MS
        self.bucketed_data = new_bucketed

    # AutosensDataStoreObject.kt:261
    def _create_bucketed_data_5min(self) -> None:
        if len(self.bg_readings) < 3:
            self.bucketed_data = None
            return
        r = self.bg_readings
        b_data: List[IMGV] = [IMGV(r[0].timestamp, r[0].value)]
        j = 0
        for i in range(1, len(r)):
            bg_time = r[i].timestamp
            last_bg_time = r[i - 1].timestamp
            elapsed_minutes = int((bg_time - last_bg_time) / 60000)   # Kotlin Long division truncates
            if abs(elapsed_minutes) > 8:
                last_bg_value = r[i - 1].value
                elapsed_minutes = abs(elapsed_minutes)
                while elapsed_minutes > 5:
                    next_bg_time = last_bg_time - 5 * 60 * 1000
                    j += 1
                    gap_delta = r[i].value - last_bg_value
                    next_bg = last_bg_value + 5.0 / elapsed_minutes * gap_delta
                    b_data.append(IMGV(next_bg_time, float(_kround(next_bg)), True))
                    elapsed_minutes -= 5
                    last_bg_value = next_bg
                    last_bg_time = next_bg_time
                j += 1
                b_data.append(IMGV(bg_time, r[i].value))
            elif abs(elapsed_minutes) > 2:
                j += 1
                b_data.append(IMGV(bg_time, r[i].value))
            else:
                b_data[j].value = (b_data[j].value + r[i].value) / 2

        oldest = b_data[-1]
        oldest.timestamp = self.adjust_to_reference_time(oldest.timestamp)
        for i in range(len(b_data) - 2, -1, -1):
            current = b_data[i]
            previous = b_data[i + 1]
            m_sec_diff = current.timestamp - previous.timestamp
            adjusted = int((m_sec_diff - MIN5_MS) / 1000)
            if abs(adjusted) > 90:
                self._create_bucketed_data_recalculated()
                return
            current.timestamp = previous.timestamp + MIN5_MS
        self.bucketed_data = b_data


def _kround(x: float) -> int:
    """Kotlin Double.roundToLong(): half-up, ties away from -inf (floor(x+0.5))."""
    return math.floor(x + 0.5)


# ---------------------------------------------------------------------------
# DeltaCalculator
# ---------------------------------------------------------------------------

def calculate_deltas(data: Sequence[IMGV]) -> DeltaResult:
    """DeltaCalculator.calculateDeltas — `data` newest first."""
    if len(data) < 2:
        return DeltaResult(0.0, 0.0, 0.0)
    last_deltas: List[float] = []
    short_deltas: List[float] = []
    long_deltas: List[float] = []
    now = data[0]
    now_date = now.timestamp
    for i in range(1, len(data)):
        if data[i].recalculated > MIN_BG_VALUE:
            then = data[i]
            minutes_ago = (now_date - then.timestamp) / 60000.0
            change = now.recalculated - then.recalculated
            avg_del = change / minutes_ago * 5 if minutes_ago != 0 else 0.0
            if MIN_LAST_DELTA_MIN <= minutes_ago <= MAX_LAST_DELTA_MIN:
                last_deltas.append(avg_del)
            if MIN_SHORT_DELTA_MIN <= minutes_ago <= MAX_SHORT_DELTA_MIN:
                short_deltas.append(avg_del)
            if MIN_LONG_DELTA_MIN <= minutes_ago <= MAX_LONG_DELTA_MIN:
                long_deltas.append(avg_del)
            elif minutes_ago > MAX_LONG_DELTA_MIN:
                break
    short_avg = _average(short_deltas)
    delta = short_avg if not last_deltas else _average(last_deltas)
    return DeltaResult(delta, short_avg, _average(long_deltas))


def as_rounded(d: DeltaResult) -> DeltaResult:
    """GlucoseStatusSMB.asRounded() — the engine sees 2-dp values."""
    return DeltaResult(round(d.delta, 2), round(d.short_avg_delta, 2), round(d.long_avg_delta, 2))


# ---------------------------------------------------------------------------
# Boost V5/V6 derived signals (OpenAPSBoostV5Plugin.buildInputs)
# ---------------------------------------------------------------------------

def delta_accl(delta: float, short_avg_delta: float) -> float:
    """`100 * (delta - shortAvgDelta) / max(|shortAvgDelta|, 2.0)` — V3's denominator floor."""
    return 100.0 * (delta - short_avg_delta) / max(abs(short_avg_delta), 2.0)


def cumulative_rise_30min(short_avg_delta: float) -> float:
    return max(0.0, short_avg_delta * 6.0)


def delta_declining(delta_history: Sequence[float], window_cycles: int = 2) -> bool:
    """MealHypothesis.deltaDeclining."""
    if len(delta_history) < window_cycles + 1:
        return False
    tail = list(delta_history)[-(window_cycles + 1):]
    return all(tail[i] < tail[i - 1] for i in range(1, len(tail)))


# ---------------------------------------------------------------------------
# Front-end simulation: what the engine sees at wall-clock `now`
# ---------------------------------------------------------------------------

@dataclass
class Seen:
    now_ms: int
    bucket_ts: int          # timestamp of bucketedData[0]
    glucose: float
    delta: float
    short_avg_delta: float
    long_avg_delta: float
    accl: float
    n_buckets: int
    used_5min_path: bool

    @property
    def staleness_min(self) -> float:
        return (self.now_ms - self.bucket_ts) / 60000.0


def front_end_at(readings_newest_first: Sequence[GV], reference_time: int) -> Optional[Seen]:
    """One loop invocation: bucket the window, then compute the glucose status.

    `readings_newest_first` must already be truncated to `now` (AAPS loads
    readings up to `now + 2 min`; the newest is bgReadings[0] and defines
    `now` for GlucoseStatus purposes).
    """
    if len(readings_newest_first) < 3:
        return None
    ads = AutosensDataStore(reference_time=reference_time)
    ads.bg_readings = list(readings_newest_first)
    ads.create_bucketed_data()
    b = ads.bucketed_data
    if not b:
        return None
    d = as_rounded(calculate_deltas(b))
    return Seen(
        now_ms=readings_newest_first[0].timestamp,
        bucket_ts=b[0].timestamp,
        glucose=round(b[0].recalculated, 0),
        delta=d.delta,
        short_avg_delta=d.short_avg_delta,
        long_avg_delta=d.long_avg_delta,
        accl=delta_accl(d.delta, d.short_avg_delta),
        n_buckets=len(b),
        used_5min_path=bool(ads.last_used_5min),
    )


# ---------------------------------------------------------------------------
# Block bootstrap (day- or episode-level; never per-point)
# ---------------------------------------------------------------------------

def block_bootstrap_ci(blocks, stat_fn, n_boot: int = 4000, seed: int = 20260730, alpha: float = 0.05):
    """Resample whole BLOCKS (a day, or a rise episode) with replacement.

    `blocks` is a list of arbitrary per-block payloads; `stat_fn(list_of_blocks)`
    returns the scalar statistic.  1-min CGM is heavily autocorrelated, so a
    per-point bootstrap would give intervals that are far too narrow.
    """
    import numpy as np
    rng = np.random.default_rng(seed)
    n = len(blocks)
    if n == 0:
        return (float("nan"), float("nan"), float("nan"))
    point = stat_fn(blocks)
    if n < 2:
        return (point, float("nan"), float("nan"))
    draws = []
    for _ in range(n_boot):
        idx = rng.integers(0, n, n)
        s = stat_fn([blocks[i] for i in idx])
        if s is not None and not (isinstance(s, float) and math.isnan(s)):
            draws.append(s)
    if not draws:
        return (point, float("nan"), float("nan"))
    lo = float(np.percentile(draws, 100 * alpha / 2))
    hi = float(np.percentile(draws, 100 * (1 - alpha / 2)))
    return (point, lo, hi)


def verdict(lo: float, hi: float, null_value: float = 0.0) -> str:
    """Explicit distinguishable/UNPROVEN call against a null."""
    if math.isnan(lo) or math.isnan(hi):
        return "UNPROVEN (no interval)"
    if lo > null_value or hi < null_value:
        return f"distinguishable from {null_value:g}"
    return f"UNPROVEN (95% CI overlaps {null_value:g})"


# ---------------------------------------------------------------------------
# Grid re-anchoring (the SHIPPED behaviour — see REPORT.md §Finding 0)
# ---------------------------------------------------------------------------
#
# `AutosensDataStoreObject.clone()` (AutosensDataStoreObject.kt:44-51) copies
# bgReadings / autosensDataTable / bucketedData but NOT `referenceTime`, and
# `IobCobOref1Worker` does `ads = iobCobCalculator.ads.clone()` ... then
# `iobCobCalculator.ads = ads` (IobCobOref1Worker.kt:83, 324) on every autosens
# run.  So referenceTime is reset to -1 every cycle, and the next
# createBucketedData call re-anchors the 5-min grid to the newest reading:
#
#     buckets at  now, now-5, now-10, ...   (now = newest reading)
#
# rather than to a fixed lattice.  Confirmed in the field: `suggested.bg` changes
# every 5.00 min for every 5-min user and every ~1.1 min for the 1-min user
# (05_live_build_check.py).

def grid_reanchored(ts_desc, bg_desc, n_buckets: int = 12):
    """Bucket grid for ONE cycle with referenceTime == -1 (re-anchored to now).

    `ts_desc`/`bg_desc` are the readings NEWEST FIRST.  Equivalent to running
    `AutosensDataStore(reference_time=-1).create_bucketed_data()` and taking the
    first `n_buckets`, for the recalculated (sub-5-min) path.
    """
    import numpy as np
    now = int(ts_desc[0])
    asc_t = ts_desc[::-1]
    asc_v = bg_desc[::-1]
    out = []
    for k in range(n_buckets):
        t = now - k * MIN5_MS
        if t < asc_t[0]:
            break
        j = int(np.searchsorted(asc_t, t, side="left"))
        if j < len(asc_t) and asc_t[j] == t:
            out.append(IMGV(t, float(asc_v[j])))
            continue
        hi = j
        lo = j - 1
        if hi >= len(asc_t) or lo < 0:
            break
        newer_t, newer_v = int(asc_t[hi]), float(asc_v[hi])
        older_t, older_v = int(asc_t[lo]), float(asc_v[lo])
        frac = (newer_t - t) / (newer_t - older_t)
        out.append(IMGV(t, float(_kround(newer_v - frac * (newer_v - older_v)))))
    return out


def deltas_vectorised(buckets):
    """DeltaCalculator in closed form for an EXACT 5-min bucket grid.

    On a grid whose buckets are exactly 5 min apart, minutesAgo for bucket k is
    5k, so the window membership is fixed:
        lastDeltas  (2.5..7.5)   -> k = 1
        shortDeltas (2.5..17.5)  -> k = 1,2,3
        longDeltas  (17.5..42.5) -> k = 4..8
    and avgDel(k) = (b0 - bk) / (5k) * 5 = (b0 - bk) / k.

    `buckets` is an (n_cycles, 9) array, column k = bucket k (newest = col 0).
    Returns (delta, shortAvgDelta, longAvgDelta), each rounded to 2 dp as
    GlucoseStatusSMB.asRounded() does.
    """
    import numpy as np
    b = np.asarray(buckets, dtype=float)
    k = np.arange(1, 9, dtype=float)
    avg = (b[:, :1] - b[:, 1:9]) / k
    delta = avg[:, 0]
    short_avg = avg[:, 0:3].mean(axis=1)
    long_avg = avg[:, 3:8].mean(axis=1)
    return np.round(delta, 2), np.round(short_avg, 2), np.round(long_avg, 2)
