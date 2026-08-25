package app.aaps.plugins.aps.openAPSBoost

/**
 * Konzept 6 (2026-08-25) — Alkohol-Button shadow logic. Pure, stateless functions only — no plugin
 * state, no DB/preferences access, nothing Android-specific. The plugin owns all state (protection
 * start time, current intensity) and BG-history fetching; this object only turns already-extracted
 * inputs into decisions, so every rule here is directly unit-testable in isolation.
 *
 * Design background (see Claude_boost_extension_ideas.md §6.1.3/§6.1.4 for the full reasoning):
 * single manual button, no pre-selection of amount, starts light, self-escalates on repeated BG
 * waves or a repeated tap, adaptive duration (2h floor, 8h ceiling), damping levels taken from
 * AIMI's own established range. Shadow-first — nothing here ever touches real dosing on its own;
 * the plugin only LOGS what this would compute until the feature is validated over real weeks.
 */
object AlcoholShadow {

    /** BG (mg/dl) crossing this from below counts as one "wave" toward escalation. Deliberately NOT
     * lowered to reduce hyper risk — escalating means MORE damping (LESS insulin), which increases
     * near-term hyper risk rather than reducing it. That concern is instead addressed by
     * [HYPER_BRAKE_THRESHOLD_MGDL] below, a separate, independent mechanism. */
    const val WAVE_THRESHOLD_MGDL = 160.0

    /** This many upward crossings of [WAVE_THRESHOLD_MGDL] within the protection window escalates
     * intensity one step, same as a repeated tap. */
    const val WAVE_COUNT_FOR_ESCALATION = 2

    /** Independent safety ceiling (2026-08-25, user idea): regardless of current intensity, BG at or
     * above this fully suspends damping for the cycle (effective multiplier 1.0) — prioritises
     * correcting a genuine high over the delayed-hypo protection this feature exists for. Verified
     * against the real incident: that night's peaks (180/193/188) sit right at/above this threshold,
     * so the brake would have engaged exactly at the peaks without undermining the protection's
     * actual purpose (which matters hours later, once BG is much lower). */
    const val HYPER_BRAKE_THRESHOLD_MGDL = 180.0

    /** Guaranteed minimum protection duration from the first tap, regardless of how calm BG looks. */
    const val MIN_DURATION_MIN = 120

    /** Hard ceiling from the first tap — protection ends here even if BG/IOB never satisfy the
     * adaptive end-condition below. */
    const val MAX_DURATION_MIN = 480

    /** Lookback window for the "has BG been calm" adaptive-end check. */
    const val STABILITY_LOOKBACK_MIN = 90

    /** Max (BG range) within [STABILITY_LOOKBACK_MIN] to count as "stable". */
    const val STABILITY_BAND_MGDL = 15.0

    /** Fixed absolute IOB floor (2026-08-25, corrected from an earlier "20% of maxIOB" proposal): at
     * this user's real maxIOB of 10.0U, 20% would have been 2.0U — ABOVE the 1.85U IOB still active
     * during the real documented crash. 1.0U keeps a real margin below that evidence point.
     * Deliberately an absolute value, not a fraction of maxIOB: the risk this guards against (active
     * insulin + alcohol-suppressed counter-regulation) doesn't scale with a user's personal maxIOB
     * ceiling, which can change for unrelated reasons. Applies equally to IOB from a concurrent meal
     * — that's intentional, not a bug: the liver-suppression risk doesn't care where the IOB came
     * from, so protection correctly runs longer on evenings with food too (bounded by the 8h cap). */
    const val LOW_IOB_THRESHOLD_U = 1.0

    enum class Intensity(val smbMultiplier: Double) {
        LIGHT(0.85),
        MODERATE(0.65),
        HIGH(0.50);

        /** One step up; HIGH is the ceiling (no further escalation). */
        fun escalated(): Intensity = when (this) {
            LIGHT -> MODERATE
            MODERATE, HIGH -> HIGH
        }
    }

    /**
     * How many times does [bgChronological] cross [threshold] from below? Only upward crossings
     * count (a single sustained high is one wave, not one per reading above threshold).
     * @param bgChronological BG values in ascending time order.
     */
    fun countUpwardCrossings(bgChronological: List<Double>, threshold: Double = WAVE_THRESHOLD_MGDL): Int {
        var crossings = 0
        for (i in 1 until bgChronological.size) {
            if (bgChronological[i - 1] < threshold && bgChronological[i] >= threshold) crossings++
        }
        return crossings
    }

    /** Escalate one step once enough real BG waves were seen this protection window. Deliberately
     * NOT also triggered by a repeat tap (2026-08-25, removed after user question — earlier design
     * had "tap again = escalate too"): a tap doesn't confirm anything BG-relevant actually happened
     * (e.g. a straight spirit with no mixer produces no wave at all), so it could escalate — reduce
     * insulin — on a signal unconnected to real risk, and an uncertain "did that register?" re-tap
     * could do it by accident. Waves measure the actual thing that matters. The one real documented
     * incident (6-8 Biere + Malibu) had THREE real waves — wave-detection alone already covers it. */
    fun shouldEscalate(waveCrossings: Int): Boolean =
        waveCrossings >= WAVE_COUNT_FOR_ESCALATION

    /** The SMB multiplier actually in effect THIS cycle — the hyper-brake overrides intensity-based
     * damping entirely (not partially, back to 1.0 = no damping) once BG reaches the threshold. */
    fun effectiveSmbMultiplier(intensity: Intensity, currentBg: Double): Double =
        if (currentBg >= HYPER_BRAKE_THRESHOLD_MGDL) 1.0 else intensity.smbMultiplier

    /** Is BG calm enough, over the (already time-filtered) [bgInLookbackWindow], to count toward
     * ending protection? Requires at least 2 readings — a single point can't demonstrate stability. */
    fun isBgStable(bgInLookbackWindow: List<Double>): Boolean {
        if (bgInLookbackWindow.size < 2) return false
        val range = bgInLookbackWindow.max() - bgInLookbackWindow.min()
        return range <= STABILITY_BAND_MGDL
    }

    /**
     * Should alcohol protection end now?
     * @param elapsedMin minutes since the first tap of this protection session.
     */
    fun protectionShouldEnd(elapsedMin: Long, bgStable: Boolean, iob: Double): Boolean = when {
        elapsedMin >= MAX_DURATION_MIN -> true                              // hard ceiling, always wins
        elapsedMin < MIN_DURATION_MIN -> false                              // guaranteed floor
        else -> bgStable && iob < LOW_IOB_THRESHOLD_U
    }
}
