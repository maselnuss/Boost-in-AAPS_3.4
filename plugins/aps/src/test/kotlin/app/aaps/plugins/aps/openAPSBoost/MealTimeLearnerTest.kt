package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * V6 MealTimeLearner — clustering of V5-CONFIRMED meal commits into habitual meal modes, and the
 * pre-meal lead-window query that drives the anticipatory low target. Pure functions, tested
 * directly with offset = 0 so an event's UTC ms maps 1:1 to its local minute-of-day.
 *
 * 2026-08-28 — updated for the manual/auto event-source distinction and the Stage 1/2 trust gates
 * (Konzept 6.2). Each value hand-verified before writing the assertion, same discipline as
 * BoostFloorSlewShadowTest/BoostOvershootGuardShadowTest.
 */
class MealTimeLearnerTest {

    private val offset = 0L
    private val dayMs = 24L * 60L * 60L * 1000L

    /** Event at `minute` of local day `day`, with offset 0 → msToMinOfDay == minute, dayIndex == day. */
    private fun ev(day: Long, minute: Int, manual: Boolean = false): MealTimeLearner.MealEvent =
        MealTimeLearner.MealEvent(day * dayMs + minute * 60_000L, manual)

    private fun tsOf(day: Long, minute: Int): Long = day * dayMs + minute * 60_000L

    private fun historyOf(vararg events: MealTimeLearner.MealEvent) =
        MealTimeLearner.History(events.toMutableList())

    // ─── empty / corrupt ──────────────────────────────────────────────────────

    @Test fun `empty history yields no modes and no window`() {
        val h = MealTimeLearner.History()
        assertThat(MealTimeLearner.modes(h, offset)).isEmpty()
        assertThat(MealTimeLearner.preMealWindow(h, nowMin = 420, localOffsetMs = offset, leadMaxMin = 60)).isNull()
    }

    @Test fun `corrupt blob deserializes to empty history`() {
        assertThat(MealTimeLearner.History.deserialize("not json {{").events).isEmpty()
        assertThat(MealTimeLearner.History.deserialize("").events).isEmpty()
    }

    @Test fun `serialize round-trips with the manual flag preserved`() {
        val h = historyOf(ev(1, 480, manual = true), ev(2, 485, manual = false))
        val back = MealTimeLearner.History.deserialize(h.serialize())
        assertThat(back.events).containsExactly(ev(1, 480, manual = true), ev(2, 485, manual = false)).inOrder()
    }

    @Test fun `legacy plain-long array deserializes as all-manual-false`() {
        // Pre-2026-08-28 storage shape: {"events":[<ms>,<ms>]} — no source tag.
        val legacy = "{\"events\":[" + tsOf(1, 480) + "," + tsOf(2, 485) + "]}"
        val h = MealTimeLearner.History.deserialize(legacy)
        assertThat(h.events).containsExactly(ev(1, 480, manual = false), ev(2, 485, manual = false)).inOrder()
    }

    // ─── confidence thresholds ──────────────────────────────────────────────────

    @Test fun `fewer than MIN_SESSIONS events yields no mode`() {
        // 5 breakfast events (< 6) → not trusted
        val h = historyOf(ev(1, 480), ev(2, 481), ev(3, 479), ev(4, 482), ev(5, 478))
        assertThat(MealTimeLearner.modes(h, offset)).isEmpty()
    }

    @Test fun `enough events but too few distinct days yields no mode`() {
        // 6 events but all on 3 days (< MIN_DISTINCT_DAYS = 4) → a binge day can't make a mode
        val h = historyOf(
            ev(1, 480), ev(1, 485), ev(2, 478), ev(2, 482), ev(3, 479), ev(3, 481)
        )
        assertThat(MealTimeLearner.modes(h, offset)).isEmpty()
    }

    @Test fun `6 events over 6 days forms one trusted mode near the cluster centre`() {
        val h = historyOf(
            ev(1, 478), ev(2, 482), ev(3, 480), ev(4, 479), ev(5, 481), ev(6, 480)
        )
        val modes = MealTimeLearner.modes(h, offset)
        assertThat(modes).hasSize(1)
        assertThat(modes[0].centreMin).isWithin(2).of(480)   // ~08:00
        assertThat(modes[0].distinctDays).isEqualTo(6)
        assertThat(modes[0].manualDistinctDays).isEqualTo(0)   // all auto in this fixture
    }

    @Test fun `two separated clusters form two modes`() {
        val days = (1L..6L)
        val breakfast = days.map { ev(it, 480) }            // 08:00
        val dinner = days.map { ev(it, 1140) }              // 19:00
        val h = MealTimeLearner.History((breakfast + dinner).toMutableList())
        val centres = MealTimeLearner.modes(h, offset).map { it.centreMin }.sorted()
        assertThat(centres).hasSize(2)
        assertThat(centres[0]).isWithin(2).of(480)
        assertThat(centres[1]).isWithin(2).of(1140)
    }

    // ─── manual-distinct-days counting (Konzept 6.2) ─────────────────────────────

    @Test fun `manualDistinctDays counts only distinct days with a manual tap, not raw manual events`() {
        // 6 days, 2 of them (day 1 and day 2) have a manual tap. Day 1 has TWO manual taps — must
        // still count as ONE distinct day, not two.
        val h = historyOf(
            ev(1, 479, manual = true), ev(1, 480, manual = true),
            ev(2, 480, manual = true),
            ev(3, 480), ev(4, 480), ev(5, 480), ev(6, 480),
        )
        val modes = MealTimeLearner.modes(h, offset)
        assertThat(modes).hasSize(1)
        assertThat(modes[0].distinctDays).isEqualTo(6)
        assertThat(modes[0].manualDistinctDays).isEqualTo(2)
    }

    // ─── circular wrap ───────────────────────────────────────────────────────────

    @Test fun `cluster straddling midnight is centred near zero not noon`() {
        // events at 23:50, 23:55, 23:58, 00:05, 00:08, 00:10 across 6 days
        val mins = listOf(1430, 1435, 1438, 5, 8, 10)
        val h = MealTimeLearner.History(mins.mapIndexed { i, m -> ev((i + 1).toLong(), m) }.toMutableList())
        val modes = MealTimeLearner.modes(h, offset)
        assertThat(modes).hasSize(1)
        // circular mean should be within a few minutes of midnight (0 / 1440), NOT ~720
        val c = modes[0].centreMin
        assertThat(c < 30 || c > 1410).isTrue()
    }

    // ─── pre-meal lead window edges ───────────────────────────────────────────────

    private fun breakfastHistory() = MealTimeLearner.History(
        (1L..6L).map { ev(it, 480) }.toMutableList()    // mode at 08:00
    )

    @Test fun `window opens leadMax before and closes 45 before the meal`() {
        // mode centre ≈ 08:00 (480, may round to 479). leadMax = 60 → window covers ~[45, 60] min
        // before the meal. Use interior points (margin ≥ 2 min) to stay robust to ±1 centre rounding.
        // No nowDayType supplied here -> pooled/backward-compat path -> Stage 1 gate not applied.
        val h = breakfastHistory()
        assertThat(MealTimeLearner.preMealWindow(h, 425, offset, 60)).isNotNull()   // ~54 min before — inside
        assertThat(MealTimeLearner.preMealWindow(h, 432, offset, 60)).isNotNull()   // ~47 min before — inside
        assertThat(MealTimeLearner.preMealWindow(h, 470, offset, 60)).isNull()      // ~10 min before — too close
        assertThat(MealTimeLearner.preMealWindow(h, 414, offset, 60)).isNull()      // ~65 min before — too early
        assertThat(MealTimeLearner.preMealWindow(h, 480, offset, 60)).isNull()      // at the meal — past window
    }

    @Test fun `minutesBeforeMeal is reported`() {
        val hit = MealTimeLearner.preMealWindow(breakfastHistory(), 425, offset, 60)
        assertThat(hit).isNotNull()
        // centre ≈ 480 (may round to 479) → 54..55 min before
        assertThat(hit!!.minutesBeforeMeal).isIn(54..55)
    }

    @Test fun `low leadMax setting still yields a usable window (min-span guaranteed)`() {
        // leadMax = 30 → open clamped to floor(45)+min-span(10) = 55 → window covers ~[45,55] before.
        val h = breakfastHistory()
        assertThat(MealTimeLearner.preMealWindow(h, 427, offset, 30)).isNotNull()   // ~52 min before — inside
        assertThat(MealTimeLearner.preMealWindow(h, 420, offset, 30)).isNull()      // ~59 min before — before open
        assertThat(MealTimeLearner.preMealWindow(h, 470, offset, 30)).isNull()      // ~10 min before — too close
    }

    // ─── record + prune ───────────────────────────────────────────────────────────

    @Test fun `record appends and prunes events older than the 60-day window, preserving manual flag`() {
        val now = 100L * dayMs
        val old = MealTimeLearner.MealEvent(now - 61L * dayMs, false)       // outside 60-day window
        val recent = MealTimeLearner.MealEvent(now - 3L * dayMs, true)
        var h = MealTimeLearner.History(mutableListOf(old, recent))
        h = MealTimeLearner.record(h, now, manual = true)
        assertThat(h.events).containsExactly(recent, MealTimeLearner.MealEvent(now, true)).inOrder()   // old pruned, new kept
    }

    // ─── day-type split (2026-08-24) ──────────────────────────────────────────────
    // Epoch day 0 = 1970-01-01 = Thursday, so with offset 0 the `ev(day, ...)` helper's `day` is
    // also the real weekday: day % 7 == 3 → Sunday, day % 7 == 2 → Saturday (verified against
    // java.time.LocalDate.ofEpochDay so the test data is trustworthy, not just self-consistent).

    @Test fun `dayTypeOf recognises real Sunday, Saturday and weekday epoch days`() {
        assertThat(MealTimeLearner.dayTypeOf(tsOf(3, 0), offset)).isEqualTo(MealTimeLearner.DayType.SUNDAY)
        assertThat(MealTimeLearner.dayTypeOf(tsOf(10, 0), offset)).isEqualTo(MealTimeLearner.DayType.SUNDAY)
        assertThat(MealTimeLearner.dayTypeOf(tsOf(2, 0), offset)).isEqualTo(MealTimeLearner.DayType.SATURDAY)
        assertThat(MealTimeLearner.dayTypeOf(tsOf(9, 0), offset)).isEqualTo(MealTimeLearner.DayType.SATURDAY)
        assertThat(MealTimeLearner.dayTypeOf(tsOf(4, 0), offset)).isEqualTo(MealTimeLearner.DayType.WEEKDAY)   // Monday
        assertThat(MealTimeLearner.dayTypeOf(tsOf(1, 0), offset)).isEqualTo(MealTimeLearner.DayType.WEEKDAY)   // Friday
    }

    @Test fun `pooled modes blend a Sunday and a nearby Saturday pattern into one mode`() {
        // 6 real Sundays at 13:00 (780) + 6 real Saturdays at 13:10 (790, within the 45-min half-width)
        val sundays = listOf(3L, 10L, 17L, 24L, 31L, 38L).map { ev(it, 780) }
        val saturdays = listOf(2L, 9L, 16L, 23L, 30L, 37L).map { ev(it, 790) }
        val h = MealTimeLearner.History((sundays + saturdays).toMutableList())

        val pooled = MealTimeLearner.modes(h, offset)
        assertThat(pooled).hasSize(1)                       // merged into ONE blended mode
        assertThat(pooled[0].eventCount).isEqualTo(12)
        assertThat(pooled[0].distinctDays).isEqualTo(12)
        assertThat(pooled[0].centreMin).isWithin(2).of(785)  // blended centre, neither pure Sunday nor Saturday
    }

    @Test fun `modesByDayType keeps the same Sunday and Saturday pattern separate and precise`() {
        val sundays = listOf(3L, 10L, 17L, 24L, 31L, 38L).map { ev(it, 780) }
        val saturdays = listOf(2L, 9L, 16L, 23L, 30L, 37L).map { ev(it, 790) }
        val h = MealTimeLearner.History((sundays + saturdays).toMutableList())

        val byDayType = MealTimeLearner.modesByDayType(h, offset)
        val sundayModes = byDayType[MealTimeLearner.DayType.SUNDAY].orEmpty()
        val saturdayModes = byDayType[MealTimeLearner.DayType.SATURDAY].orEmpty()

        assertThat(sundayModes).hasSize(1)
        assertThat(sundayModes[0].eventCount).isEqualTo(6)
        assertThat(sundayModes[0].distinctDays).isEqualTo(6)
        assertThat(sundayModes[0].centreMin).isWithin(2).of(780)   // precise, not blended with Saturday

        assertThat(saturdayModes).hasSize(1)
        assertThat(saturdayModes[0].centreMin).isWithin(2).of(790)

        assertThat(byDayType[MealTimeLearner.DayType.WEEKDAY].orEmpty()).isEmpty()   // no weekday events at all
    }

    @Test fun `preMealWindow with nowDayType only fires for that day-type own mode, once Stage 1 confirmed`() {
        // 4 of the 6 Sundays manually confirmed -> satisfies MIN_MANUAL_CONFIRMATION_DAYS(4).
        val sundays = listOf(3L, 10L, 17L, 24L, 31L, 38L).mapIndexed { i, d -> ev(d, 780, manual = i < 4) }
        val saturdays = listOf(2L, 9L, 16L, 23L, 30L, 37L).map { ev(it, 790) }
        val h = MealTimeLearner.History((sundays + saturdays).toMutableList())

        // 725 = 55 min before the Sunday centre (780) — inside the [45,60] window for leadMax=60.
        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60, MealTimeLearner.DayType.SUNDAY)).isNotNull()
        // Same clock time, but queried as a WEEKDAY — no weekday mode exists → must NOT fire, even
        // though the pooled/Saturday data would have matched. This is the actual bug being fixed:
        // a weekday must never borrow a weekend-only pattern.
        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60, MealTimeLearner.DayType.WEEKDAY)).isNull()
        // Omitting nowDayType falls back to the old pooled behaviour (no Stage 1 gate) — still fires.
        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60)).isNotNull()
    }

    // ─── Stage 1 trust gate (Konzept 6.2, 2026-08-28) ────────────────────────────

    @Test fun `an otherwise-valid day-type mode does NOT fire without enough manual confirmations`() {
        // Same Sunday cluster as above, but ZERO manual taps this time.
        val sundays = listOf(3L, 10L, 17L, 24L, 31L, 38L).map { ev(it, 780) }
        val h = MealTimeLearner.History(sundays.toMutableList())
        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60, MealTimeLearner.DayType.SUNDAY)).isNull()
    }

    @Test fun `exactly MIN_MANUAL_CONFIRMATION_DAYS is enough, one fewer is not`() {
        val sundaysDays = listOf(3L, 10L, 17L, 24L, 31L, 38L)
        val threeConfirmed = MealTimeLearner.History(sundaysDays.mapIndexed { i, d -> ev(d, 780, manual = i < 3) }.toMutableList())
        val fourConfirmed = MealTimeLearner.History(sundaysDays.mapIndexed { i, d -> ev(d, 780, manual = i < 4) }.toMutableList())
        assertThat(MealTimeLearner.preMealWindow(threeConfirmed, 725, offset, 60, MealTimeLearner.DayType.SUNDAY)).isNull()
        assertThat(MealTimeLearner.preMealWindow(fourConfirmed, 725, offset, 60, MealTimeLearner.DayType.SUNDAY)).isNotNull()
    }

    @Test fun `two manual taps on the SAME day count as one distinct day, not enough alone`() {
        val sundaysDays = listOf(3L, 10L, 17L, 24L, 31L, 38L)
        val events = sundaysDays.mapIndexed { i, d -> ev(d, 780, manual = i == 0) }.toMutableList()
        events.add(ev(3, 781, manual = true))   // a second manual tap on day 3, same cluster
        val h = MealTimeLearner.History(events)
        // Still only 1 distinct manually-confirmed day -> gate not satisfied.
        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60, MealTimeLearner.DayType.SUNDAY)).isNull()
    }

    // ─── Stage 2 graduation (Konzept 6.2, 2026-08-28) ────────────────────────────

    @Test fun `recordGraduationProgress does not graduate below either threshold`() {
        val key = MealTimeLearner.ModeKey(MealTimeLearner.DayType.SUNDAY, 780)
        var state = MealTimeLearner.GraduationState()
        // 8 confirmations but all in the SAME week (dayIndex 0..7 spans <2 weeks) -> distinctWeeks too low.
        for (day in 0L until 8L) state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = 0L)
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = 0L)).isFalse()
    }

    @Test fun `recordGraduationProgress graduates once both day-count and week-spread thresholds are met`() {
        val key = MealTimeLearner.ModeKey(MealTimeLearner.DayType.SUNDAY, 780)
        var state = MealTimeLearner.GraduationState()
        // 8 confirmations, one every 7 days -> 8 distinct weeks, >= GRADUATION_MIN_DISTINCT_WEEKS(6).
        val days = (0 until 8).map { it * 7L }
        for (day in days) state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = day * 86_400_000L)
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = days.last() * 86_400_000L)).isTrue()
    }

    @Test fun `a mode re-graduates after expiry once enough FRESH evidence accumulates again`() {
        // Regression test for a real bug found on re-check: the first implementation only ever
        // evaluated the graduation thresholds while graduatedAtMs == 0L, so an expired record could
        // never re-graduate no matter how many fresh confirmations arrived afterwards.
        val key = MealTimeLearner.ModeKey(MealTimeLearner.DayType.SUNDAY, 780)
        var state = MealTimeLearner.GraduationState()
        val firstRun = (0 until 8).map { it * 7L }
        for (day in firstRun) state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = day * 86_400_000L)
        val firstGraduatedAtMs = firstRun.last() * 86_400_000L
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = firstGraduatedAtMs)).isTrue()

        // Well past expiry, and past the pruning cutoff for ALL of firstRun's days too.
        val expiredNowMs = firstGraduatedAtMs + MealTimeLearner.GRADUATION_VALIDITY_MS + 1
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = expiredNowMs)).isFalse()

        // A single fresh confirmation right after expiry is NOT enough on its own...
        val expiredNowDay = expiredNowMs / 86_400_000L
        state = MealTimeLearner.recordGraduationProgress(state, key, expiredNowDay, nowMs = expiredNowMs)
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = expiredNowMs)).isFalse()

        // ...but a full fresh run of 8 confirmations over 8 distinct weeks, starting from scratch
        // after expiry, DOES re-graduate it.
        val secondRun = (0 until 8).map { expiredNowDay + it * 7L }
        for (day in secondRun) state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = day * 86_400_000L)
        val secondGraduatedAtMs = secondRun.last() * 86_400_000L
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = secondGraduatedAtMs)).isTrue()
    }

    @Test fun `graduation expires after GRADUATION_VALIDITY_MS`() {
        val key = MealTimeLearner.ModeKey(MealTimeLearner.DayType.SUNDAY, 780)
        var state = MealTimeLearner.GraduationState()
        val days = (0 until 8).map { it * 7L }
        var graduatedAtMs = 0L
        for (day in days) {
            graduatedAtMs = day * 86_400_000L
            state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = graduatedAtMs)
        }
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = graduatedAtMs + MealTimeLearner.GRADUATION_VALIDITY_MS - 1)).isTrue()
        assertThat(MealTimeLearner.isGraduated(state, key, nowMs = graduatedAtMs + MealTimeLearner.GRADUATION_VALIDITY_MS + 1)).isFalse()
    }

    @Test fun `a graduated mode fires even with zero fresh Stage 1 confirmations in the rolling window`() {
        // Cluster with NO manual taps at all (would normally fail Stage 1)...
        val sundays = listOf(3L, 10L, 17L, 24L, 31L, 38L).map { ev(it, 780) }
        val h = MealTimeLearner.History(sundays.toMutableList())
        // ...but the same time-slot key has already graduated separately.
        val key = MealTimeLearner.modeKeyOf(MealTimeLearner.DayType.SUNDAY, 780)
        var state = MealTimeLearner.GraduationState()
        for (day in (0 until 8).map { it * 7L }) state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = day * 86_400_000L)

        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60, MealTimeLearner.DayType.SUNDAY, graduationState = state, nowMs = 100L * 86_400_000L)).isNotNull()
    }

    @Test fun `graduation cannot manufacture a mode where the underlying auto-cluster no longer exists`() {
        // Only 3 distinct days total -> below MIN_DISTINCT_DAYS(4) -> no cluster forms at all,
        // regardless of graduation state.
        val h = MealTimeLearner.History(listOf(3L, 10L, 17L).map { ev(it, 780) }.toMutableList())
        val key = MealTimeLearner.modeKeyOf(MealTimeLearner.DayType.SUNDAY, 780)
        var state = MealTimeLearner.GraduationState()
        for (day in (0 until 8).map { it * 7L }) state = MealTimeLearner.recordGraduationProgress(state, key, day, nowMs = day * 86_400_000L)

        assertThat(MealTimeLearner.preMealWindow(h, 725, offset, 60, MealTimeLearner.DayType.SUNDAY, graduationState = state, nowMs = 100L * 86_400_000L)).isNull()
    }
}
