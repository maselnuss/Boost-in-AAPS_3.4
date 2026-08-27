package app.aaps.core.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class StringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    QuickWizard(key = "QuickWizard", defaultValue = "[]"),
    WearCwfWatchfaceName(key = "wear_cwf_watchface_name", defaultValue = ""),
    WearCwfAuthorVersion(key = "wear_cwf_author_version", defaultValue = ""),
    WearCwfFileName(key = "wear_cwf_filename", defaultValue = ""),
    BolusInfoStorage(key = "key_bolus_storage", defaultValue = ""),
    ActivePumpType(key = "active_pump_type", defaultValue = ""),
    ActivePumpSerialNumber(key = "active_pump_serial_number", defaultValue = ""),
    SmsOtpSecret("smscommunicator_otp_secret", defaultValue = ""),
    TotalBaseBasal("TBB", defaultValue = "10.00"),

    // Konzept 6 (2026-08-25) — current alcohol-protection intensity, for Overview button display
    // only (LIGHT/MODERATE/HIGH, empty = no active session). Written by OpenAPSBoostPlugin, read by
    // BoostOverviewV2Fragment — display-only, never feeds back into any decision logic.
    ApsBoostAlcoholIntensityDisplay(key = "boost_alcohol_intensity_display", defaultValue = ""),

    // 2026-08-27 — AutoConfig/Periodic-Review undo safety net: up to 2 snapshots of the managed
    // knobs' values from immediately BEFORE the last 2 automatic applies (one-shot AutoConfig or
    // Konzept-7 Periodic Review), newest first. See BoostV5AutoConfigBackup.kt. Exportable so a
    // settings export/import carries the backup along like any other setting.
    ApsBoostV5AutoConfigBackup(key = "boost_v5_autoconfig_backup", defaultValue = "[]")
}
