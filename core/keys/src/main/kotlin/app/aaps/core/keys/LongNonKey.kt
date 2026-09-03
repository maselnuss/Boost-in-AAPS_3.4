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
    // Konzept 6.2 (2026-08-28) — set by the Cancel action on the pre-meal notification; suppresses
    // the AUTO/learned pre-meal trigger (never a manual tap) until this timestamp. See
    // OpenAPSBoostPlugin.kt's learnedHit branch.
    ApsBoostPreMealCancelledUntilMs("boost_premeal_cancelled_until_ms", 0L),
    // Konzept 6.2 (2026-08-28) — set by the Plugin every cycle the AUTO/learned pre-meal trigger is
    // active (to now + a few cycles' buffer), read-only by BoostOverviewV2Fragment to switch the
    // MEAL button into "Cancel" mode. Self-expiring by design (no explicit clear needed): once the
    // Plugin stops re-asserting it (window closed, or cancelled — firedViaLearnedHit false next
    // cycle), it just goes stale within the buffer instead of the Fragment needing a separate signal.
    ApsBoostPreMealWindowActiveUntilMs("boost_premeal_window_active_until_ms", 0L),

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
}

