package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-08-26 — pure-function tests for BoostFloorSlewShadow. Each value hand-verified before
 * writing the assertion, per the same discipline as AlcoholShadowTest/PostExerciseRecoveryShadowTest.
 */
class BoostFloorSlewShadowTest {

    private fun sample(tMs: Long, ratio: Double) = BoostFloorSlewShadow.CycleSample(tMs, ratio)

    // ─── minimum sample gate ─────────────────────────────────────────────────

    @Test fun `returns null with one sample short of the minimum`() {
        val longWindow = List(BoostFloorSlewShadow.MIN_SAMPLES_FOR_ESTIMATE - 1) { sample(it.toLong(), 0.5) }
        assertThat(BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, 100.0)).isNull()
    }

    @Test fun `returns a suggestion at exactly the minimum sample count`() {
        val longWindow = List(BoostFloorSlewShadow.MIN_SAMPLES_FOR_ESTIMATE) { sample(it.toLong(), 0.5) }
        assertThat(BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, 100.0)).isNotNull()
    }

    // ─── floor: percentile + clamping ────────────────────────────────────────

    @Test fun `floor uses the worst-case (low) percentile, not the median`() {
        // 25 samples: 5 at 0.3 (worst compression, 20% of data — safely past the 10th-percentile
        // index boundary), 20 at 0.9 (normal). Sorted ascending, index (25*0.10)=2 falls inside the
        // five 0.3 values -> picks 0.3, not the far more common 0.9.
        val longWindow = List(5) { sample(it.toLong(), 0.3) } + List(20) { sample(100L + it, 0.9) }
        val result = BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, aggressivenessPct = 100.0)
        assertThat(result).isNotNull()
        // 0.3 * 100 * 1.0 = 30%, within [20,60] -> unclamped
        assertThat(result!!.floorPct).isEqualTo(30)
    }

    @Test fun `floor is clamped to the 60% ceiling even if the raw estimate is higher`() {
        val longWindow = List(25) { sample(it.toLong(), 0.9) }   // raw: 90%
        val result = BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.floorPct).isEqualTo(60)
    }

    @Test fun `floor is clamped to the 20% floor even if the raw estimate is lower`() {
        val longWindow = List(25) { sample(it.toLong(), 0.1) }   // raw: 10%
        val result = BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.floorPct).isEqualTo(20)
    }

    @Test fun `higher aggressiveness suggests a stricter (HIGHER) floor`() {
        // For the floor, stricter means a HIGHER %, not lower — a 70% floor blocks compression in
        // 100% of the replay's cases, a 30% floor only in 18% (§3.6). So aggressiveness must scale
        // the raw estimate UP, the opposite direction from slew below.
        val longWindow = List(25) { sample(it.toLong(), 0.3) }   // raw: 30%
        // aggressiveness 150 -> 30 * 1.5 = 45, within [20,60] so not clamped
        val result = BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, aggressivenessPct = 150.0)
        assertThat(result!!.floorPct).isEqualTo(45)
    }

    @Test fun `lower aggressiveness suggests a weaker (LOWER) floor`() {
        val longWindow = List(25) { sample(it.toLong(), 0.5) }   // raw: 50%
        // aggressiveness 50 -> 50 * 0.5 = 25, within [20,60] so not clamped
        val result = BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, aggressivenessPct = 50.0)
        assertThat(result!!.floorPct).isEqualTo(25)
    }

    // ─── slew: cycle-to-cycle delta + clamping ───────────────────────────────

    @Test fun `slew is the median cycle-to-cycle delta, clamped into range`() {
        val longWindow = List(20) { sample(it.toLong(), 0.5) }
        val shortWindow = listOf(
            sample(0L, 0.50),
            sample(300_000L, 0.52),   // delta 2.0
            sample(600_000L, 0.48)    // delta 4.0
        )
        // deltas [2.0, 4.0] sorted, median index = size/2 = 1 -> 4.0
        val result = BoostFloorSlewShadow.computeSuggestion(shortWindow, longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.slewPctPerCycle).isEqualTo(4.0)
    }

    @Test fun `slew is clamped to the 10 percent ceiling for a huge jump`() {
        val longWindow = List(20) { sample(it.toLong(), 0.5) }
        val shortWindow = listOf(sample(0L, 0.10), sample(300_000L, 0.90))   // delta 80.0
        val result = BoostFloorSlewShadow.computeSuggestion(shortWindow, longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.slewPctPerCycle).isEqualTo(10.0)
    }

    @Test fun `slew is clamped to the 1 percent floor for a tiny delta`() {
        val longWindow = List(20) { sample(it.toLong(), 0.5) }
        val shortWindow = listOf(sample(0L, 0.500), sample(300_000L, 0.501))   // delta 0.1
        val result = BoostFloorSlewShadow.computeSuggestion(shortWindow, longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.slewPctPerCycle).isEqualTo(1.0)
    }

    @Test fun `empty short window falls back to the slew ceiling rather than crashing`() {
        val longWindow = List(20) { sample(it.toLong(), 0.5) }
        val result = BoostFloorSlewShadow.computeSuggestion(emptyList(), longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.slewPctPerCycle).isEqualTo(BoostFloorSlewShadow.SLEW_MAX_PCT_PER_CYCLE)
    }

    @Test fun `sample counts are reported through to the suggestion`() {
        val longWindow = List(22) { sample(it.toLong(), 0.5) }
        val shortWindow = List(3) { sample(it.toLong(), 0.5) }
        val result = BoostFloorSlewShadow.computeSuggestion(shortWindow, longWindow, aggressivenessPct = 100.0)
        assertThat(result!!.longWindowSamples).isEqualTo(22)
        assertThat(result.shortWindowSamples).isEqualTo(3)
    }
}
