package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-06-19 intraday activity-load (shadow): today's cumulative steps vs typical pace by hour →
 * raise-only ISF nudge (acute exercise = more sensitive). Pure-function tests.
 */
class IntradayActivityFactorTest {

    private val baseline = 14000   // ~typical daily steps

    @Test fun `running well ahead of pace raises ISF`() {
        // 15:00 → diurnal fraction ~0.66 → expected ≈ 9240. 18000 today = ~1.95x → near the cap.
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 18000, baseline = baseline, hour = 15)
        assertThat(f.ratio!!).isGreaterThan(1.5)
        assertThat(f.wouldDeltaIsfPct).isGreaterThan(10.0)   // approaching the 15% cap
    }

    @Test fun `on-pace gives no factor`() {
        // 12:00 → fraction ~0.44 → expected ≈ 6160. 6160 today = 1.0x → 0%.
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 6160, baseline = baseline, hour = 12)
        assertThat(f.wouldDeltaIsfPct).isEqualTo(0.0)
    }

    @Test fun `below pace returns zero - raise-only, next-day factor owns the low side`() {
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 2000, baseline = baseline, hour = 15)
        assertThat(f.wouldDeltaIsfPct).isEqualTo(0.0)
    }

    @Test fun `capped at the activity max`() {
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 40000, baseline = baseline, hour = 16)
        assertThat(f.wouldDeltaIsfPct).isWithin(0.001).of(DailyStepHistoryTracker.ACTIVITY_MAX_ISF_PCT)
    }

    @Test fun `no baseline yet means no factor`() {
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 20000, baseline = null, hour = 14)
        assertThat(f.ratio).isNull()
        assertThat(f.wouldDeltaIsfPct).isEqualTo(0.0)
    }

    @Test fun `overnight tiny expected does not divide-by-zero`() {
        // hour 3 → fraction floored at 0.02 → expected ≈ 280; a 500-step night walk reads as ahead.
        // At this (fairly high, 14000/day) baseline, 280 expected steps is still above the new
        // MIN_EXPECTED_STEPS_FOR_INTRADAY_FACTOR guard, so behaviour here is unchanged by the fix.
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 500, baseline = baseline, hour = 3)
        assertThat(f.ratio).isNotNull()
        assertThat(f.wouldDeltaIsfPct).isAtLeast(0.0)
    }

    @Test fun `low-baseline user overnight - a bathroom trip no longer reads as exercise (2026-08-27 fix)`() {
        // Real incident: a user with a ~3862 step/day baseline showed the intraday ratio pinned
        // >=2.0 across ~300 of a day's ~300 cycles, including 00:01-00:06 — a stray ~100 steps at
        // hour 0-5 (floored fraction 0.02 -> expected ~77) trivially saturated the ratio. Fixed via
        // MIN_EXPECTED_STEPS_FOR_INTRADAY_FACTOR: below it, no factor at all (matches the existing
        // "no baseline yet" null-factor contract other callers already handle).
        val lowBaseline = 3862
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 100, baseline = lowBaseline, hour = 2)
        assertThat(f.ratio).isNull()
        assertThat(f.wouldDeltaIsfPct).isEqualTo(0.0)
        assertThat(f.expectedByNow).isLessThan(DailyStepHistoryTracker.MIN_EXPECTED_STEPS_FOR_INTRADAY_FACTOR)
    }

    @Test fun `low-baseline user still gets a real factor once expected steps clear the guard`() {
        // Same low baseline, but a later hour where the diurnal fraction alone (no floor needed)
        // already pushes expected steps above the guard - the fix must not suppress genuine signal.
        val lowBaseline = 3862
        // hour 15 -> fraction 0.66 -> expected ~2549, comfortably above the 150-step guard.
        val f = DailyStepHistoryTracker.intradayFactor(stepsToday = 5000, baseline = lowBaseline, hour = 15)
        assertThat(f.expectedByNow).isAtLeast(DailyStepHistoryTracker.MIN_EXPECTED_STEPS_FOR_INTRADAY_FACTOR)
        assertThat(f.ratio).isNotNull()
        assertThat(f.wouldDeltaIsfPct).isGreaterThan(0.0)
    }
}
