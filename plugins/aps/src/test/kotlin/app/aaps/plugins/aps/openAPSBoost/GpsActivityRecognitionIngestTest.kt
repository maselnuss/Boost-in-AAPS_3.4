package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_ENTER
import com.google.android.gms.location.ActivityTransition.ACTIVITY_TRANSITION_EXIT
import com.google.android.gms.location.DetectedActivity
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Tests for the pure accumulate/prune logic in [GpsActivityRecognitionIngest.onTransitionEvent] and
 * [GpsActivityRecognitionIngest.recentTransitions] — Konzept 8 GPS part (2026-08-27). Android/Play
 * Services glue (registerIfNeeded, the actual PendingIntent/ActivityRecognitionClient calls) isn't
 * exercised here — [Context] is only needed to satisfy the constructor, never touched by the method
 * under test.
 */
class GpsActivityRecognitionIngestTest {

    private fun ingest() = GpsActivityRecognitionIngest(mock<Context>(), mock<Preferences>(), mock<AAPSLogger>())

    @Test fun `a single ENTER transition is recorded`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 1000L)
        val result = g.recentTransitions(1000L)
        assertThat(result).hasSize(1)
        val t = result.single()
        assertThat(t.activityType).isEqualTo(DetectedActivity.ON_BICYCLE)
        assertThat(t.entering).isTrue()
        assertThat(t.atMs).isEqualTo(1000L)
    }

    @Test fun `ENTER followed by EXIT keeps both, newest last`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 1000L)
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_EXIT, 2000L)
        val result = g.recentTransitions(2000L)
        assertThat(result).hasSize(2)
        assertThat(result.map { it.entering }).containsExactly(true, false).inOrder()
    }

    @Test fun `events older than the 90min window are pruned on the next event`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        // 91 minutes later — the old ENTER should be pruned when the new event lands.
        val ninetyOneMinLaterMs = 91 * 60_000L
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_EXIT, ninetyOneMinLaterMs)
        val result = g.recentTransitions(ninetyOneMinLaterMs)
        assertThat(result).hasSize(1)
        assertThat(result.single().atMs).isEqualTo(ninetyOneMinLaterMs)
    }

    @Test fun `an event still within the 90min window is kept`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        val eightyMinLaterMs = 80 * 60_000L
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_EXIT, eightyMinLaterMs)
        assertThat(g.recentTransitions(eightyMinLaterMs)).hasSize(2)
    }

    // ── 2026-09-05: the actual bug — staleness must age out on READ, without needing a new event ──

    @Test fun `a stale entry ages out on a later read even with no new event arriving`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        // No further onTransitionEvent call at all — this is the overnight-ferry scenario: Android
        // never sends another callback, so nothing would ever trigger the OLD write-side-only prune.
        val ninetyOneMinLaterMs = 91 * 60_000L
        assertThat(g.recentTransitions(ninetyOneMinLaterMs)).isEmpty()
    }

    @Test fun `a read exactly at the 90min boundary still includes the event`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        assertThat(g.recentTransitions(GpsActivityRecognitionIngest.RECENT_MS)).hasSize(1)
    }

    @Test fun `reading twice does not duplicate or otherwise mutate unexpired entries`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        val fortyMinLaterMs = 40 * 60_000L
        assertThat(g.recentTransitions(fortyMinLaterMs)).hasSize(1)
        assertThat(g.recentTransitions(fortyMinLaterMs)).hasSize(1)
    }
}
