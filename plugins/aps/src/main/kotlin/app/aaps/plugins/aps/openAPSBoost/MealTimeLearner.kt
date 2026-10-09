package app.aaps.plugins.aps.openAPSBoost

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min

/**
 * MealTimeLearner — Boost V6.
 *
 * Learns a user's habitual meal times from the events V5 itself already calls meals (a fresh
 * CONFIRMED commit) and exposes a query the loop uses to fire an anticipatory low temp target
 * 45–60 min before a learned meal. Reuses [SleepHistoryTracker]'s `msToMinOfDay` / `circularMean`
 * so all time-of-day maths is midnight-wrap-safe and consistent with the sleep learner.
 *
 * **Why CONFIRMED events** (decided with Tim 2026-06-15): they are the real, already-computed
 * meal detector — no second detector to tune, and the histogram learns exactly what V5 treats
 * as a meal. The plugin records `decision.mealHypothesis == CONFIRMED && mealHypothesisAge == 0`.
 *
 * Storage shape (StringKey `ApsBoostMealTimeHistory`):
 *   { "events": [ {"ts": <utcMs>, "manual": <bool>}, ... ] }      // rolling [WINDOW_DAYS] days
 * The legacy shape (plain array of longs, no manual/auto distinction) is still readable — see
 * [History.deserialize]; every legacy entry counts as `manual=false` (auto-detected).
 *
 * Clustering: events are projected to local minute-of-day and greedily grouped into *modes*.
 * A mode is emitted only when a cluster has ≥ [MIN_SESSIONS] events spread over ≥ [MIN_DISTINCT_DAYS]
 * distinct days within ±[CLUSTER_HALF_WIDTH_MIN] — so a one-off late dinner can't manufacture a
 * window. The mode centre is the circular mean of its members.
 *
 * **Manual-confirmation trust gates** — a backtest found the blind, auto-detected trigger
 * (`learnedHit`: fires purely from time-pattern matching, no per-day confirmation) had a 45% false-alarm
 * rate, disproportionately while BG was already elevated for another reason (stacking risk). A manual
 * MEAL-button tap is inherently not blind, so the gates apply ONLY to the auto-detected path, never to a
 * live manual tap:
 *  - **Stage 1** ([MIN_MANUAL_CONFIRMATION_DAYS]): a mode may only drive the blind auto-fire once at least
 *    this many of its distinct days include a real manual tap.
 *  - **Stage 2** ([GraduationState]): once a time-slot has [GRADUATION_MIN_MANUAL_DAYS] confirmed days spread
 *    over [GRADUATION_MIN_DISTINCT_WEEKS] distinct weeks it "graduates" — trusted for
 *    [GRADUATION_VALIDITY_MS] without fresh Stage-1 confirmation in every rolling window. Persisted
 *    separately (it must survive the 60-day prune) and expires on its own. Graduation only WAIVES the
 *    confirmation count; it never manufactures a mode the auto-cluster no longer supports.
 *
 * Safety posture: empty / corrupt history → no modes → [preMealWindow] returns null → the feature
 * never fires. The learner has ZERO dosing impact on its own; the plugin gates the actual target
 * change behind a user toggle (shadow-first).
 *
 * **Day-type split:** pooling all events regardless of weekday dilutes real patterns — a ~13:00
 * Sunday-lunch habit never reaches [MIN_SESSIONS]/[MIN_DISTINCT_DAYS] when it is outnumbered by
 * unrelated weekday events, and a mixed evening band conflates weekday dinners with weekend events
 * that don't share a cause. [modesByDayType] clusters WEEKDAY/SATURDAY/SUNDAY separately so each
 * can reach trust on its own. [modes] is kept unchanged (pooled, day-type-blind); existing callers
 * are unaffected unless they pass [preMealWindow]'s `nowDayType` parameter.
 */
object MealTimeLearner {

    private const val WINDOW_DAYS = 60L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val WINDOW_MS = WINDOW_DAYS * DAY_MS

    /** A cluster must have at least this many events to be a trusted meal mode. */
    const val MIN_SESSIONS = 6

    /** …spread over at least this many distinct days (kills a single binge-day false mode). */
    const val MIN_DISTINCT_DAYS = 4

    /** Circular half-width (min) for grouping events into one mode (~07:50 ± 45 → breakfast). */
    const val CLUSTER_HALF_WIDTH_MIN = 45

    /** The pre-meal window always CLOSES this many minutes before the learned meal (Tim: 45–60 prior). */
    const val PRE_MEAL_LEAD_MIN_FLOOR = 45

    /** Guaranteed minimum window span (min), so a low leadMax setting can't yield a zero-width window. */
    const val PRE_MEAL_MIN_SPAN_MIN = 10

    /** Two events closer than this are one meal (a MEAL tap at the start, the V5 commit 20-60 min
     *  later, a double-tap): [record] keeps only the earliest, so the learner counts a meal once. */
    const val SAME_MEAL_GAP_MIN = 90

    /** Stage 1 trust gate: distinct days with a manual tap a mode needs before it may auto-fire. */
    const val MIN_MANUAL_CONFIRMATION_DAYS = 2

    /** Stage 2 graduation: confirmed days, spread over distinct weeks, valid for [GRADUATION_VALIDITY_MS]. */
    const val GRADUATION_MIN_MANUAL_DAYS = 8
    const val GRADUATION_MIN_DISTINCT_WEEKS = 6
    const val GRADUATION_VALIDITY_MS = 180L * DAY_MS

    private const val MINUTES_PER_DAY = 1440

    /** One recorded meal: [manual] = a MEAL-button tap was part of it (a V5 commit merged into a tap keeps it). */
    data class MealEvent(val tsMs: Long, val manual: Boolean)

    /** Rolling history of meal events. */
    data class History(var events: MutableList<MealEvent> = mutableListOf()) {
        fun serialize(): String {
            val arr = JSONArray()
            for (e in events) arr.put(JSONObject().put("ts", e.tsMs).put("manual", e.manual))
            return JSONObject().put("events", arr).toString()
        }

        companion object {
            fun deserialize(raw: String): History {
                if (raw.isBlank()) return History()
                return try {
                    val arr = JSONObject(raw).optJSONArray("events") ?: JSONArray()
                    val list = mutableListOf<MealEvent>()
                    for (i in 0 until arr.length()) {
                        val item = arr.get(i)
                        if (item is JSONObject) list.add(MealEvent(item.getLong("ts"), item.optBoolean("manual", false)))
                        else list.add(MealEvent(arr.getLong(i), false))   // legacy: plain long, no source tag
                    }
                    History(list)
                } catch (e: Exception) {
                    History()
                }
            }
        }
    }

    /** A learned habitual meal time. */
    data class MealMode(
        /** Circular-mean clock minute-of-day [0..1439]. */
        val centreMin: Int,
        /** Number of events in the cluster. */
        val eventCount: Int,
        /** Number of distinct local days contributing (the trust signal). */
        val distinctDays: Int,
        /** Distinct local days contributing that include >=1 manual tap — the Stage 1 trust signal. */
        val manualDistinctDays: Int,
    )

    /** Result of a positive [preMealWindow] match. */
    data class PreMealHit(
        val mode: MealMode,
        /** How many minutes before the meal centre we currently are. */
        val minutesBeforeMeal: Int,
    )

    /** Weekday vs. Saturday vs. Sunday — see class doc "Day-type split". */
    enum class DayType { WEEKDAY, SATURDAY, SUNDAY }

    /** Identity of "the same" meal-time slot across cluster re-formations: the centre drifts a few minutes,
     *  so it is bucketed to the nearest 30 min. Used only by [GraduationState]. */
    data class ModeKey(val dayType: DayType, val bucketMin: Int) {
        fun serialize(): String = "$dayType:$bucketMin"
    }

    fun modeKeyOf(dayType: DayType, centreMin: Int): ModeKey = ModeKey(dayType, (centreMin / 30) * 30)

    /** The mode (if any) in [modesForDayType] whose cluster would include an event at [minOfDay]. */
    fun modeNear(modesForDayType: List<MealMode>, minOfDay: Int): MealMode? =
        modesForDayType.firstOrNull { circularDistance(it.centreMin, minOfDay) <= CLUSTER_HALF_WIDTH_MIN }

    /**
     * Which [DayType] does [ms] (epoch millis, UTC) fall on in the local calendar defined by
     * [localOffsetMs]? Uses the SAME `(ms + localOffsetMs) / dayMs` day-index arithmetic as
     * [clusterModes]'s `distinctDays` counting, so "now" and historical events are always bucketed
     * consistently (not `LocalDate.now()`, which could disagree at a midnight edge).
     */
    fun dayTypeOf(ms: Long, localOffsetMs: Long): DayType {
        val dayIndex = (ms + localOffsetMs) / DAY_MS
        return when (LocalDate.ofEpochDay(dayIndex).dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.SATURDAY
            DayOfWeek.SUNDAY   -> DayType.SUNDAY
            else               -> DayType.WEEKDAY
        }
    }

    /**
     * Record a meal at [tsMs]. An event within [SAME_MEAL_GAP_MIN] of an existing one is the same meal:
     * the earliest timestamp wins, and the meal counts as manual if EITHER side was a tap (a V5 commit
     * arriving before the tap must not hide that the user confirmed it). Trims to the rolling window.
     * Returns the updated history (caller persists); an unchanged `events` list means nothing was recorded.
     */
    fun record(h: History, tsMs: Long, manual: Boolean): History {
        val newEvents = h.events.toMutableList()
        val gapMs = SAME_MEAL_GAP_MIN * 60_000L
        val sameMeal = newEvents.filter { abs(it.tsMs - tsMs) < gapMs }
        val merged = MealEvent(
            tsMs = minOf(tsMs, sameMeal.minOfOrNull { it.tsMs } ?: tsMs),
            manual = manual || sameMeal.any { it.manual },
        )
        if (sameMeal.size == 1 && sameMeal[0] == merged) return History(newEvents)
        newEvents.removeAll(sameMeal)
        newEvents.add(merged)
        val cutoff = tsMs - WINDOW_MS
        newEvents.removeAll { it.tsMs < cutoff }
        return History(newEvents)
    }

    /** Smaller of clockwise / anticlockwise distance between two minute-of-day values. */
    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return min(d, MINUTES_PER_DAY - d)
    }

    /**
     * Greedily cluster [events] into trusted meal modes (descending by size). O(n²) over events,
     * but n is tiny per day-type group (≤ ~3 meals/day × ~26 matching days within the 60-day window).
     * Shared core for [modes] (pooled) and [modesByDayType] (split) — identical algorithm, only the
     * input event list differs.
     */
    private fun clusterModes(events: List<MealEvent>, localOffsetMs: Long): List<MealMode> {
        if (events.size < MIN_SESSIONS) return emptyList()
        data class Pt(val minOfDay: Int, val dayIndex: Long, val manual: Boolean)
        val pts = events.map { e ->
            Pt(SleepHistoryTracker.msToMinOfDay(e.tsMs, localOffsetMs), (e.tsMs + localOffsetMs) / DAY_MS, e.manual)
        }.toMutableList()

        val result = mutableListOf<MealMode>()
        while (pts.size >= MIN_SESSIONS) {
            // pick the event whose ±half-width neighbourhood holds the most events
            val best = pts.maxByOrNull { c -> pts.count { circularDistance(it.minOfDay, c.minOfDay) <= CLUSTER_HALF_WIDTH_MIN } }
                ?: break
            val cluster = pts.filter { circularDistance(it.minOfDay, best.minOfDay) <= CLUSTER_HALF_WIDTH_MIN }
            val distinctDays = cluster.map { it.dayIndex }.distinct().size
            if (cluster.size >= MIN_SESSIONS && distinctDays >= MIN_DISTINCT_DAYS) {
                val centre = SleepHistoryTracker.circularMean(cluster.map { it.minOfDay })
                val manualDistinctDays = cluster.filter { it.manual }.map { it.dayIndex }.distinct().size
                if (centre != null) result.add(MealMode(centre, cluster.size, distinctDays, manualDistinctDays))
                pts.removeAll(cluster.toSet())
            } else {
                // the densest remaining cluster isn't trustworthy → no further modes will be either
                break
            }
        }
        return result
    }

    /**
     * Pooled modes across ALL events, ignoring day-of-week. Kept for backward compatibility (existing
     * tests / any other caller); [preMealWindow] no longer uses this once a [DayType] is supplied.
     */
    fun modes(h: History, localOffsetMs: Long): List<MealMode> = clusterModes(h.events, localOffsetMs)

    /**
     * Modes clustered separately per [DayType] — see class doc "Day-type split". A Sunday-only
     * pattern (e.g. ~13:00 lunch) is now judged purely against other Sundays, not diluted by
     * unrelated weekday events.
     */
    fun modesByDayType(h: History, localOffsetMs: Long): Map<DayType, List<MealMode>> =
        h.events.groupBy { dayTypeOf(it.tsMs, localOffsetMs) }
            .mapValues { (_, events) -> clusterModes(events, localOffsetMs) }

    /** One [ModeKey]'s Stage 2 progress/status. Day indices are pruned to the validity window, NOT to [History]'s 60 days. */
    data class GraduationRecord(val confirmedDayIndices: MutableSet<Long> = mutableSetOf(), var graduatedAtMs: Long = 0L) {
        val distinctWeeks: Int get() = confirmedDayIndices.map { it / 7L }.distinct().size
        val isGraduated: Boolean get() = graduatedAtMs > 0L
    }

    /** All [GraduationRecord]s keyed by [ModeKey.serialize]; persisted under its own StringKey. */
    data class GraduationState(val records: MutableMap<String, GraduationRecord> = mutableMapOf()) {
        fun serialize(): String {
            val obj = JSONObject()
            for ((key, rec) in records) {
                val days = JSONArray()
                for (d in rec.confirmedDayIndices) days.put(d)
                obj.put(key, JSONObject().put("days", days).put("graduatedAt", rec.graduatedAtMs))
            }
            return obj.toString()
        }

        companion object {
            fun deserialize(raw: String): GraduationState {
                if (raw.isBlank()) return GraduationState()
                return try {
                    val obj = JSONObject(raw)
                    val map = mutableMapOf<String, GraduationRecord>()
                    for (key in obj.keys()) {
                        val recObj = obj.getJSONObject(key)
                        val daysArr = recObj.optJSONArray("days") ?: JSONArray()
                        val days = mutableSetOf<Long>()
                        for (i in 0 until daysArr.length()) days.add(daysArr.getLong(i))
                        map[key] = GraduationRecord(days, recObj.optLong("graduatedAt", 0L))
                    }
                    GraduationState(map)
                } catch (e: Exception) {
                    GraduationState()
                }
            }
        }
    }

    /**
     * Record a manual confirmation on local day [dayIndex] for [key]. Pure — caller persists.
     * The thresholds are re-checked whenever the record is NOT currently graduated (never, or expired),
     * and the day set is pruned to the trailing [GRADUATION_VALIDITY_MS], so re-graduation after expiry
     * needs genuinely recent evidence rather than an ever-growing lifetime tally.
     */
    fun recordGraduationProgress(state: GraduationState, key: ModeKey, dayIndex: Long, nowMs: Long): GraduationState {
        val newRecords = state.records.toMutableMap()
        val existing = newRecords[key.serialize()] ?: GraduationRecord()
        val cutoffDayIndex = nowMs / DAY_MS - GRADUATION_VALIDITY_MS / DAY_MS
        val updatedDays = (existing.confirmedDayIndices + dayIndex).filter { it >= cutoffDayIndex }.toMutableSet()
        val currentlyGraduated = existing.isGraduated && nowMs - existing.graduatedAtMs < GRADUATION_VALIDITY_MS
        var graduatedAtMs = if (currentlyGraduated) existing.graduatedAtMs else 0L
        if (!currentlyGraduated) {
            val candidate = GraduationRecord(updatedDays)
            if (updatedDays.size >= GRADUATION_MIN_MANUAL_DAYS && candidate.distinctWeeks >= GRADUATION_MIN_DISTINCT_WEEKS) {
                graduatedAtMs = nowMs
            }
        }
        newRecords[key.serialize()] = GraduationRecord(updatedDays, graduatedAtMs)
        return GraduationState(newRecords)
    }

    /** Takes a cancelled tap's day back out of [key]'s progress. An already-granted graduation stays. */
    fun revokeGraduationDay(state: GraduationState, key: ModeKey, dayIndex: Long): GraduationState {
        val existing = state.records[key.serialize()] ?: return state
        if (dayIndex !in existing.confirmedDayIndices) return state
        val newRecords = state.records.toMutableMap()
        newRecords[key.serialize()] = GraduationRecord((existing.confirmedDayIndices - dayIndex).toMutableSet(), existing.graduatedAtMs)
        return GraduationState(newRecords)
    }

    /** Is [key] currently graduated (and not yet expired) as of [nowMs]? */
    fun isGraduated(state: GraduationState, key: ModeKey, nowMs: Long): Boolean {
        val rec = state.records[key.serialize()] ?: return false
        return rec.isGraduated && nowMs - rec.graduatedAtMs < GRADUATION_VALIDITY_MS
    }

    /**
     * [isGraduated] for the mode centred at [centreMin], also checking the neighbouring 30-min buckets
     * (centre ±15 min). A centre that drifts (or rounds, e.g. 780 → 779) across a bucket edge must not
     * lose a graduation that was credited under the adjacent bucket.
     */
    fun isGraduatedNear(state: GraduationState, dayType: DayType, centreMin: Int, nowMs: Long): Boolean =
        listOf(0, -15, 15).any { d ->
            isGraduated(state, modeKeyOf(dayType, ((centreMin + d) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY), nowMs)
        }

    /**
     * Is [nowMin] (local clock minute-of-day) inside the pre-meal lead window of any learned mode?
     *
     * The window for a mode centred at `c` is the arc `[c − openBefore, c − PRE_MEAL_LEAD_MIN_FLOOR]`
     * — it opens [openBefore] min before the meal and closes [PRE_MEAL_LEAD_MIN_FLOOR] min before
     * it (we stop adding pre-meal insulin once within the floor; V5's own detection takes the meal
     * from there). `openBefore` is the user's lead-minutes setting, but is held at least
     * [PRE_MEAL_MIN_SPAN_MIN] above the floor so a low setting can't collapse the window to nothing.
     * Returns the matched mode + how far before the meal we are, or null.
     *
     * @param leadMaxMin how far ahead the window opens (the user's "lead minutes" setting).
     * @param nowDayType when supplied, only modes clustered for THIS [DayType] (via [modesByDayType])
     *   are considered — the day-type-aware fix. Defaults to `null` = old pooled [modes] behaviour,
     *   so every existing caller (incl. all current tests) is unaffected unless it opts in.
     * @param graduationState/[nowMs]: when a day-type is supplied, a mode failing the Stage 1
     *   [MIN_MANUAL_CONFIRMATION_DAYS] gate is still let through if its [ModeKey] is graduated (Stage 2).
     *   The gate only applies with a day-type; the pooled path stays ungated.
     */
    fun preMealWindow(
        h: History,
        nowMin: Int,
        localOffsetMs: Long,
        leadMaxMin: Int,
        nowDayType: DayType? = null,
        graduationState: GraduationState? = null,
        nowMs: Long = 0L,
    ): PreMealHit? {
        val open = leadMaxMin.coerceAtLeast(PRE_MEAL_LEAD_MIN_FLOOR + PRE_MEAL_MIN_SPAN_MIN)
        val candidateModes = if (nowDayType != null) modesByDayType(h, localOffsetMs)[nowDayType].orEmpty()
                             else modes(h, localOffsetMs)
        for (mode in candidateModes) {
            // minutes from now forward to the meal centre, on the circle [0..1439]
            val ahead = ((mode.centreMin - nowMin) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
            if (ahead in PRE_MEAL_LEAD_MIN_FLOOR..open) {
                if (nowDayType != null && mode.manualDistinctDays < MIN_MANUAL_CONFIRMATION_DAYS) {
                    val graduated = graduationState != null && isGraduatedNear(graduationState, nowDayType, mode.centreMin, nowMs)
                    if (!graduated) continue
                }
                return PreMealHit(mode, ahead)
            }
        }
        return null
    }
}
