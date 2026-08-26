package app.aaps.plugins.aps.openAPSBoost

/**
 * BoostFloorSlewShadow — Konzept 1 (2026-08-26), SHADOW ONLY. ISF-floor + slew-limiter, rolling/
 * dynamic instead of a one-off statically calibrated value (unlike a fixed replay-derived number,
 * this self-adjusts as new data arrives — same architectural pattern as DynISF's own TDD24/TDD7
 * ratio and IsfShadow's EMA, applied here to the floor/slew question instead).
 *
 * Pure, stateless functions only — no plugin state, no DB/preferences access, nothing
 * Android-specific. The plugin owns all I/O (fetching APSResult history) and passes already-
 * extracted samples in; this object only turns those into a suggestion, so every rule here is
 * directly unit-testable in isolation. Never touches rT.units/rT.rate/oapsProfile itself — see
 * OpenAPSBoostPlugin.kt's Shadow call site.
 *
 * Background/evidence (see Claude_boost_extension_ideas.md §3.6/3.7 for the full replay): a
 * completed offline replay (22.08., 116 real excursions, 37 days) found Slew 1-2%/cycle as the
 * best single candidate (97% comedown coverage, +3.8% peak cost) and Floor 40% as a second, nearly
 * free candidate (66% coverage, +0.1% peak cost) — Floor ≥60% was found to cost more than it gains.
 * This shadow does NOT re-run that full peak-cost-tradeoff analysis on-device (a much bigger,
 * separate undertaking); it computes a simpler ROLLING estimate from recent ISF-compression
 * behaviour, logged for comparison against those known-good reference values over the coming weeks.
 */
object BoostFloorSlewShadow {

    /** One past cycle's ISF compression: variableSens / profile sens (the dosing ISF actually used,
     *  as a fraction of the configured profile ISF — 1.0 = no compression, 0.4 = compressed to 40%
     *  of profile). */
    data class CycleSample(val timestampMs: Long, val compressionRatio: Double)

    /** Never suggest outside this range, regardless of what the rolling window computes — matches
     *  the replay's own findings (below 20% floor is observed to do essentially nothing; above 60%
     *  peak-cost grows faster than comedown-benefit). Fixed in code, not user-adjustable — these are
     *  safety rails, not a preference. */
    const val FLOOR_MIN_PCT = 20
    const val FLOOR_MAX_PCT = 60

    /** Only the 1-10%/cycle range was actually tested in the replay; slew below 1% is unexplored, so
     *  the rolling estimate is never allowed to suggest tighter than that. */
    const val SLEW_MIN_PCT_PER_CYCLE = 1.0
    const val SLEW_MAX_PCT_PER_CYCLE = 10.0

    /** Below this many samples in the long window, a percentile estimate is too noisy to be
     *  meaningful (e.g. right after the feature is first enabled) — return null rather than a wild
     *  early guess. ~20 cycles = ~100min at the normal 5-min cadence, a low bar deliberately: this is
     *  a rolling estimate that keeps refining, not a one-shot calibration that needs to be "right"
     *  from sample 1. */
    const val MIN_SAMPLES_FOR_ESTIMATE = 20

    /** Percentile of the long window's compression ratios used as the floor candidate — a real floor
     *  needs to catch the WORST compressions, not the typical ones, so a low percentile (not the
     *  median) is deliberate. */
    private const val FLOOR_PERCENTILE = 0.10

    data class Suggestion(
        val floorPct: Int,
        val slewPctPerCycle: Double,
        val shortWindowSamples: Int,
        val longWindowSamples: Int
    )

    /**
     * @param shortWindow Recent samples (e.g. trailing ~2 days) — captures current volatility for
     *                    the slew estimate.
     * @param longWindow  Older samples too (e.g. trailing ~14 days) — a stabler baseline for the
     *                    floor estimate; naturally ages out stale data (e.g. from before an insulin
     *                    switch) without any manual "recalibrate now" step.
     * @param aggressivenessPct User-set scale (default 100 = the raw computed values unchanged);
     *                    see DoubleKey.ApsBoostFloorSlewAggressiveness. Higher = suggests a stronger
     *                    response than the raw rolling estimate — HIGHER floor % (blocks compression
     *                    sooner) and LOWER slew % (tighter rate limit); these two move in opposite
     *                    numeric directions because that's what "stricter" means for each (see §3.6:
     *                    a 70% floor blocks 100% of cases, a 30% floor only 18%; conversely 1%/cycle
     *                    slew is a tighter brake than 5%/cycle).
     * @return null if [longWindow] doesn't have enough samples yet for a meaningful estimate.
     */
    fun computeSuggestion(
        shortWindow: List<CycleSample>,
        longWindow: List<CycleSample>,
        aggressivenessPct: Double
    ): Suggestion? {
        if (longWindow.size < MIN_SAMPLES_FOR_ESTIMATE) return null

        val aggressiveness = (aggressivenessPct / 100.0).coerceIn(0.1, 5.0)

        val sortedRatios = longWindow.map { it.compressionRatio }.sorted()
        val percentileIndex = (sortedRatios.size * FLOOR_PERCENTILE).toInt().coerceIn(0, sortedRatios.size - 1)
        val worstCaseCompressionFraction = sortedRatios[percentileIndex]
        // For the floor, HIGHER % is stricter (a floor of 70% blocks compression in 100% of the
        // replay's 116 cases; 30% only in 18% — see §3.6). So higher aggressiveness must scale the
        // raw observed worst case UP (multiply), not down — dividing here (as an earlier version of
        // this function did) would make more "aggressive" settings produce a WEAKER floor, backwards.
        val rawFloorPct = worstCaseCompressionFraction * 100.0 * aggressiveness
        val floorPct = rawFloorPct.toInt().coerceIn(FLOOR_MIN_PCT, FLOOR_MAX_PCT)

        val byTime = shortWindow.sortedBy { it.timestampMs }
        val cycleToCycleDeltasPct = (1 until byTime.size).map { i ->
            kotlin.math.abs(byTime[i].compressionRatio - byTime[i - 1].compressionRatio) * 100.0
        }
        val medianDeltaPct = if (cycleToCycleDeltasPct.isEmpty()) {
            SLEW_MAX_PCT_PER_CYCLE
        } else {
            cycleToCycleDeltasPct.sorted()[cycleToCycleDeltasPct.size / 2]
        }
        // Higher aggressiveness -> tighter (lower) slew limit than the raw observed typical delta.
        val slewPctPerCycle = (medianDeltaPct / aggressiveness).coerceIn(SLEW_MIN_PCT_PER_CYCLE, SLEW_MAX_PCT_PER_CYCLE)

        return Suggestion(
            floorPct = floorPct,
            slewPctPerCycle = slewPctPerCycle,
            shortWindowSamples = shortWindow.size,
            longWindowSamples = longWindow.size
        )
    }
}
