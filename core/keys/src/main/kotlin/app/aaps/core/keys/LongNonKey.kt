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

    // Konzept 8 (2026-08-26) — Health Connect Exercise Session ingest, diagnostics only (mirrors HR's)
    ApsBoostHealthConnectLastExerciseSyncMs("boost_health_connect_last_exercise_sync_ms", 0L),

    // Konzept 6 (2026-08-24/25) — timestamp of the last manual MEAL/ALC button tap (epoch ms).
    // Written by BoostOverviewV2Fragment on tap, read by OpenAPSBoostPlugin's next cycle(s). NOT a
    // Settings toggle — internal bridge between the UI tap and the loop's calculation cycle.
    ApsBoostLastMealTapMs("boost_last_meal_tap_ms", 0L),
    ApsBoostLastAlcoholTapMs("boost_last_alcohol_tap_ms", 0L),

    // Konzept 6 (2026-08-25) — Fragment -> Plugin: long-press on ALC requests an immediate manual
    // end of the active alcohol-protection session (epoch ms of the long-press). Same
    // write-timestamp/read-and-act bridge pattern as the tap keys above.
    ApsBoostAlcoholCancelRequestMs("boost_alcohol_cancel_request_ms", 0L),

    // Konzept 6 (2026-08-25) — Plugin -> Fragment: mirrors the Plugin's own alcoholProtectionStartMs
    // (0 = no active session) purely so the Overview button can display elapsed time. Display-only —
    // the Fragment never writes this, and the Plugin's own decision logic never reads it back.
    ApsBoostAlcoholProtectionStartMs("boost_alcohol_protection_start_ms", 0L),

    // Konzept 7 (2026-08-26) — periodic BoostV5AutoConfig re-suggestion review: epoch ms of the last
    // time the review notification was shown to the user (0 = never). Independent of the per-knob
    // resolution state in BoostV5AutoConfigApply — this periodic path re-derives EVERY managed value
    // (including ones already resolved) on an interval and lets the user pick per-item what to apply.
    ApsBoostV5PeriodicReviewLastMs("boost_v5_periodic_review_last_ms", 0L),
}

