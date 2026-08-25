package app.aaps.plugins.aps.openAPSBoost

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.getBoostDosing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HealthConnectSleepIngest — Konzept 9 (2026-08-26), SHADOW ONLY.
 *
 * Reads SleepSessionRecord (the watch's own sleep-tracking algorithm's verdict on when the user
 * was asleep) and RestingHeartRateRecord (the watch's own day-level resting-HR estimate) from
 * Health Connect, purely as an independent second opinion to log alongside Boost's existing,
 * entirely HR+step-derived [SleepStateDetector] — never feeds back into it, never overrides it.
 * See OpenAPSBoostPlugin.kt's Shadow call site (right after sleepStateCached is updated) for the
 * comparison itself.
 *
 * Mirrors HealthConnectExerciseIngest's structure (Konzept 8) — same polling/throttle pattern,
 * same permission-check-then-warn-once behaviour, same "never throws" contract for syncIfDue().
 */
@Singleton
class HealthConnectSleepIngest @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var client: HealthConnectClient? = null
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastSyncRunMs: Long = 0L
    @Volatile private var permissionWarned = false

    // Sleep sessions can span midnight and run long (naps + a full night); 24h comfortably covers
    // the current/most recent night even if the sync happens to run right after waking, without
    // reaching back into the PRIOR night too.
    private val lookbackMs = 24 * 60 * 60_000L

    data class SleepSession(val startMs: Long, val endMs: Long, val source: String)

    /** Every sleep session found within [lookbackMs]. Written only from [syncOnce]. */
    @Volatile var recentSleepSessions: List<SleepSession> = emptyList()
        private set

    /** Most recent RestingHeartRateRecord value in the window, or null if none. Written only from
     *  [syncOnce]. Health Connect emits at most one of these per day per source, so "most recent
     *  in the window" is effectively "today's value" once the watch has synced it. */
    @Volatile var latestRestingHrBpm: Int? = null
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
            aapsLogger.error(LTag.APS, "HealthConnectSleepIngest: client init failed: ${t.message}")
            null
        }
    }

    /** Invoke from the Boost plugin's runEngine() each cycle. Cheap when not due; never throws. */
    fun syncIfDue() {
        if (!preferences.getBoostDosing(BooleanKey.ApsBoostHealthConnectSleepEnabled)) return
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
                aapsLogger.error(LTag.APS, "HealthConnectSleepIngest: sync failed: ${t.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private suspend fun syncOnce(hc: HealthConnectClient, nowMs: Long) {
        val granted = try {
            val grantedPerms = hc.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(SleepSessionRecord::class) in grantedPerms &&
                HealthPermission.getReadPermission(RestingHeartRateRecord::class) in grantedPerms
        } catch (t: Throwable) {
            aapsLogger.error(LTag.APS, "HealthConnectSleepIngest: permission check failed: ${t.message}")
            false
        }
        if (!granted) {
            if (!permissionWarned) {
                aapsLogger.warn(LTag.APS, "HealthConnectSleepIngest: READ_SLEEP/READ_RESTING_HEART_RATE not granted — open AAPS settings → Boost → Health Connect to grant.")
                permissionWarned = true
            }
            return
        }

        val sinceMs = nowMs - lookbackMs
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(sinceMs), Instant.ofEpochMilli(nowMs))

        val sleepResp = hc.readRecords(ReadRecordsRequest(recordType = SleepSessionRecord::class, timeRangeFilter = range))
        recentSleepSessions = sleepResp.records.map { record ->
            val device = record.metadata.device?.let { d ->
                listOfNotNull(d.manufacturer, d.model).joinToString(" ").trim().ifEmpty { "HealthConnect" }
            } ?: "HealthConnect"
            SleepSession(record.startTime.toEpochMilli(), record.endTime.toEpochMilli(), device)
        }

        val hrResp = hc.readRecords(ReadRecordsRequest(recordType = RestingHeartRateRecord::class, timeRangeFilter = range))
        latestRestingHrBpm = hrResp.records.maxByOrNull { it.time.toEpochMilli() }?.beatsPerMinute?.toInt()

        aapsLogger.info(
            LTag.APS,
            "HealthConnectSleepIngest: window [${sinceMs}..${nowMs}] sleepSessions=${sleepResp.records.size} restingHrRecords=${hrResp.records.size}"
        )
    }

    /** Force a sync regardless of throttle — e.g. from a settings "test now" button. */
    fun forceSync() {
        lastSyncRunMs = 0L
        syncIfDue()
    }
}
