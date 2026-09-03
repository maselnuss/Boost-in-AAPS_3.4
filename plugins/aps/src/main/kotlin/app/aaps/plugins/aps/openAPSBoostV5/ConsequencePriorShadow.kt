package app.aaps.plugins.aps.openAPSBoostV5

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Consequence prior, SHADOW ONLY (2026-08-26). Computes and logs; delivers nothing.
 *
 * At a rise onset the engine must decide how much to commit, and the thing that would justify a
 * large commitment is whether this particular rise is going somewhere that matters. Measured over
 * 1,986,123 rise onsets from 1,807 participants across seven observational corpora, that is
 * separable at 0.812 from glucose at the onset alone and 0.829 once the hour is added, while the
 * shape of the first ten minutes adds +0.014 on top of the two.
 *
 * The reason this is worth logging rather than dismissing as something the loop already knows is
 * that the loop does not know it. On 27,619 onsets from 36 participants of this fork, the engine's
 * own forward projection scores 0.527 against a base rate of 0.398 for whether glucose will pass
 * 180, which is chance; the prior scores 0.763, and adding the entire engine record to the prior is
 * worth +0.001. The controller holds onset glucose already and does not read it for this purpose.
 *
 * Shipping form is a logistic rather than the booster the measurement used: three inputs, four
 * coefficients, deterministic, pre-trained offline and applied at inference, which is what the
 * dose-path rule permits. The simplification costs 0.010 of separation on the absolute target and
 * 0.015 on the rise target, fitted on 302,633 onsets from 400 participants and held out by
 * participant. Coefficients are in backtesting/scripts/2026-08-meal-size-readability/out/
 * consequence_prior_fit.json and are reproduced by fit_consequence_prior.py.
 *
 * Onset is tracked here rather than taken from the meal state machine deliberately. The ladder's
 * escalation is measured to add nothing beyond the visible rise when it fires early, so binding the
 * prior to it would inherit that. Onset is the last non-rising sample, which is what a detector
 * could find without any state at all.
 */
class ConsequencePriorShadow(
    private val riseResetMgdl: Double = 2.0,     // a fall of this much re-anchors the onset
    private val maxOnsetAgeMin: Double = 180.0,  // an onset older than this is stale, re-anchor
) {

    // Fitted 2026-08-26 on the JAEB corpus, held out by participant. See the class KDoc.
    private companion object {
        const val HIGH_B0 = -3.92725; const val HIGH_BG = 0.038878
        const val HIGH_SIN = -0.488715; const val HIGH_COS = -0.393587
        const val RISE_B0 = 2.19673; const val RISE_BG = -0.011494
        const val RISE_SIN = -0.404185; const val RISE_COS = -0.443666
        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
    }

    private var onsetBg: Double? = null
    private var onsetMs: Long = 0L
    private var lastBg: Double? = null

    /** Exposed for tests and for a caller that wants the anchor without the tag. */
    fun onsetGlucose(): Double? = onsetBg

    /**
     * One cycle. Returns the reason-tag payload, or null if the inputs are unusable.
     *
     * The onset re-anchors when glucose falls, when nothing is anchored yet, or when the anchor has
     * gone stale. It is deliberately not cleared on a state change, because the state machine is not
     * what defines a rise.
     */
    fun runCycle(nowMs: Long, bg: Double?): String? {
        if (bg == null || !bg.isFinite() || bg <= 0.0) return null
        val prev = lastBg
        lastBg = bg
        val ageMin = if (onsetMs > 0L) (nowMs - onsetMs) / 60000.0 else Double.MAX_VALUE
        val falling = prev != null && bg < prev - riseResetMgdl
        if (onsetBg == null || falling || ageMin > maxOnsetAgeMin) {
            onsetBg = bg
            onsetMs = nowMs
        }
        val ob = onsetBg ?: return null
        val mins = (nowMs - onsetMs) / 60000.0
        // Local clock, not UTC. The coefficients were fitted against local time in the corpus, so
        // scoring them against UTC would shift the two clock terms by the offset and quietly cost
        // most of what the hour contributes.
        val localMs = nowMs + java.util.TimeZone.getDefault().getOffset(nowMs)
        val hour = ((localMs % 86_400_000L) / 3_600_000.0)
        val s = sin(2.0 * PI * hour / 24.0)
        val c = cos(2.0 * PI * hour / 24.0)
        val pHigh = sigmoid(HIGH_B0 + HIGH_BG * ob + HIGH_SIN * s + HIGH_COS * c)
        val pRise = sigmoid(RISE_B0 + RISE_BG * ob + RISE_SIN * s + RISE_COS * c)
        val riseSoFar = bg - ob
        return "${r3(pHigh)},${r3(pRise)},${ob.toInt()},${mins.toInt()},${riseSoFar.toInt()}"
    }

    private fun r3(v: Double): String = (kotlin.math.round(v * 1000.0) / 1000.0).toString()
}
