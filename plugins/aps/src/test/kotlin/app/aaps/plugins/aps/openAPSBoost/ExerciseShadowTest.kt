package app.aaps.plugins.aps.openAPSBoost

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Konzept 8 (2026-08-26) — pure-function tests for ExerciseShadow. Each boundary/mapping
 * hand-verified before writing the assertion, per the same discipline as AlcoholShadowTest.
 * Renamed from CyclingShadowTest once scope broadened to all exercise types.
 */
class ExerciseShadowTest {

    private fun session(startMs: Long, endMs: Long, type: Int = ExerciseSessionRecord.EXERCISE_TYPE_BIKING) =
        ExerciseShadow.Session(startMs = startMs, endMs = endMs, exerciseType = type, source = "test")

    // ─── isCurrentlyRelevant ────────────────────────────────────────────────

    @Test fun `a still-ongoing session (endMs in the future) is always relevant`() {
        val s = session(startMs = 1_000_000L, endMs = 2_000_000L)
        assertThat(ExerciseShadow.isCurrentlyRelevant(s, nowMs = 1_500_000L)).isTrue()
    }

    @Test fun `a session that just ended is relevant`() {
        val s = session(startMs = 0L, endMs = 1_000_000L)
        assertThat(ExerciseShadow.isCurrentlyRelevant(s, nowMs = 1_000_000L)).isTrue()
    }

    @Test fun `a session is still relevant exactly at the 90-minute boundary`() {
        val endMs = 1_000_000L
        val boundaryNow = endMs + ExerciseShadow.RELEVANT_AFTER_END_MIN * 60_000L
        val s = session(startMs = 0L, endMs = endMs)
        assertThat(ExerciseShadow.isCurrentlyRelevant(s, nowMs = boundaryNow)).isTrue()
    }

    @Test fun `a session becomes irrelevant one ms past the 90-minute boundary`() {
        val endMs = 1_000_000L
        val justPast = endMs + ExerciseShadow.RELEVANT_AFTER_END_MIN * 60_000L + 1
        val s = session(startMs = 0L, endMs = endMs)
        assertThat(ExerciseShadow.isCurrentlyRelevant(s, nowMs = justPast)).isFalse()
    }

    @Test fun `a session from yesterday is not relevant`() {
        val endMs = 1_000_000L
        val muchLater = endMs + 24L * 60 * 60_000L
        val s = session(startMs = 0L, endMs = endMs)
        assertThat(ExerciseShadow.isCurrentlyRelevant(s, nowMs = muchLater)).isFalse()
    }

    // ─── severityTier ───────────────────────────────────────────────────────

    @Test fun `sustained cardio types map to VIGOROUS_AEROBIC`() {
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_BIKING)).isEqualTo("VIGOROUS_AEROBIC")
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY)).isEqualTo("VIGOROUS_AEROBIC")
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)).isEqualTo("VIGOROUS_AEROBIC")
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL)).isEqualTo("VIGOROUS_AEROBIC")
        // Hiking (2026-08-26, user correction): sustained elevation exertion, not "just walking".
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_HIKING)).isEqualTo("VIGOROUS_AEROBIC")
    }

    @Test fun `strength types map to RESISTANCE`() {
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING)).isEqualTo("RESISTANCE")
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING)).isEqualTo("RESISTANCE")
    }

    @Test fun `racquet sports map to MODERATE_AEROBIC`() {
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON)).isEqualTo("MODERATE_AEROBIC")
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_TENNIS)).isEqualTo("MODERATE_AEROBIC")
    }

    @Test fun `low-intensity types are deliberately unmapped`() {
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_WALKING)).isNull()
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_YOGA)).isNull()
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING)).isNull()
    }

    @Test fun `an unrecognised type is unmapped, not an error`() {
        assertThat(ExerciseShadow.severityTier(ExerciseSessionRecord.EXERCISE_TYPE_GOLF)).isNull()
    }
}
