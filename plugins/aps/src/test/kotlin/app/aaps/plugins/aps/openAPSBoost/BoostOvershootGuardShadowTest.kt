package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-08-28 — pure-function tests for BoostOvershootGuardShadow, added after the fact (noted
 * missing when compared against BoostFloorSlewShadowTest's coverage of the same architectural
 * pattern). Each value hand-verified before writing the assertion, per the same discipline as
 * BoostFloorSlewShadowTest/AlcoholShadowTest/PostExerciseRecoveryShadowTest. Double comparisons
 * use isWithin() rather than isEqualTo() since several of these results are sums of
 * non-binary-exact fractions (0.3, 0.4, 0.65 etc.).
 */
class BoostOvershootGuardShadowTest {

    private fun sample(tMs: Long, overshoot: Double) = BoostOvershootGuardShadow.OvershootSample(tMs, overshoot)

    // ─── computeCoefficients: minimum sample gate ────────────────────────────

    @Test fun `computeCoefficients returns null with one sample short of the minimum`() {
        val window = List(BoostOvershootGuardShadow.MIN_SAMPLES_FOR_ESTIMATE - 1) { sample(it.toLong(), 50.0) }
        assertThat(BoostOvershootGuardShadow.computeCoefficients(0.5, 2.5, window)).isNull()
    }

    @Test fun `computeCoefficients returns coefficients at exactly the minimum sample count`() {
        val window = List(BoostOvershootGuardShadow.MIN_SAMPLES_FOR_ESTIMATE) { sample(it.toLong(), 50.0) }
        assertThat(BoostOvershootGuardShadow.computeCoefficients(0.5, 2.5, window)).isNotNull()
    }

    // ─── computeCoefficients: base from committedCapU/confirmedCapU ratio ────

    @Test fun `base is the committedCapU over confirmedCapU ratio when inside range`() {
        // 1.0 / 2.5 = 0.4, inside [0.2, 0.6] -> unclamped.
        val window = List(20) { sample(it.toLong(), 100.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(1.0, 2.5, window)
        assertThat(result).isNotNull()
        assertThat(result!!.base).isWithin(0.0001).of(0.4)
    }

    @Test fun `base is clamped to the 0_2 floor for a very small committedCap ratio`() {
        // 0.05 / 2.5 = 0.02, below the 0.2 floor.
        val window = List(20) { sample(it.toLong(), 100.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(0.05, 2.5, window)
        assertThat(result!!.base).isWithin(0.0001).of(0.2)
    }

    @Test fun `base is clamped to the 0_6 ceiling for a very large committedCap ratio`() {
        // 2.0 / 2.5 = 0.8, above the 0.6 ceiling.
        val window = List(20) { sample(it.toLong(), 100.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(2.0, 2.5, window)
        assertThat(result!!.base).isWithin(0.0001).of(0.6)
    }

    @Test fun `base falls back to FIXED_base when confirmedCapU is zero, ignoring committedCapU`() {
        val window = List(20) { sample(it.toLong(), 100.0) }
        // committedCapU deliberately absurd (999.0) to prove the fallback branch ignores it
        // entirely rather than merely avoiding a division-by-zero crash.
        val result = BoostOvershootGuardShadow.computeCoefficients(999.0, 0.0, window)
        assertThat(result!!.base).isWithin(0.0001).of(BoostOvershootGuardShadow.FIXED.base)
    }

    @Test fun `range is the ceiling minus base`() {
        val window = List(20) { sample(it.toLong(), 100.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(1.0, 2.5, window)
        // base 0.4 -> range = 0.85 - 0.4 = 0.45
        assertThat(result!!.range).isWithin(0.0001).of(0.45)
    }

    // ─── computeCoefficients: normalisation from the P90 of the overshoot window ──

    @Test fun `normalisation uses the 90th percentile, not the median`() {
        // 20 samples at 30 (the bulk), 5 at 90 (the tail) -> sorted index (25*0.90)=22 falls
        // inside the five 90-value tail, not the 30-value bulk (median would be 30).
        val window = List(20) { sample(it.toLong(), 30.0) } + List(5) { sample(100L + it, 90.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(0.5, 2.5, window)
        assertThat(result!!.normalizationMgdl).isWithin(0.0001).of(90.0)
    }

    @Test fun `normalisation is clamped to the 150 ceiling even if the raw P90 is higher`() {
        val window = List(22) { sample(it.toLong(), 50.0) } + List(3) { sample(100L + it, 200.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(0.5, 2.5, window)
        assertThat(result!!.normalizationMgdl).isWithin(0.0001).of(150.0)
    }

    @Test fun `normalisation is clamped to the 40 floor even if the raw P90 is lower`() {
        val window = List(25) { sample(it.toLong(), 5.0) }
        val result = BoostOvershootGuardShadow.computeCoefficients(0.5, 2.5, window)
        assertThat(result!!.normalizationMgdl).isWithin(0.0001).of(40.0)
    }

    // ─── guardScale: the overshoot gate ──────────────────────────────────────

    @Test fun `guardScale is 1_0 (no dampening) at or below the overshoot gate`() {
        assertThat(BoostOvershootGuardShadow.guardScale(10.0, BoostOvershootGuardShadow.FIXED)).isWithin(0.0001).of(1.0)
        assertThat(BoostOvershootGuardShadow.guardScale(5.0, BoostOvershootGuardShadow.FIXED)).isWithin(0.0001).of(1.0)
    }

    @Test fun `guardScale starts dampening just above the overshoot gate`() {
        // overshoot 20 -> normalized = 20/80 = 0.25 -> 0.4 + 0.3*0.25 = 0.475
        val scale = BoostOvershootGuardShadow.guardScale(20.0, BoostOvershootGuardShadow.FIXED)
        assertThat(scale).isWithin(0.0001).of(0.475)
    }

    // ─── guardScale: FIXED reproduces AIMI's own realised range ──────────────

    @Test fun `FIXED reaches its realised max of 0_7 at overshoot 80, not the 0_85 ceiling`() {
        val scale = BoostOvershootGuardShadow.guardScale(80.0, BoostOvershootGuardShadow.FIXED)
        assertThat(scale).isWithin(0.0001).of(0.7)
    }

    @Test fun `FIXED never exceeds 0_7 even far beyond its normalisation scale`() {
        // overshoot 500 >> normalizationMgdl 80 -> normalized clamps to 1.0, same result as at 80.
        val scale = BoostOvershootGuardShadow.guardScale(500.0, BoostOvershootGuardShadow.FIXED)
        assertThat(scale).isWithin(0.0001).of(0.7)
    }

    // ─── guardScale: a COMPUTED-style coefficients set reaches the shared ceiling ──

    @Test fun `a computed-style coefficients set reaches the 0_85 ceiling at full severity`() {
        // Matches the by-hand derivation for committedCapU=0.5/confirmedCapU=2.5/normalization=90:
        // base=0.2, range=0.85-0.2=0.65.
        val coeffs = BoostOvershootGuardShadow.Coefficients(base = 0.2, range = 0.65, normalizationMgdl = 90.0)
        val scale = BoostOvershootGuardShadow.guardScale(90.0, coeffs)
        assertThat(scale).isWithin(0.0001).of(BoostOvershootGuardShadow.GUARD_SCALE_CEILING)
    }

    @Test fun `guardScale never exceeds the shared ceiling even for an extreme coefficients set`() {
        val coeffs = BoostOvershootGuardShadow.Coefficients(base = 0.6, range = 0.6, normalizationMgdl = 40.0)
        val scale = BoostOvershootGuardShadow.guardScale(1000.0, coeffs)
        assertThat(scale).isWithin(0.0001).of(BoostOvershootGuardShadow.GUARD_SCALE_CEILING)
    }

    // ─── guardScale: monotonicity (catches a sign-flip regression cheaply) ───

    @Test fun `guardScale never decreases as overshoot grows`() {
        val points = listOf(15.0, 30.0, 50.0, 80.0, 120.0)
        val scales = points.map { BoostOvershootGuardShadow.guardScale(it, BoostOvershootGuardShadow.FIXED) }
        for (i in 0 until scales.size - 1) {
            assertThat(scales[i + 1]).isAtLeast(scales[i])
        }
    }
}
