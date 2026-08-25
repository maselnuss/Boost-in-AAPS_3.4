package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Konzept 6 (2026-08-25) — pure-function tests for AlcoholShadow. Each rule hand-verified before
 * writing the assertion, per the same discipline as ManualMealTapWindowTest.
 */
class AlcoholShadowTest {

    // ─── Intensity escalation ladder ──────────────────────────────────────────

    @Test fun `intensity escalates one step at a time, HIGH is the ceiling`() {
        assertThat(AlcoholShadow.Intensity.LIGHT.escalated()).isEqualTo(AlcoholShadow.Intensity.MODERATE)
        assertThat(AlcoholShadow.Intensity.MODERATE.escalated()).isEqualTo(AlcoholShadow.Intensity.HIGH)
        assertThat(AlcoholShadow.Intensity.HIGH.escalated()).isEqualTo(AlcoholShadow.Intensity.HIGH)
    }

    @Test fun `intensity multipliers match the agreed AIMI-derived values`() {
        assertThat(AlcoholShadow.Intensity.LIGHT.smbMultiplier).isEqualTo(0.85)
        assertThat(AlcoholShadow.Intensity.MODERATE.smbMultiplier).isEqualTo(0.65)
        assertThat(AlcoholShadow.Intensity.HIGH.smbMultiplier).isEqualTo(0.50)
    }

    // ─── countUpwardCrossings ──────────────────────────────────────────────────

    @Test fun `no crossing when BG never reaches the threshold`() {
        assertThat(AlcoholShadow.countUpwardCrossings(listOf(100.0, 120.0, 140.0, 155.0))).isEqualTo(0)
    }

    @Test fun `one crossing for a single rise above threshold, staying above does not add more`() {
        // 150 -> 165 (1 crossing) -> 170 -> 175 (still above, no NEW crossing)
        assertThat(AlcoholShadow.countUpwardCrossings(listOf(150.0, 165.0, 170.0, 175.0))).isEqualTo(1)
    }

    @Test fun `two separate waves count as two crossings`() {
        // 150 -> 165 (cross 1) -> 140 (back below) -> 168 (cross 2)
        assertThat(AlcoholShadow.countUpwardCrossings(listOf(150.0, 165.0, 140.0, 168.0))).isEqualTo(2)
    }

    @Test fun `exactly-at-threshold counts as crossed (inclusive)`() {
        assertThat(AlcoholShadow.countUpwardCrossings(listOf(159.0, 160.0))).isEqualTo(1)
    }

    @Test fun `custom threshold parameter is respected`() {
        assertThat(AlcoholShadow.countUpwardCrossings(listOf(90.0, 110.0), threshold = 100.0)).isEqualTo(1)
        assertThat(AlcoholShadow.countUpwardCrossings(listOf(90.0, 95.0), threshold = 100.0)).isEqualTo(0)
    }

    // ─── shouldEscalate (2026-08-25: waves-only, repeat-tap escalation removed) ──

    @Test fun `escalates once enough waves are seen`() {
        assertThat(AlcoholShadow.shouldEscalate(waveCrossings = 2)).isTrue()
        assertThat(AlcoholShadow.shouldEscalate(waveCrossings = 3)).isTrue()
    }

    @Test fun `does not escalate below the wave threshold`() {
        assertThat(AlcoholShadow.shouldEscalate(waveCrossings = 0)).isFalse()
        assertThat(AlcoholShadow.shouldEscalate(waveCrossings = 1)).isFalse()
    }

    // ─── effectiveSmbMultiplier / hyper brake ─────────────────────────────────

    @Test fun `below hyper-brake threshold uses the intensity's own multiplier`() {
        assertThat(AlcoholShadow.effectiveSmbMultiplier(AlcoholShadow.Intensity.HIGH, currentBg = 179.9)).isEqualTo(0.50)
        assertThat(AlcoholShadow.effectiveSmbMultiplier(AlcoholShadow.Intensity.LIGHT, currentBg = 100.0)).isEqualTo(0.85)
    }

    @Test fun `at or above hyper-brake threshold damping is fully suspended regardless of intensity`() {
        assertThat(AlcoholShadow.effectiveSmbMultiplier(AlcoholShadow.Intensity.HIGH, currentBg = 180.0)).isEqualTo(1.0)
        assertThat(AlcoholShadow.effectiveSmbMultiplier(AlcoholShadow.Intensity.HIGH, currentBg = 200.0)).isEqualTo(1.0)
        assertThat(AlcoholShadow.effectiveSmbMultiplier(AlcoholShadow.Intensity.LIGHT, currentBg = 180.0)).isEqualTo(1.0)
    }

    // ─── isBgStable ────────────────────────────────────────────────────────────

    @Test fun `fewer than 2 readings is never stable`() {
        assertThat(AlcoholShadow.isBgStable(emptyList())).isFalse()
        assertThat(AlcoholShadow.isBgStable(listOf(120.0))).isFalse()
    }

    @Test fun `range within the band counts as stable, exactly-at-band is still stable (inclusive)`() {
        assertThat(AlcoholShadow.isBgStable(listOf(110.0, 118.0, 115.0))).isTrue()   // range 8
        assertThat(AlcoholShadow.isBgStable(listOf(110.0, 125.0))).isTrue()          // range exactly 15
    }

    @Test fun `range beyond the band is not stable`() {
        assertThat(AlcoholShadow.isBgStable(listOf(110.0, 126.0))).isFalse()         // range 16
    }

    // ─── protectionShouldEnd ───────────────────────────────────────────────────

    @Test fun `never ends before the minimum duration, even if BG stable and IOB low`() {
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 119, bgStable = true, iob = 0.1)).isFalse()
    }

    @Test fun `ends right at minimum duration if already stable with low IOB`() {
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 120, bgStable = true, iob = 0.1)).isTrue()
    }

    @Test fun `stays active past minimum duration if not yet stable or IOB still high`() {
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 200, bgStable = false, iob = 0.1)).isFalse()
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 200, bgStable = true, iob = 1.5)).isFalse()
    }

    @Test fun `IOB exactly at the threshold does not yet count as low (strict less-than)`() {
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 200, bgStable = true, iob = 1.0)).isFalse()
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 200, bgStable = true, iob = 0.99)).isTrue()
    }

    @Test fun `hard ceiling ends protection regardless of BG or IOB`() {
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 480, bgStable = false, iob = 5.0)).isTrue()
        assertThat(AlcoholShadow.protectionShouldEnd(elapsedMin = 1000, bgStable = false, iob = 5.0)).isTrue()
    }
}
