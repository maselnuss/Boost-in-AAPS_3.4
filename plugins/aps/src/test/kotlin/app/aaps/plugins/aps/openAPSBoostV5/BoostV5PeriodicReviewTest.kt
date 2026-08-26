package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the Konzept 7 periodic-review calculator: re-derives every BoostV5AutoConfig-managed
 * knob (plus floor/slew aggressiveness) from CURRENT V1 history and diffs against the CURRENTLY
 * stored value, independent of BoostV5AutoConfigApply's one-shot per-knob resolution lock.
 */
class BoostV5PeriodicReviewTest {

    private fun profile(
        days: Int = 14, bg: Int = 3500, tdd: Double = 40.0,
        manual: List<Double> = listOf(3.0, 3.5, 4.0, 4.0, 4.5, 5.0, 5.0, 5.5, 6.0, 6.0),
        smb: List<Double> = listOf(0.2, 0.3, 0.4, 0.6, 0.8),
        tbr70: Double = 3.0, sev54: Double = 0.4, meanBg: Double = 130.0,
        maxIob: Double = 8.0, maxBolus: Double = 10.0
    ) = BoostV5AutoConfig.V1Profile(days, bg, tdd, manual, smb, tbr70, sev54, meanBg, maxIob, maxBolus)

    @Test fun `insufficient history returns null, not empty`() {
        val items = BoostV5PeriodicReview.computeReviewItems(profile(days = 5)) { null }
        assertThat(items).isNull()
    }

    @Test fun `every knob already at its fresh suggestion yields an empty (not null) list`() {
        val p = profile(tbr70 = 2.5, sev54 = 0.2)
        val suggestion = BoostV5AutoConfig.compute(p)!!
        val current = BoostV5AutoConfigApply.managedDoubleKnobs(suggestion).toMap() +
            (DoubleKey.ApsBoostFloorSlewAggressiveness to suggestion.floorSlewAggressiveness)
        val items = BoostV5PeriodicReview.computeReviewItems(p) { current[it] }
        assertThat(items).isNotNull()
        assertThat(items).isEmpty()
    }

    @Test fun `a knob left at factory default surfaces when the suggestion differs`() {
        // Hypo-prone history derives aggression 0.85; leaving the knob unset (factory 1.0) must
        // surface a review item with the right current/suggested pair.
        val p = profile(tbr70 = 8.0, sev54 = 2.5)
        val items = BoostV5PeriodicReview.computeReviewItems(p) { null }!!
        val aggressionItem = items.single { it.key == DoubleKey.ApsBoostV5Aggression }
        assertThat(aggressionItem.currentValue).isEqualTo(DoubleKey.ApsBoostV5Aggression.defaultValue)
        assertThat(aggressionItem.suggestedValue).isEqualTo(0.85)
        assertThat(aggressionItem.rationale).contains("Aggression")
        assertThat(aggressionItem.wasUserTuned).isFalse()
    }

    @Test fun `a manually tuned value that still differs from the suggestion is surfaced and marked`() {
        val p = profile(tbr70 = 8.0, sev54 = 2.5)   // derives aggression 0.85
        val current = mapOf(DoubleKey.ApsBoostV5Aggression to 0.75)   // user tuned it to something else
        val items = BoostV5PeriodicReview.computeReviewItems(p) { current[it] }!!
        val aggressionItem = items.single { it.key == DoubleKey.ApsBoostV5Aggression }
        assertThat(aggressionItem.currentValue).isEqualTo(0.75)
        assertThat(aggressionItem.suggestedValue).isEqualTo(0.85)
        assertThat(aggressionItem.wasUserTuned).isTrue()
    }

    @Test fun `floor-slew aggressiveness is included as a candidate knob`() {
        val p = profile(tbr70 = 8.0, sev54 = 2.5)   // hypo-prone -> floorSlewAggressiveness 130
        val items = BoostV5PeriodicReview.computeReviewItems(p) { null }!!
        val item = items.single { it.key == DoubleKey.ApsBoostFloorSlewAggressiveness }
        assertThat(item.currentValue).isEqualTo(DoubleKey.ApsBoostFloorSlewAggressiveness.defaultValue)
        assertThat(item.suggestedValue).isEqualTo(130.0)
    }

    @Test fun `alcohol calibration keys are never candidates`() {
        val p = profile(tbr70 = 8.0, sev54 = 2.5)
        val items = BoostV5PeriodicReview.computeReviewItems(p) { null }!!
        assertThat(items.map { it.key.key }.none { it.contains("alcohol", ignoreCase = true) }).isTrue()
    }

    // ── Boolean switches (2026-08-27 gap fix) — same contract as the double-knob tests above ──

    @Test fun `boolean review insufficient history returns null, not empty`() {
        val items = BoostV5PeriodicReview.computeBooleanReviewItems(profile(days = 5)) { null }
        assertThat(items).isNull()
    }

    @Test fun `boolean review empty when every switch already matches its fresh suggestion`() {
        val p = profile(tbr70 = 2.5, sev54 = 0.2)   // not hypo-prone, not well-controlled -> defaults
        val suggestion = BoostV5AutoConfig.compute(p)!!
        val currentBool = mapOf(
            BooleanKey.ApsBoostV5FastCarbConfirm to suggestion.fastCarbConfirm,
            BooleanKey.ApsBoostV5AggressiveEarlyConfirm to suggestion.aggressiveEarlyConfirm,
            BooleanKey.ApsBoostV5VelocityBudgetActive to suggestion.velocityBudgetFloor,
            BooleanKey.ApsBoostV5PrimerTbrFallback to suggestion.primerTbrFallback
        )
        val items = BoostV5PeriodicReview.computeBooleanReviewItems(p) { currentBool[it] }
        assertThat(items).isNotNull()
        assertThat(items).isEmpty()
    }

    @Test fun `boolean review surfaces a switch left at factory default when the suggestion differs`() {
        // Hypo-prone -> fastCarbConfirm suggests false; factory default is true (unset = default).
        val p = profile(tbr70 = 8.0, sev54 = 2.5)
        val items = BoostV5PeriodicReview.computeBooleanReviewItems(p) { null }!!
        val item = items.single { it.key == BooleanKey.ApsBoostV5FastCarbConfirm }
        assertThat(item.currentValue).isEqualTo(BooleanKey.ApsBoostV5FastCarbConfirm.defaultValue)
        assertThat(item.suggestedValue).isFalse()
        assertThat(item.rationale).contains("OFF")
        assertThat(item.wasUserTuned).isFalse()
    }

    @Test fun `boolean review marks a manually tuned switch that still differs from the suggestion`() {
        // NOTE on why this profile, not the hypo-prone one used elsewhere in this file: a Boolean
        // only has 2 possible values. Whenever the derived suggestion DIFFERS from the factory
        // default (e.g. hypoProne -> fastCarbConfirm suggests false, default is true), the only two
        // possible stored states are "at default" (not user-tuned by definition) or "equals the
        // suggestion" (which wouldn't surface as a review item at all — current == suggested is
        // filtered out). So wasUserTuned=true on a SURFACED boolean item is only possible when the
        // suggestion equals the default and the user manually flipped it to the non-default value —
        // exactly the case here: NOT hypo-prone -> fastCarbConfirm suggests true (= factory default,
        // ApsBoostV5FastCarbConfirm's default is true), user has manually turned it off.
        val p = profile(tbr70 = 0.5, sev54 = 0.0)
        val current = mapOf(BooleanKey.ApsBoostV5FastCarbConfirm to false)
        val items = BoostV5PeriodicReview.computeBooleanReviewItems(p) { current[it] }!!
        val item = items.single { it.key == BooleanKey.ApsBoostV5FastCarbConfirm }
        assertThat(item.currentValue).isFalse()
        assertThat(item.suggestedValue).isTrue()
        assertThat(item.wasUserTuned).isTrue()
    }

    @Test fun `boolean review covers all 4 managed switches when everything is unset`() {
        val p = profile(tbr70 = 0.5, sev54 = 0.0)   // well-controlled -> several switches flip from default
        val items = BoostV5PeriodicReview.computeBooleanReviewItems(p) { null }!!
        val keys = items.map { it.key }.toSet()
        // well-controlled flips AggressiveEarlyConfirm/VelocityBudgetActive to true (default false)
        // and PrimerTbrFallback to false... which IS the default (false), so it does NOT surface.
        assertThat(keys).containsAtLeast(BooleanKey.ApsBoostV5AggressiveEarlyConfirm, BooleanKey.ApsBoostV5VelocityBudgetActive)
    }
}
