package app.aaps.core.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LocalProfileLastChange("local_profile_last_change", 0L),
    BtWatchdogLastBark("bt_watchdog_last", 0L),
    ActivePumpChangeTimestamp("active_pump_change_timestamp", 0L),
    LastCleanupRun("last_cleanup_run", 0L),

    // Health Connect HR ingest — high-water mark for incremental polling (epoch ms)
    ApsBoostHealthConnectLastSyncMs("boost_health_connect_last_sync_ms", 0L),

    // Konzept 6 (2026-08-24) — timestamp of the last manual MEAL button tap (epoch ms). Written
    // by BoostOverviewV2Fragment on tap, read by OpenAPSBoostPlugin's next cycle(s). NOT a Settings
    // toggle — internal bridge between the UI tap and the loop's calculation cycle. (ALC gets its
    // own equivalent key when its shadow logic is built — step 6, not yet.)
    ApsBoostLastMealTapMs("boost_last_meal_tap_ms", 0L),
}

