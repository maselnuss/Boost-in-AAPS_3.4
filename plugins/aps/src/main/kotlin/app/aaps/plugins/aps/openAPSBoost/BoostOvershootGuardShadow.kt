package app.aaps.plugins.aps.openAPSBoost

/**
 * BoostOvershootGuardShadow — Konzept 10 (2026-08-27), SHADOW ONLY.
 *
 * A continuous meal-overshoot dampener: a smooth multiplier on the prospective COMMITTED-state
 * dose, scaled by how far eventualBG already exceeds target — no state machine, no
 * declining-streak, just a function of the current cycle's own prediction. Concept ported from a
 * comparable mechanism in the AIMI fork (`computeMealAggressionWeights`/`guardScale`,
 * DetermineBasalAIMI2.kt:14332-14343, direction verified against its actual downstream use
 * `smbToGive *= guardScale` at line 13840): dampens MORE at small overshoot and LESS as overshoot
 * grows toward severe — more caution on a borderline predicted excess, less caution once it's
 * unambiguously severe.
 *
 * Two variants, logged in parallel so they can be compared against each other and against the
 * live dose over the coming weeks (see OpenAPSBoostPlugin.kt's `overshootGuardFixedShadow=` /
 * `overshootGuardComputedShadow=` call site):
 *  - [FIXED]: AIMI's own coefficients (0.4 base, 0.3 range, /80 mg/dl normalisation) verbatim —
 *    not this user's data, a borrowed reference point.
 *  - [computeCoefficients]: re-derived from THIS user's own numbers, same architectural pattern
 *    as BoostFloorSlewShadow's rolling floor/slew estimate — base/range from the live
 *    committedCapU/confirmedCapU ratio (how conservative routine ongoing dosing already is
 *    relative to the max single confirm shot — an existing, per-user AutoConfig-derived number,
 *    not borrowed from anywhere), and the overshoot-normalisation scale from the rolling P90 of
 *    this user's own historical (eventualBG − targetBG) overshoot, instead of AIMI's fixed 80.
 *
 * Pure, stateless functions only — no plugin state, no DB/preferences access, nothing
 * Android-specific. The plugin owns all I/O and passes already-extracted samples in, so every
 * rule here is directly unit-testable in isolation (same split as BoostFloorSlewShadow).
 */
object BoostOvershootGuardShadow {

    /** One past cycle's overshoot: max(0, eventualBG − targetBG), mg/dl. */
    data class OvershootSample(val timestampMs: Long, val overshootMgdl: Double)

    data class Coefficients(val base: Double, val range: Double, val normalizationMgdl: Double)

    /** AIMI's own tuning, unchanged — see class KDoc. Reference point for the COMPUTED variant,
     *  not a target it's pulled toward. */
    val FIXED = Coefficients(base = 0.4, range = 0.3, normalizationMgdl = 80.0)

    /** Below this many samples in the rolling window, a P90 estimate is too noisy — same reasoning
     *  as BoostFloorSlewShadow.MIN_SAMPLES_FOR_ESTIMATE. */
    const val MIN_SAMPLES_FOR_ESTIMATE = 20
    private const val OVERSHOOT_PERCENTILE = 0.90

    // Rails: never derive something wildly outside AIMI's own validated shape, regardless of what
    // this user's rolling data says (safety, not a preference).
    private const val NORMALIZATION_MIN_MGDL = 40.0
    private const val NORMALIZATION_MAX_MGDL = 150.0
    private const val BASE_MIN = 0.2
    private const val BASE_MAX = 0.6

    /** Guard-scale never exceeds this — matches AIMI's own stated ceiling (its realised max at the
     *  FIXED coefficients is ~0.7, since 0.4+0.3 < 0.85, but 0.85 is the ceiling it explicitly
     *  coded for). Applies to BOTH variants so the computed one can never disable the dampening
     *  entirely even at extreme overshoot. */
    const val GUARD_SCALE_CEILING = 0.85

    /**
     * @param committedCapU live ApsBoostV5CommittedCapU preference (U).
     * @param confirmedCapU live ApsBoostV5ConfirmedCapU preference (U).
     * @param overshootWindow rolling window of positive-only (eventualBG − targetBG) samples,
     *   trailing ~14 days (mirrors BoostFloorSlewShadow's long window) — the actual spread of
     *   overshoot severities this user has experienced, not AIMI's cohort-wide guess.
     * @return null if [overshootWindow] doesn't have enough samples yet for a meaningful estimate.
     */
    fun computeCoefficients(
        committedCapU: Double,
        confirmedCapU: Double,
        overshootWindow: List<OvershootSample>,
    ): Coefficients? {
        if (overshootWindow.size < MIN_SAMPLES_FOR_ESTIMATE) return null
        // base = how conservative routine ongoing dosing already is relative to this user's own
        // max single confirm shot. Reuses two numbers AutoConfig already derives per-user
        // (committedCapU ≈ "max of your routine SMB size and TDD/40", confirmedCapU ≈ "your
        // biggest typical single dose") instead of borrowing AIMI's fixed 0.4.
        val base = if (confirmedCapU > 0.0) (committedCapU / confirmedCapU).coerceIn(BASE_MIN, BASE_MAX) else FIXED.base
        val range = (GUARD_SCALE_CEILING - base).coerceAtLeast(0.0)
        val sorted = overshootWindow.map { it.overshootMgdl }.sorted()
        val idx = (sorted.size * OVERSHOOT_PERCENTILE).toInt().coerceIn(0, sorted.size - 1)
        val normalization = sorted[idx].coerceIn(NORMALIZATION_MIN_MGDL, NORMALIZATION_MAX_MGDL)
        return Coefficients(base = base, range = range, normalizationMgdl = normalization)
    }

    /** Same shape for both variants — only [coeffs] differs between FIXED and computed. */
    fun guardScale(overshootMgdl: Double, coeffs: Coefficients, minOvershootGateMgdl: Double = 10.0): Double {
        if (overshootMgdl <= minOvershootGateMgdl) return 1.0
        val normalized = (overshootMgdl / coeffs.normalizationMgdl).coerceIn(0.0, 1.0)
        return (coeffs.base + coeffs.range * normalized).coerceAtMost(GUARD_SCALE_CEILING)
    }
}
