package app.aaps.core.interfaces.alerts

interface LocalAlertUtils {

    /**
     * Check for unreachable pump (there was no connection with pump for some time).
     * Raise alarm if needed.
     * Overview notification with sound, Therapy event, SMS
     */
    fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean)

    /**
     * Preset next alarm at least 5 min after start of app
     * Call only at startup!
     */
    fun preSnoozeAlarms()

    /**
     * Shortens alarm times in case of setting changes or future data
     */
    fun shortenSnoozeInterval()

    /**
     * Report pump status has been read.
     * Shifts threshold for next alarm to now + preset_interval
     */
    fun reportPumpStatusRead()

    /**
     * Check for missing BGs.
     * Raise alarm if needed.
     * Overview notification with sound, Therapy event
     */
    fun checkStaleBGAlert()

    /**
     * Warn when the loop has not produced a successful run for too long while it is supposed to be
     * running (2026-09-05). [lastLoopRun] is passed in rather than read here so this class needs no
     * dependency on Loop; 0 means "never ran in this process" and is ignored.
     */
    fun checkStaleLoopAlert(lastLoopRun: Long, loopShouldBeRunning: Boolean)
}