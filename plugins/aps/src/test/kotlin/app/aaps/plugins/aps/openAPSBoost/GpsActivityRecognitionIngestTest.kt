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
 * Tests for the pure accumulate/prune logic in [GpsActivityRecognitionIngest.onTransitionEvent] —
 * Konzept 8 GPS part (2026-08-27). Android/Play Services glue (registerIfNeeded, the actual
 * PendingIntent/ActivityRecognitionClient calls) isn't exercised here — [Context] is only needed
 * to satisfy the constructor, never touched by the method under test.
 */
class GpsActivityRecognitionIngestTest {

    private fun ingest() = GpsActivityRecognitionIngest(mock<Context>(), mock<Preferences>(), mock<AAPSLogger>())

    @Test fun `a single ENTER transition is recorded`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 1000L)
        assertThat(g.recentTransitions).hasSize(1)
        val t = g.recentTransitions.single()
        assertThat(t.activityType).isEqualTo(DetectedActivity.ON_BICYCLE)
        assertThat(t.entering).isTrue()
        assertThat(t.atMs).isEqualTo(1000L)
    }

    @Test fun `ENTER followed by EXIT keeps both, newest last`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 1000L)
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_EXIT, 2000L)
        assertThat(g.recentTransitions).hasSize(2)
        assertThat(g.recentTransitions.map { it.entering }).containsExactly(true, false).inOrder()
    }

    @Test fun `events older than the 90min window are pruned on the next event`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        // 91 minutes later — the old ENTER should be pruned when the new event lands.
        val ninetyOneMinLaterMs = 91 * 60_000L
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_EXIT, ninetyOneMinLaterMs)
        assertThat(g.recentTransitions).hasSize(1)
        assertThat(g.recentTransitions.single().atMs).isEqualTo(ninetyOneMinLaterMs)
    }

    @Test fun `an event still within the 90min window is kept`() {
        val g = ingest()
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_ENTER, 0L)
        val eightyMinLaterMs = 80 * 60_000L
        g.onTransitionEvent(DetectedActivity.ON_BICYCLE, ACTIVITY_TRANSITION_EXIT, eightyMinLaterMs)
        assertThat(g.recentTransitions).hasSize(2)
    }
}
