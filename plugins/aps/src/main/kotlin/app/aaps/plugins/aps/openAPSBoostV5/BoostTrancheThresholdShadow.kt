package app.aaps.plugins.aps.openAPSBoostV5

/**
 * BoostTrancheThresholdShadow — SHADOW ONLY (2026-09-03, user request: "sollten wir die Berechnung
 * direkt mitlaufen lassen?").
 *
 * Derives what [ConfirmTrancheController.releaseThreshold] SHOULD be for this user, from their own
 * confirm episodes, and logs the answer. Never writes a preference, never touches dosing — the
 * on-device counterpart to the offline replay in
 * `modules/server/apps/nightscout/tools/tranche_and_postrescue_tuning.py` (2026-09-03), which found
 * 0.30–0.35 for this user against the 0.48 population default.
 *
 * WHY on-device at all, when the offline script exists: the script approximates `slope` with a
 * sample five minutes back, because Nightscout does not carry the controller's own `lastBg`. Here
 * the real per-cycle BG series is available, so the replay is exact. Running both lets the offline
 * number be validated rather than trusted.
 *
 * WHY this is NOT wired into auto-config (2026-09-03 decision): the value cannot be inferred from
 * summary statistics — upstream measured it correlates only -0.32 with the obvious predictor, which
 * is why it needs this episode replay in the first place. Auto-config's compute() is a percentile
 * machine over aggregate profile stats; this is a different kind of computation and stays separate
 * until it has earned a place (see TODO.md).
 *
 * ── The feedback-loop caveat, which matters for reading the output ──────────────────────────────
 * While the tranche is OFF, every episode's outcome is a clean counterfactual: nothing was withheld,
 * so "would releasing have been right" is answerable directly. Once the tranche is ON, the outcomes
 * are partly CAUSED by the tranche's own withholding decisions, so a naive sweep is measuring its
 * own footprint. [Suggestion.thresholdInEffect] is logged alongside precisely so this is visible
 * rather than hidden — when it is non-null the numbers describe a system that was already acting,
 * and the honest reading is "how did the threshold in force perform", not "what is optimal".
 *
 * Pure functions only — no DB, no preferences, nothing Android-specific. The plugin gathers the
 * episodes and owns all I/O; this only turns them into a suggestion, so every rule is unit-testable.
 */
object BoostTrancheThresholdShadow {

    /**
     * One replayed confirm episode.
     *
     * @param pRelease the release rule's probability at the release cycle, from
     *        [ConfirmTrancheController.probabilityFor] — the same arithmetic the live path runs.
     * @param peakAfter highest BG in the outcome window after the confirm.
     * @param troughAfter lowest BG in the same window.
     */
    data class Episode(val atMs: Long, val pRelease: Double, val peakAfter: Double, val troughAfter: Double)

    data class Suggestion(
        val bestThreshold: Double,
        val episodes: Int,
        /** Episodes where releasing was right (peak reached [PEAK_NEEDED_MGDL], no following low). */
        val releaseWasRight: Int,
        /** Episodes where holding was right (a low followed and the peak never justified the dose). */
        val holdWasRight: Int,
        /** Correct decisions the winning threshold makes, out of [releaseWasRight] + [holdWasRight]. */
        val correctAtBest: Int,
        /** Correct decisions the CURRENT setting makes, for a like-for-like comparison. */
        val correctAtCurrent: Int,
        /** Non-null once the tranche is live — see the feedback-loop caveat in the class KDoc. */
        val thresholdInEffect: Double?,
    )

    /** Upstream's stated per-person range; nothing outside it is worth suggesting. */
    val CANDIDATE_THRESHOLDS = listOf(0.30, 0.35, 0.40, 0.45, 0.48, 0.50, 0.55, 0.60, 0.65)

    /**
     * Below this many UNAMBIGUOUS episodes the sweep is noise. Deliberately counted after the
     * ambiguous ones are dropped (see [computeSuggestion]), not before — 30 raw episodes of which 10
     * are ambiguous is a 20-episode question, and reporting it as 30 would overstate the evidence.
     * The offline run on 2026-09-03 had 32 unambiguous out of 57 raw.
     */
    const val MIN_UNAMBIGUOUS_EPISODES = 15

    /** Peak (mg/dL) at or above which the excursion is taken to have justified the full dose. */
    const val PEAK_NEEDED_MGDL = 180.0

    /** Trough (mg/dL) below which the episode is taken to have been over-dosed. */
    const val HYPO_MGDL = 70.0

    /**
     * Sweeps [CANDIDATE_THRESHOLDS] over [episodes] and returns the one making the most correct
     * decisions, or null when there is not enough unambiguous evidence.
     *
     * Episodes that are BOTH "needed" and "too much" (a spike that then crashes) are DROPPED, not
     * scored. They are genuinely ambiguous — the right answer there is not a threshold but a
     * different mechanism — and scoring them on both sides double-counts them, which is exactly the
     * error the first offline pass made on 2026-09-03 before the numbers were split out.
     *
     * Ties go to the HIGHER threshold: a higher threshold withholds more, and when the evidence
     * cannot separate two options the more restrained one is the safer default for a user whose
     * documented problem is over-delivery.
     */
    fun computeSuggestion(
        episodes: List<Episode>,
        currentThreshold: Double,
        thresholdInEffect: Double?,
    ): Suggestion? {
        val releaseRight = episodes.filter { it.peakAfter >= PEAK_NEEDED_MGDL && it.troughAfter >= HYPO_MGDL }
        val holdRight = episodes.filter { it.troughAfter < HYPO_MGDL && it.peakAfter < PEAK_NEEDED_MGDL }
        if (releaseRight.size + holdRight.size < MIN_UNAMBIGUOUS_EPISODES) return null

        fun correctAt(t: Double) =
            releaseRight.count { it.pRelease > t } + holdRight.count { it.pRelease <= t }

        val best = CANDIDATE_THRESHOLDS.maxWithOrNull(
            compareBy<Double> { correctAt(it) }.thenBy { it }      // tie -> higher threshold
        ) ?: return null

        return Suggestion(
            bestThreshold = best,
            episodes = releaseRight.size + holdRight.size,
            releaseWasRight = releaseRight.size,
            holdWasRight = holdRight.size,
            correctAtBest = correctAt(best),
            correctAtCurrent = correctAt(currentThreshold),
            thresholdInEffect = thresholdInEffect,
        )
    }
}
