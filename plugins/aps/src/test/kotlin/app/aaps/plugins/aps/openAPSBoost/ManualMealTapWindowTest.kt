package app.aaps.plugins.aps.openAPSBoost

import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.MANUAL_MEAL_WINDOW_MIN
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.manualTapActive
import app.aaps.plugins.aps.openAPSBoost.OpenAPSBoostPlugin.Companion.manualTapAgeMin
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Konzept 6 (2026-08-24) — manual MEAL tap window. Pure-function tests for the arithmetic that
 * decides how long a tap keeps the pre-meal shadow logic active (forward from the tap, NOT the
 * learned path's backward-from-predicted-centre window — see the KDoc on MANUAL_MEAL_WINDOW_MIN).
 */
class ManualMealTapWindowTest {

    private val minuteMs = 60_000L

    @Test fun `never tapped (lastTapMs = 0) is never active`() {
        assertThat(manualTapAgeMin(nowMs = 10_000_000L, lastTapMs = 0L)).isEqualTo(Int.MAX_VALUE)
        assertThat(manualTapActive(nowMs = 10_000_000L, lastTapMs = 0L)).isFalse()
    }

    @Test fun `tap right now (age 0) is active`() {
        val now = 5_000_000L
        assertThat(manualTapAgeMin(now, lastTapMs = now)).isEqualTo(0)
        assertThat(manualTapActive(now, lastTapMs = now)).isTrue()
    }

    @Test fun `tap at exactly the window edge (45min) is still active`() {
        val tap = 1_000_000L
        val now = tap + MANUAL_MEAL_WINDOW_MIN * minuteMs
        assertThat(manualTapAgeMin(now, tap)).isEqualTo(45)
        assertThat(manualTapActive(now, tap)).isTrue()
    }

    @Test fun `tap one minute past the window (46min) is no longer active`() {
        val tap = 1_000_000L
        val now = tap + (MANUAL_MEAL_WINDOW_MIN + 1) * minuteMs
        assertThat(manualTapAgeMin(now, tap)).isEqualTo(46)
        assertThat(manualTapActive(now, tap)).isFalse()
    }

    @Test fun `tap 44 minutes ago is active, 45 is active, 46 is not - boundary sweep`() {
        val tap = 2_000_000L
        assertThat(manualTapActive(tap + 44 * minuteMs, tap)).isTrue()
        assertThat(manualTapActive(tap + 45 * minuteMs, tap)).isTrue()
        assertThat(manualTapActive(tap + 46 * minuteMs, tap)).isFalse()
    }

    @Test fun `a tap in the future (clock skew) is not active, not negative-age`() {
        // now < lastTapMs would give a negative minute count without the lower bound in the range
        // check — must not be treated as "just tapped".
        val now = 1_000_000L
        val futureTap = now + 5 * minuteMs
        assertThat(manualTapAgeMin(now, futureTap)).isEqualTo(-5)
        assertThat(manualTapActive(now, futureTap)).isFalse()
    }

    @Test fun `custom window parameter is respected`() {
        val tap = 3_000_000L
        assertThat(manualTapActive(tap + 10 * minuteMs, tap, windowMin = 5)).isFalse()
        assertThat(manualTapActive(tap + 3 * minuteMs, tap, windowMin = 5)).isTrue()
    }
}
