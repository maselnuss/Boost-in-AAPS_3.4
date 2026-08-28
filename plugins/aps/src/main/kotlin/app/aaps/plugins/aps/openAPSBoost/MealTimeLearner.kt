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
 * Legacy shape (pre-2026-08-28, no manual/auto distinction) is still readable — see
 * [History.deserialize] — every legacy entry is treated as `manual=false` (all pre-existing
 * history predates the manual-tap-recording feature, so it can only be auto-CONFIRMED).
 *
 * Clustering: events are projected to local minute-of-day and greedily grouped into *modes*.
 * A mode is emitted only when a cluster has ≥ [MIN_SESSIONS] events spread over ≥ [MIN_DISTINCT_DAYS]
 * distinct days within ±[CLUSTER_HALF_WIDTH_MIN] — so a one-off late dinner can't manufacture a
 * window. The mode centre is the circular mean of its members.
 *
 * **Manual-confirmation trust gates (Konzept 6.2, 2026-08-28)** — added after a review found the
 * blind, auto-detected trigger path (`learnedHit`, fires purely from time-pattern matching, no
 * per-day confirmation) had a 45% false-alarm rate in a 50-day backtest, disproportionately firing
 * while BG was already elevated for an unrelated reason (stacking risk, not just wasted insulin —
 * see `Claude_boost_extension_ideas.md` §7.4). A manual MEAL-button tap is inherently NOT blind (it
 * only fires when the user says so), so it was never subject to that finding — these gates apply
 * ONLY to the auto-detected `learnedHit` path, never to a live manual tap:
 *  - **Stage 1** ([MIN_MANUAL_CONFIRMATION_DAYS]): a mode may only be used for the blind auto-fire
 *    once at least this many of its distinct days include a real manual confirmation — a purely
 *    auto-detected pattern (possibly noise, possibly real but never human-verified) can't drive the
 *    blind trigger on its own.
 *  - **Stage 2** ([GraduationState]/[GRADUATION_MIN_MANUAL_DAYS]/[GRADUATION_MIN_DISTINCT_WEEKS]):
 *    once a time-slot has accumulated enough manual confirmations SPREAD OVER ENOUGH DISTINCT WEEKS
 *    (not just a raw count reachable in a fortnight), it "graduates" — trusted for
 *    [GRADUATION_VALIDITY_MS] without needing fresh Stage-1-level confirmation in every rolling
 *    60-day window. Persisted separately from [History] (must survive its 60-day prune) and expires
 *    on its own, so a graduated slot can't stay trusted forever off a burst of taps from months ago.
 *    Graduation only WAIVES the confirmation-count requirement — it can never manufacture a mode
 *    that the underlying auto-cluster (MIN_SESSIONS/MIN_DISTINCT_DAYS) doesn't itself support any
 *    more, so a genuinely changed routine still ages the feature back out on its own.
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
 * Deliberately NOT split further into 7 individual weekdays (2026-08-28, discussed with user): each
 * bucket's usable sample in the 60-day window would shrink ~5x for what's currently pooled as
 * WEEKDAY (~43 occurrences → ~8-9 per individual day, the same small budget SATURDAY/SUNDAY already
 * have), multiplying the time needed to reach trust for no benefit for the common case (a job-driven
 * weekday routine rarely differs Mon-Fri) — not worth the added tap burden without a concrete,
 * stated day-specific outlier to justify it.
 */
object MealTimeLearner {

    private const val WINDOW_DAYS = 60L
    private const val WINDOW_MS = WINDOW_DAYS * 24L * 60L * 60L * 1000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

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

    /** Stage 1 trust gate (Konzept 6.2) — see class KDoc "Manual-confirmation trust gates".
     *  2026-08-28: raised 2 → 4 (user decision, not backtest-calibrated like most constants in this
     *  file). Deliberately set equal to [MIN_DISTINCT_DAYS]: the blind trigger should require at
     *  least as much manual verification as it took to recognize the pattern automatically in the
     *  first place — a mode barely at the MIN_DISTINCT_DAYS floor now needs ALL of its founding days
     *  manually confirmed, not just half. */
    const val MIN_MANUAL_CONFIRMATION_DAYS = 4

    /** Stage 2 graduation gates (Konzept 6.2) — see class KDoc. */
    const val GRADUATION_MIN_MANUAL_DAYS = 8
    const val GRADUATION_MIN_DISTINCT_WEEKS = 6
    const val GRADUATION_VALIDITY_MS = 180L * DAY_MS

    private const val MINUTES_PER_DAY = 1440

    /** One recorded meal-time event. [manual] = true for a MEAL-button tap, false for an
     *  auto-detected V5 CONFIRMED commit — see class KDoc "Manual-confirmation trust gates". */
    data class MealEvent(val tsMs: Long, val manual: Boolean)

    /** Rolling history of meal-commit events. */
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
                        if (item is JSONObject) {
                            list.add(MealEvent(item.getLong("ts"), item.optBoolean("manual", false)))
                        } else {
                            // Legacy format (pre-2026-08-28): plain array of longs, no source tag.
                            list.add(MealEvent(arr.getLong(i), false))
                        }
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
        /** Number of distinct local days contributing that include ≥1 manual confirmation — the
         *  Stage 1 trust signal (Konzept 6.2). */
        val manualDistinctDays: Int,
    )

    /** Result of a positive [preMealWindow] match. */
    data class PreMealHit(
        val mode: MealMode,
        /** How many minutes before the meal centre we currently are. */
        val minutesBeforeMeal: Int,
    )

    /** Werktag vs. Samstag vs. Sonntag — see class doc "Day-type split". */
    enum class DayType { WEEKDAY, SATURDAY, SUNDAY }

    /** Stable key identifying "the same" meal-time slot across cluster re-formations — a cluster's
     *  circular-mean centre can drift a few minutes cycle to cycle, so identity is bucketed to the
     *  nearest 30 min rather than tracked by exact centre. Used only by [GraduationState]. */
    data class ModeKey(val dayType: DayType, val bucketMin: Int) {
        fun serialize(): String = "$dayType:$bucketMin"
    }

    fun modeKeyOf(dayType: DayType, centreMin: Int): ModeKey = ModeKey(dayType, (centreMin / 30) * 30)

    /** The mode (if any) in [modesForDayType] whose cluster would include an event at [minOfDay] —
     *  used by the plugin to attribute a fresh manual tap to its mode for Stage 2 graduation
     *  tracking (Konzept 6.2), right after [record]ing it. */
    fun modeNear(modesForDayType: List<MealMode>, minOfDay: Int): MealMode? =
        modesForDayType.firstOrNull { circularDistance(it.centreMin, minOfDay) <= CLUSTER_HALF_WIDTH_MIN }

    /**
     * Which [DayType] does [ms] (epoch millis, UTC) fall on in the local calendar defined by
     * [localOffsetMs]? Uses the SAME `(ms + localOffsetMs) / dayMs` day-index arithmetic as
     * [clusterModes]'s `distinctDays` counting, so "now" and historical events are always bucketed
     * consistently — deliberately not `LocalDate.now()`, which could disagree at a midnight edge.
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
     * Record a fresh meal-commit at [tsMs]. Appends and trims to the rolling window.
     * Returns the updated history (caller persists).
     *
     * @param manual true for a MEAL-button tap, false for an auto-detected CONFIRMED commit.
     */
    fun record(h: History, tsMs: Long, manual: Boolean): History {
        val newEvents = h.events.toMutableList()
        newEvents.add(MealEvent(tsMs, manual))
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
        // (minuteOfDay, dayIndex, manual) per event
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

    /** One [ModeKey]'s Stage 2 progress/status. Lifetime day-index set, deliberately NOT pruned by
     *  [History]'s 60-day window — see class KDoc "Stage 2". */
    data class GraduationRecord(val confirmedDayIndices: MutableSet<Long> = mutableSetOf(), var graduatedAtMs: Long = 0L) {
        val distinctWeeks: Int get() = confirmedDayIndices.map { it / 7L }.distinct().size
        val isGraduated: Boolean get() = graduatedAtMs > 0L
    }

    /** All [GraduationRecord]s, keyed by [ModeKey.serialize]. Persisted under a SEPARATE StringKey
     *  from [History] (survives [History]'s 60-day prune by design). */
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
     * Record a manual confirmation's contribution to Stage 2 graduation progress for [key] at
     * [dayIndex] (local calendar day index, same arithmetic as [clusterModes]). Pure function —
     * caller persists the returned state.
     *
     * 2026-08-28 fix (found on re-check): the first version only ever evaluated the graduation
     * thresholds while `graduatedAtMs == 0L` — once set, it was NEVER re-evaluated, so a mode that
     * graduated once and later expired ([isGraduated] returning false again) could never re-graduate
     * even with ongoing fresh confirmations, directly contradicting the "re-earn trust periodically"
     * intent this whole mechanism exists for. Fixed by re-checking whenever the record is NOT
     * currently graduated (never graduated, OR previously graduated and now expired) — and by
     * pruning [confirmedDayIndices] to the trailing [GRADUATION_VALIDITY_MS] window on every call,
     * so re-graduation needs genuinely RECENT evidence, not an ever-growing lifetime tally that would
     * instantly re-qualify off ancient confirmations the moment any new one arrives.
     */
    fun recordGraduationProgress(state: GraduationState, key: ModeKey, dayIndex: Long, nowMs: Long): GraduationState {
        val newRecords = state.records.toMutableMap()
        val existing = newRecords[key.serialize()] ?: GraduationRecord()
        val nowDayIndex = nowMs / DAY_MS
        val cutoffDayIndex = nowDayIndex - GRADUATION_VALIDITY_MS / DAY_MS
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

    /** Is [key] currently graduated (and not yet expired) as of [nowMs]? */
    fun isGraduated(state: GraduationState, key: ModeKey, nowMs: Long): Boolean {
        val rec = state.records[key.serialize()] ?: return false
        return rec.isGraduated && nowMs - rec.graduatedAtMs < GRADUATION_VALIDITY_MS
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
     * @param graduationState/[nowMs] (Konzept 6.2): when supplied together, a mode failing the
     *   Stage 1 [MIN_MANUAL_CONFIRMATION_DAYS] gate is still allowed through if its [ModeKey] is
     *   graduated (Stage 2). Both default to `null`/`0` = old behaviour (Stage 1 gate ungated),
     *   so existing callers/tests are unaffected unless they opt in.
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
                // Stage 1/2 trust gate (Konzept 6.2) — only applied when the caller opted in AND a
                // day-type was supplied (this gate only makes sense per-mode-per-day-type; the
                // pooled `modes` path predates it and is left ungated for backward compatibility).
                if (nowDayType != null && mode.manualDistinctDays < MIN_MANUAL_CONFIRMATION_DAYS) {
                    val graduated = graduationState != null && isGraduated(graduationState, modeKeyOf(nowDayType, mode.centreMin), nowMs)
                    if (!graduated) continue
                }
                return PreMealHit(mode, ahead)
            }
        }
        return null
    }
}
