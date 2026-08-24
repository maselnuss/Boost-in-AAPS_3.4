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

    // 2026-08-03 auto-config periodic re-derivation — when it last RAN (epoch ms), regardless of
    // whether it changed anything. Drives the 7-day cadence.
    ApsBoostV5AutoConfigLastRedriveMs("boost_v5_autoconfig_last_redrive_ms", 0L),

    // Install-time history-gap backfill (2026-07-30, see BoostHistorySync) — when the last request
    // was made. Enforces BoostHistorySync.RETRY_COOLDOWN_MS so a thin-history install cannot ask
    // NSClient for a re-download on every 5-minute loop cycle.
    ApsBoostHistorySyncLastAttemptMs("boost_history_sync_last_attempt_ms", 0L),
    // 2026-07-30 anchor for BoostHistorySync.NEW_INSTALL_WINDOW_MS: the first time Boost V6 evaluated
    // history on this install. A backfill request opens a brief window in which the NsClientAccept*
    // preferences are bypassed, so requests are confined to a genuinely new install rather than any
    // later moment history happens to look thin (a long pump break, a deleted history, a sensor swap).
    ApsBoostHistorySyncFirstSeenMs("boost_history_sync_first_seen_ms", 0L),

    // Timestamp of the last manual MEAL button tap (epoch ms). Written by BoostOverviewV2Fragment on
    // tap, read by OpenAPSBoostPlugin's next cycle. Internal UI→loop bridge, not a Settings toggle.
    ApsBoostLastMealTapMs("boost_last_meal_tap_ms", 0L),
}

