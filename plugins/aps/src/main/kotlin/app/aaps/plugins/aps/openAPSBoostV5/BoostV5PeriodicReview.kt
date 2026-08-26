package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.keys.DoubleKey
import kotlin.math.abs

/**
 * Konzept 7 (2026-08-26) — periodic re-suggestion review: recompute every
 * [BoostV5AutoConfig]-managed double knob (whether already resolved or not, per
 * [BoostV5AutoConfigApply]'s one-shot lock) from the user's CURRENT V1 history, and surface any item
 * whose fresh suggestion differs from the currently-operative value. This path is deliberately
 * SEPARATE from, and never writes through, [BoostV5AutoConfigApply]'s per-knob resolution state
 * machine — that machine exists to make the first-activation auto-config a one-shot ("a human
 * decision takes precedence"); this is a recurring, human-confirmed review on top of it. Nothing
 * computed here is ever applied without the user checking the specific item in the review dialog.
 *
 * Covers every knob in [BoostV5AutoConfigApply.managedDoubleKeys] plus
 * [DoubleKey.ApsBoostFloorSlewAggressiveness] (the Konzept-1 floor/slew shadow's bidirectional
 * scale — never part of the one-shot auto-config, since it has no day-1 onboarding need, but very
 * much worth periodically re-suggesting as trailing history shifts). Alcohol calibration is
 * deliberately excluded — there is no comparable trailing-history-derived formula for it.
 *
 * PURE — no Android / no I/O. The plugin gathers the [BoostV5AutoConfig.V1Profile] and the current
 * preference values and calls [computeReviewItems]; the review dialog renders the result and the
 * plugin writes only the items the user actually confirmed.
 */
object BoostV5PeriodicReview {

    /**
     * Minimum absolute change before a knob is worth surfacing. All suggested values are already
     * rounded to 1 or 2 decimal places by [BoostV5AutoConfig.compute], so this only filters
     * float round-trip noise (AdaptiveDoublePreference persists as Float), not genuine differences.
     */
    private const val MIN_ABS_DELTA = 0.01

    data class ReviewItem(
        val key: DoubleKey,
        val currentValue: Double,
        val suggestedValue: Double,
        val rationale: String,
        // Per BoostV5AutoConfigApply.isUserTuned: the CURRENT stored value already differs from
        // every factory default the key ever shipped with — i.e. a human (or the one-shot
        // auto-config) deliberately set it. Surfaced so the review dialog can mark it, NOT used to
        // filter the item out — Konzept 7 re-derives and offers every knob regardless.
        val wasUserTuned: Boolean
    )

    private fun candidateKnobs(s: BoostV5AutoConfig.V5Suggestion): List<Pair<DoubleKey, Double>> =
        BoostV5AutoConfigApply.managedDoubleKnobs(s) +
            (DoubleKey.ApsBoostFloorSlewAggressiveness to s.floorSlewAggressiveness)

    /**
     * Returns null when there isn't enough V1 history to responsibly re-derive (same
     * data-sufficiency gate as [BoostV5AutoConfig.compute]). Returns an empty list when there IS
     * enough data but every knob's fresh suggestion already matches its current value — i.e.
     * genuinely nothing to review, as opposed to "couldn't compute".
     *
     * [currentValue] should read the RAW stored preference (may be null = never set, i.e. at
     * factory default) — the same shape [BoostV5AutoConfigApply.isUserTuned] expects.
     */
    fun computeReviewItems(p: BoostV5AutoConfig.V1Profile, currentValue: (DoubleKey) -> Double?): List<ReviewItem>? {
        val suggestion = BoostV5AutoConfig.compute(p) ?: return null
        return candidateKnobs(suggestion).mapNotNull { (key, suggested) ->
            val stored = currentValue(key)
            val current = stored ?: key.defaultValue
            if (abs(current - suggested) < MIN_ABS_DELTA) return@mapNotNull null
            ReviewItem(
                key = key,
                currentValue = current,
                suggestedValue = suggested,
                rationale = suggestion.rationaleByKey[key] ?: "",
                wasUserTuned = BoostV5AutoConfigApply.isUserTuned(key, stored)
            )
        }
    }
}
