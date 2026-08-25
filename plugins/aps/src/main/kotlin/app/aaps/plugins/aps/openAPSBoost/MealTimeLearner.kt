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
 *   { "events": [ <utcMs>, <utcMs>, ... ] }      // rolling [WINDOW_DAYS] days
 *
 * Clustering: events are projected to local minute-of-day and greedily grouped into *modes*.
 * A mode is emitted only when a cluster has ≥ [MIN_SESSIONS] events spread over ≥ [MIN_DISTINCT_DAYS]
 * distinct days within ±[CLUSTER_HALF_WIDTH_MIN] — so a one-off late dinner can't manufacture a
 * window. The mode centre is the circular mean of its members.
 *
 * Safety posture: empty / corrupt history → no modes → [preMealWindow] returns null → the feature
 * never fires. The learner has ZERO dosing impact on its own; the plugin gates the actual target
 * change behind a user toggle (shadow-first).
 *
 * **Day-type split (2026-08-24, User-Fund + Daten-Gegencheck):** pooling all events regardless of
 * weekday diluted real patterns — a ~13:00 Sunday-lunch habit never reached [MIN_SESSIONS]/
 * [MIN_DISTINCT_DAYS] because it was outnumbered by unrelated weekday events, and a mixed
 * ~20:25-21:29 evening band conflated weekday dinners with Saturday/Sunday events that don't share
 * a cause. [modesByDayType] clusters WEEKDAY/SATURDAY/SUNDAY separately so each can reach trust on
 * its own. [modes] is kept unchanged (pooled, day-type-blind) for backward compatibility — existing
 * callers/tests are unaffected unless they opt into [preMealWindow]'s new `nowDayType` parameter.
 */
object MealTimeLearner {

    private const val WINDOW_DAYS = 60L
    private const val WINDOW_MS = WINDOW_DAYS * 24L * 60L * 60L * 1000L

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

    /** A manual MEAL tap within this many minutes of an already-recorded event is treated as an
     *  accidental repeat (fat-finger / "did that register?" double-tap), not a second real meal —
     *  see caller in OpenAPSBoostPlugin.kt. Real, distinct meals/snacks are realistically never this
     *  close together; without this, each repeat tap would train the learner as its own event. */
    const val MIN_TAP_GAP_MIN = 15

    private const val MINUTES_PER_DAY = 1440

    /** Rolling history of meal-commit timestamps (UTC ms). */
    data class History(var events: MutableList<Long> = mutableListOf()) {
        fun serialize(): String {
            val arr = JSONArray()
            for (e in events) arr.put(e)
            return JSONObject().put("events", arr).toString()
        }

        companion object {
            fun deserialize(raw: String): History {
                if (raw.isBlank()) return History()
                return try {
                    val arr = JSONObject(raw).optJSONArray("events") ?: JSONArray()
                    val list = mutableListOf<Long>()
                    for (i in 0 until arr.length()) list.add(arr.getLong(i))
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
    )

    /** Result of a positive [preMealWindow] match. */
    data class PreMealHit(
        val mode: MealMode,
        /** How many minutes before the meal centre we currently are. */
        val minutesBeforeMeal: Int,
    )

    /** Werktag vs. Samstag vs. Sonntag — see class doc "Day-type split". */
    enum class DayType { WEEKDAY, SATURDAY, SUNDAY }

    /**
     * Which [DayType] does [ms] (epoch millis, UTC) fall on in the local calendar defined by
     * [localOffsetMs]? Uses the SAME `(ms + localOffsetMs) / dayMs` day-index arithmetic as
     * [clusterModes]'s `distinctDays` counting, so "now" and historical events are always bucketed
     * consistently — deliberately not `LocalDate.now()`, which could disagree at a midnight edge.
     */
    fun dayTypeOf(ms: Long, localOffsetMs: Long): DayType {
        val dayIndex = (ms + localOffsetMs) / (24L * 60L * 60L * 1000L)
        return when (LocalDate.ofEpochDay(dayIndex).dayOfWeek) {
            DayOfWeek.SATURDAY -> DayType.SATURDAY
            DayOfWeek.SUNDAY   -> DayType.SUNDAY
            else               -> DayType.WEEKDAY
        }
    }

    /**
     * Record a fresh meal-commit at [tsMs]. Appends and trims to the rolling window.
     * Returns the updated history (caller persists).
     */
    fun record(h: History, tsMs: Long): History {
        val newEvents = h.events.toMutableList()
        newEvents.add(tsMs)
        val cutoff = tsMs - WINDOW_MS
        newEvents.removeAll { it < cutoff }
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
    private fun clusterModes(events: List<Long>, localOffsetMs: Long): List<MealMode> {
        if (events.size < MIN_SESSIONS) return emptyList()
        // (minuteOfDay, dayIndex) per event
        val pts = events.map { ms ->
            SleepHistoryTracker.msToMinOfDay(ms, localOffsetMs) to ((ms + localOffsetMs) / (24L * 60L * 60L * 1000L))
        }.toMutableList()

        val result = mutableListOf<MealMode>()
        while (pts.size >= MIN_SESSIONS) {
            // pick the event whose ±half-width neighbourhood holds the most events
            val best = pts.maxByOrNull { c -> pts.count { circularDistance(it.first, c.first) <= CLUSTER_HALF_WIDTH_MIN } }
                ?: break
            val cluster = pts.filter { circularDistance(it.first, best.first) <= CLUSTER_HALF_WIDTH_MIN }
            val distinctDays = cluster.map { it.second }.distinct().size
            if (cluster.size >= MIN_SESSIONS && distinctDays >= MIN_DISTINCT_DAYS) {
                val centre = SleepHistoryTracker.circularMean(cluster.map { it.first })
                if (centre != null) result.add(MealMode(centre, cluster.size, distinctDays))
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
        h.events.groupBy { dayTypeOf(it, localOffsetMs) }
            .mapValues { (_, events) -> clusterModes(events, localOffsetMs) }

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
     */
    fun preMealWindow(
        h: History,
        nowMin: Int,
        localOffsetMs: Long,
        leadMaxMin: Int,
        nowDayType: DayType? = null,
    ): PreMealHit? {
        val open = leadMaxMin.coerceAtLeast(PRE_MEAL_LEAD_MIN_FLOOR + PRE_MEAL_MIN_SPAN_MIN)
        val candidateModes = if (nowDayType != null) modesByDayType(h, localOffsetMs)[nowDayType].orEmpty()
                             else modes(h, localOffsetMs)
        for (mode in candidateModes) {
            // minutes from now forward to the meal centre, on the circle [0..1439]
            val ahead = ((mode.centreMin - nowMin) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
            if (ahead in PRE_MEAL_LEAD_MIN_FLOOR..open) {
                return PreMealHit(mode, ahead)
            }
        }
        return null
    }
}
