package app.aaps.plugins.aps.openAPSBoost

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.getBoostDosing
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GpsActivityRecognitionIngest — Konzept 8 GPS part (2026-08-27), SHADOW ONLY.
 *
 * Android's Activity Recognition Transition API (`com.google.android.gms.location`) delivers
 * ON_BICYCLE enter/exit events live, as they happen. Real incident this targets: cycling missed by
 * the HR+steps detector (`HrActivityCalculator`), real hypo, had to drink against it.
 *
 * (Originally planned alongside a Health Connect ExerciseSessionRecord path for broader sport-type
 * coverage — reverted 2026-08-27: Galaxy Watch only auto-detects Walking/Running/Cycling, and
 * Badminton/Swimming would have needed a MANUAL workout start on the watch, which doesn't happen in
 * practice, so that half had no realistic path to real value. See TODO.md.)
 *
 * Scoped to ON_BICYCLE only, not the full [DetectedActivity] catalog — the one type with (a) a clear
 * GPS movement signature and (b) a real, documented detection gap in the existing step-based
 * mechanism to fill. WALKING is deliberately excluded — already covered by the step detector, a
 * dedicated signal would add noise, not information. STILL/IN_VEHICLE aren't exercise.
 *
 * Never writes into any AAPS data table and never touches dosing/TT — purely detects and exposes
 * "this transition happened".
 */
@Singleton
class GpsActivityRecognitionIngest @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    data class Transition(val activityType: Int, val entering: Boolean, val atMs: Long)

    /**
     * Backing store — NOT the public read surface, see [recentTransitions]. A `List`, not a single
     * "most recent" slot — an exit shortly after an enter must both stay reachable for one
     * shadow-log line, not silently overwrite each other.
     */
    @Volatile private var storedTransitions: List<Transition> = emptyList()

    /**
     * Every transition within the last [RECENT_MS] of [nowMs] — read by OpenAPSBoostPlugin's
     * Shadow block every cycle.
     *
     * 2026-09-05 BUGFIX (real incident: 122 repeated `gpsActivity:` log lines over 9 hours on an
     * overnight ferry, while the user was asleep and later walking — not cycling for anything
     * close to that long). Previously this pruned ONLY inside [onTransitionEvent], i.e. only when a
     * NEW Android callback arrived — confirmed as intended by this class's own pre-existing test
     * ("events older than the 90min window are pruned ON THE NEXT EVENT"). If no further callback
     * ever arrives, a stale entry sat here indefinitely. The consumer's own dedup
     * (`loggedGpsTransitionMs` in OpenAPSBoostPlugin) normally stops that from being re-announced
     * more than once — but that dedup is a plain instance field, not Dagger-singleton-scoped like
     * this class, so anything that recreates the OWNING plugin instance without killing the whole
     * process (AAPS can and does reload/reconstruct plugins) resets it while this class's state
     * survives, making an already-stale entry look "new" again.
     *
     * Threading `nowMs` in from the caller rather than reading the system clock here, matching the
     * rest of this codebase's testability convention (MealHypothesis, ConfirmTrancheController,
     * ...) — a class that reads its own wall clock cannot be driven deterministically from a test.
     *
     * Self-heals as a side effect: whatever this read finds too old for THIS [nowMs] is dropped
     * from the backing store too, not just from the returned list — so staleness can never persist
     * for longer than [RECENT_MS] regardless of whether new Android callbacks keep arriving.
     */
    fun recentTransitions(nowMs: Long): List<Transition> {
        val fresh = storedTransitions.filter { nowMs - it.atMs <= RECENT_MS }
        storedTransitions = fresh
        return fresh
    }

    private val registered = AtomicBoolean(false)

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GpsActivityTransitionReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * Registers for ON_BICYCLE enter/exit updates if the feature is enabled, the
     * `ACTIVITY_RECOGNITION` permission is granted (already requested app-wide for the step
     * counter — see MainApp.kt — no new permission prompt needed), and we haven't already
     * registered this process lifetime. Cheap no-op otherwise; never throws (Play Services
     * availability varies by device/ROM, this must not affect the loop cycle calling it).
     * Call from OpenAPSBoostPlugin's onStart()/invoke() — idempotent, safe to call every cycle.
     */
    @SuppressLint("MissingPermission") // permission checked explicitly below, not via annotation
    fun registerIfNeeded() {
        if (!preferences.getBoostDosing(BooleanKey.ApsBoostGpsActivityRecognitionEnabled)) return
        if (!registered.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            aapsLogger.warn(LTag.APS, "GpsActivityRecognitionIngest: ACTIVITY_RECOGNITION not granted — skipping registration")
            registered.set(false)
            return
        }
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
        )
        try {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent())
                .addOnSuccessListener { aapsLogger.info(LTag.APS, "GpsActivityRecognitionIngest: registered for ON_BICYCLE transitions") }
                .addOnFailureListener { t ->
                    // Found on review: this async path did NOT reset `registered`, unlike the
                    // permission-check and synchronous-throw paths above/below — a transient failure
                    // (e.g. Play Services not yet warmed up right after app start) would have left
                    // `registered` stuck at true forever, silently never retrying for the rest of the
                    // process lifetime despite registerIfNeeded() being called every cycle specifically
                    // to be self-healing. Reset here too so the next cycle retries.
                    aapsLogger.error(LTag.APS, "GpsActivityRecognitionIngest: registration failed: ${t.message}")
                    registered.set(false)
                }
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "GpsActivityRecognitionIngest: registration threw: ${t.message}")
            registered.set(false)
        }
    }

    /** Called by [GpsActivityTransitionReceiver] for each transition event. [atMs] is wall-clock at
     *  receipt (not `event.elapsedRealTimeNanos`, which is boot-relative and needs an extra
     *  SystemClock reference to convert) — an approximation, broadcast delivery latency is normally
     *  small; fine for shadow logging, not something dosing ever reads. */
    fun onTransitionEvent(activityType: Int, transitionType: Int, atMs: Long) {
        val entering = transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
        aapsLogger.info(LTag.APS, "GpsActivityRecognitionIngest: activityType=$activityType entering=$entering at=$atMs")
        val cutoff = atMs - RECENT_MS
        storedTransitions = storedTransitions.filter { it.atMs >= cutoff } + Transition(activityType, entering, atMs)
    }

    companion object {
        private const val REQUEST_CODE = 4771
        // 90min "still worth reporting" window — read by OpenAPSBoostPlugin's shadow-log pruning too.
        const val RECENT_MS = 90 * 60_000L
    }
}
