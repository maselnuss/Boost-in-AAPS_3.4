package app.aaps.plugins.aps.openAPSBoost

/**
 * PostExerciseRecoveryShadow — proposed improvement to the existing (LIVE) post-exercise recovery
 * window formula (OpenAPSBoostPlugin.kt's postExerciseRecoveryEnabled block, ~line 1140+), SHADOW
 * ONLY for now. The live formula and what it actually applies (real TempTarget, real SMB scale) are
 * UNCHANGED — this object only computes what the improved version WOULD do, for comparison logging,
 * per the same Validierungs-Stufenleiter as everything else: propose, shadow-log, evaluate real
 * weeks, only then consider switching the live mechanism over.
 *
 * Two fixes agreed 2026-08-26, both motivated by a real scenario: a short (e.g. 15min) bike ride to
 * a friend's, followed by eating/drinking there —
 *  1. Window length scales with actual exercise duration (capped at today's fixed formula's own
 *     result), instead of always being the fixed maximum regardless of whether the session was 15min
 *     or 3 hours.
 *  2. A BG hyper-brake (same pattern/threshold reasoning as AlcoholShadow's): if BG rises above
 *     [HYPER_BRAKE_MGDL] during an active window, suppress the SMB-dampening for that cycle only
 *     (not a permanent cancel of the rest of the window) — something changed (most likely food),
 *     normal correction should apply again until BG comes back down.
 */
object PostExerciseRecoveryShadow {

    /** Recovery window length scales with actual exercise duration, capped at the existing fixed
     *  formula's own value — long/hard sessions are unaffected (they already exceed the cap), short
     *  ones get proportionally less instead of always the maximum. 2.0 is a starting heuristic
     *  (~2x exercise duration), not a scientifically derived value — expected to be corrected by the
     *  shadow evaluation, not treated as final. */
    const val DURATION_SCALE_FACTOR = 2.0

    /** Above this BG, during an active recovery window, treat it as "something changed" (most likely
     *  food) and suppress SMB-dampening for this cycle only. 180, not e.g. 160: the window's own
     *  elevated target already sits at 144-154 mg/dl by default (VIGOROUS_AEROBIC/RESISTANCE), so a
     *  brake much closer than 180 would fire on ordinary post-exercise fluctuation around that
     *  target and defeat the window's purpose before it can do anything. */
    const val HYPER_BRAKE_MGDL = 180.0

    /** Duration-scaled window length (minutes), capped at [fixedWindowMin] (today's live formula's
     *  own result for this exercise type). */
    fun durationScaledWindowMin(exerciseDurationMin: Long, fixedWindowMin: Int): Int =
        minOf(fixedWindowMin, (exerciseDurationMin * DURATION_SCALE_FACTOR).toInt())

    /** Should the hyper-brake suppress SMB-dampening this cycle, given [currentBg]? */
    fun hyperBrakeActive(currentBg: Double): Boolean = currentBg >= HYPER_BRAKE_MGDL

    /** The effective SMB scale for this cycle: 1.0 (no dampening) if the hyper-brake is active,
     *  otherwise the window's own configured [windowScale] unchanged. */
    fun effectiveScale(windowScale: Double, currentBg: Double): Double =
        if (hyperBrakeActive(currentBg)) 1.0 else windowScale
}
