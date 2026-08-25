package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-08-26 — pure-function tests for PostExerciseRecoveryShadow. Each value hand-verified before
 * writing the assertion, per the same discipline as AlcoholShadowTest.
 */
class PostExerciseRecoveryShadowTest {

    // ─── durationScaledWindowMin ────────────────────────────────────────────

    @Test fun `a short session gets a proportionally short window, not the fixed max`() {
        // 15min ride, fixed formula would give 150min (VIGOROUS_AEROBIC default example) -> scaled: 15*2=30
        assertThat(PostExerciseRecoveryShadow.durationScaledWindowMin(exerciseDurationMin = 15, fixedWindowMin = 150)).isEqualTo(30)
    }

    @Test fun `a long session is capped at the fixed formula's own value, unaffected`() {
        // 90min ride * 2 = 180min, but fixed cap is 150min -> capped
        assertThat(PostExerciseRecoveryShadow.durationScaledWindowMin(exerciseDurationMin = 90, fixedWindowMin = 150)).isEqualTo(150)
    }

    @Test fun `a session exactly at the cap boundary is unaffected`() {
        // 75min * 2 = 150min, exactly equal to the cap
        assertThat(PostExerciseRecoveryShadow.durationScaledWindowMin(exerciseDurationMin = 75, fixedWindowMin = 150)).isEqualTo(150)
    }

    @Test fun `the 60min example from the conversation lands under the cap`() {
        // 60min * 2 = 120min, under the 150min VIGOROUS_AEROBIC cap
        assertThat(PostExerciseRecoveryShadow.durationScaledWindowMin(exerciseDurationMin = 60, fixedWindowMin = 150)).isEqualTo(120)
    }

    // ─── hyperBrakeActive / effectiveScale ──────────────────────────────────

    @Test fun `hyper-brake is inactive below 180`() {
        assertThat(PostExerciseRecoveryShadow.hyperBrakeActive(179.9)).isFalse()
    }

    @Test fun `hyper-brake activates at exactly 180`() {
        assertThat(PostExerciseRecoveryShadow.hyperBrakeActive(180.0)).isTrue()
    }

    @Test fun `hyper-brake stays inactive at the window's own elevated target (144, no false trigger)`() {
        assertThat(PostExerciseRecoveryShadow.hyperBrakeActive(144.0)).isFalse()
    }

    @Test fun `effective scale is the window's own scale when hyper-brake is inactive`() {
        assertThat(PostExerciseRecoveryShadow.effectiveScale(windowScale = 0.8, currentBg = 150.0)).isEqualTo(0.8)
    }

    @Test fun `effective scale is forced to 1_0 (no dampening) when hyper-brake is active`() {
        assertThat(PostExerciseRecoveryShadow.effectiveScale(windowScale = 0.8, currentBg = 195.0)).isEqualTo(1.0)
    }
}
