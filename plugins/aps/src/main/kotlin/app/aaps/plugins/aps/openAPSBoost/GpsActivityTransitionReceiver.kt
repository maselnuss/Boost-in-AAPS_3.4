package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import android.content.Intent
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import com.google.android.gms.location.ActivityTransitionResult
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

/**
 * GpsActivityTransitionReceiver — Konzept 8 GPS part (2026-08-27), SHADOW ONLY.
 *
 * Registered in AndroidManifest.xml, targeted by the PendingIntent
 * [GpsActivityRecognitionIngest.registerIfNeeded] hands to Play Services. Dagger field injection
 * (same pattern as CarbSuggestionReceiver) gets the SAME [GpsActivityRecognitionIngest] singleton
 * instance OpenAPSBoostPlugin reads from — no separate persistence needed to bridge the async
 * broadcast into the next loop cycle.
 */
class GpsActivityTransitionReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var gpsActivityRecognitionIngest: GpsActivityRecognitionIngest
    @Inject lateinit var aapsLogger: AAPSLogger

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val nowMs = System.currentTimeMillis()
        for (event in result.transitionEvents) {
            try {
                gpsActivityRecognitionIngest.onTransitionEvent(event.activityType, event.transitionType, nowMs)
            } catch (t: Throwable) {
                aapsLogger.error(LTag.APS, "GpsActivityTransitionReceiver: failed to process event: ${t.message}")
            }
        }
    }
}
