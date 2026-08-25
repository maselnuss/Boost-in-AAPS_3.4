package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.getBoostDosing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HealthConnectExerciseIngest — Konzept 8 (2026-08-26), SHADOW ONLY.
 *
 * Reads ExerciseSessionRecord (watch-native training entries, e.g. a Galaxy Watch's own automatic
 * exercise detection — any type; the request itself has no per-type filter, Health Connect doesn't
 * offer one) from Health Connect on the same polling cadence as [HealthConnectHrIngest], and keeps
 * every session found in the lookback window in memory for OpenAPSBoostPlugin's Shadow logging (see
 * ExerciseShadow.kt for the severity-tier mapping that decides what's actually reported). Never
 * writes into any AAPS data table and never touches dosing/TT — this class exists purely to detect
 * and expose "these exercise sessions were recorded", nothing else.
 *
 * Mirrors HealthConnectHrIngest's throttle/failsafe structure, simplified: an ExerciseSessionRecord
 * is one finalized (startTime, endTime) entry per workout — no per-sample stream dedup needed like HR.
 */
@Singleton
class HealthConnectExerciseIngest @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var client: HealthConnectClient? = null
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastSyncRunMs: Long = 0L
    @Volatile private var permissionWarned = false

    // Covers a bit more than a day so a session written late (synced from watch to phone the next
    // morning) is still caught on the next poll rather than skipped.
    private val lookbackMs = 30 * 60 * 60_000L

    /** Every exercise session found within [lookbackMs], of any type — filtering/relevance is
     *  ExerciseShadow's job, not this class's. Read by OpenAPSBoostPlugin's Shadow block (which
     *  dedups per session so each is only ever logged once); written only from [syncOnce]. A `List`,
     *  not a single "most recent" slot — two different, still-relevant sessions (e.g. a badminton
     *  match and a bike ride both within the last 90min) must both be reachable, not silently
     *  overwrite each other. */
    @Volatile var recentSessions: List<ExerciseShadow.Session> = emptyList()
        private set

    val isAvailable: Boolean
        get() = try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (t: Throwable) {
            false
        }

    private fun getOrInitClient(): HealthConnectClient? {
        client?.let { return it }
        if (!isAvailable) return null
        return try {
            val c = HealthConnectClient.getOrCreate(context)
            client = c
            c
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "HealthConnectExerciseIngest: client init failed: ${t.message}")
            null
        }
    }

    /** Invoke from the Boost plugin's runEngine() each cycle. Cheap when not due; never throws. */
    fun syncIfDue() {
        if (!preferences.getBoostDosing(BooleanKey.ApsBoostHealthConnectExerciseEnabled)) return
        val intervalMs = preferences.getBoostDosing(IntKey.ApsBoostHealthConnectPollMin).coerceAtLeast(1) * 60_000L
        val now = System.currentTimeMillis()
        if (now - lastSyncRunMs < intervalMs) return
        if (!inFlight.compareAndSet(false, true)) return
        val hc = getOrInitClient()
        if (hc == null) { inFlight.set(false); return }
        lastSyncRunMs = now
        scope.launch {
            try {
                syncOnce(hc, now)
            } catch (t: Throwable) {
                aapsLogger.error(LTag.APS, "HealthConnectExerciseIngest: sync failed: ${t.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private suspend fun syncOnce(hc: HealthConnectClient, nowMs: Long) {
        val granted = try {
            val grantedPerms = hc.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(ExerciseSessionRecord::class) in grantedPerms
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "HealthConnectExerciseIngest: permission check failed: ${t.message}")
            false
        }
        if (!granted) {
            if (!permissionWarned) {
                aapsLogger.warn(LTag.APS, "HealthConnectExerciseIngest: READ_EXERCISE not granted — open AAPS settings → Boost → HR sources → Health Connect to grant.")
                permissionWarned = true
            }
            return
        }

        val sinceMs = nowMs - lookbackMs
        val request = ReadRecordsRequest(
            recordType = ExerciseSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                Instant.ofEpochMilli(sinceMs),
                Instant.ofEpochMilli(nowMs)
            )
        )
        val resp = hc.readRecords(request)
        // No per-type filter here (Health Connect doesn't offer one at the query level, and we now
        // want every type — walking excepted, see ExerciseShadow) — every session in the window is
        // kept; ExerciseShadow.severityTier() decides what's actually reported.
        recentSessions = resp.records.map { record ->
            val device = record.metadata.device?.let { d ->
                listOfNotNull(d.manufacturer, d.model).joinToString(" ").trim().ifEmpty { "HealthConnect" }
            } ?: "HealthConnect"
            ExerciseShadow.Session(
                startMs = record.startTime.toEpochMilli(),
                endMs = record.endTime.toEpochMilli(),
                exerciseType = record.exerciseType,
                source = device
            )
        }
        preferences.put(LongNonKey.ApsBoostHealthConnectLastExerciseSyncMs, nowMs)   // diagnostics only
        aapsLogger.info(LTag.APS, "HealthConnectExerciseIngest: window [${sinceMs}..${nowMs}] records=${resp.records.size}")
    }

    /** Force a sync regardless of throttle — e.g. from a settings "test now" button. */
    fun forceSync() {
        lastSyncRunMs = 0L
        syncIfDue()
    }
}
