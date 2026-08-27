package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON helpers for the AutoConfig/Periodic-Review undo safety net (2026-08-27, user request:
 * "was, wenn ich mich bei der Übernahme komplett verschätzt habe?"): a bounded stack of snapshots
 * of the managed knobs' values from immediately BEFORE the last [MAX_SNAPSHOTS] automatic applies
 * (the BoostV5AutoConfig one-shot resolution, or a Konzept-7 Periodic Review apply), so either can
 * be undone from the Advanced settings screen.
 *
 * Separated from OpenAPSBoostV5Plugin's preference I/O for the same reason as
 * BoostV5AutoConfigApply — pure, unit-testable, no Android/Preferences dependency. The blob lives
 * in a single StringNonKey (ApsBoostV5AutoConfigBackup), exportable, so it rides along with any
 * normal AAPS settings export/import.
 */
internal object BoostV5AutoConfigBackup {

    /** How many prior states are kept. 2, not 1: a missed Periodic Review notification (14-day
     *  interval) can mean two automatic applies land before the user looks — with only 1 slot the
     *  older, possibly-still-wanted state would already be gone by the time they notice. */
    const val MAX_SNAPSHOTS = 2

    /**
     * If a push lands within this many ms of the current front entry AND carries IDENTICAL
     * content, it's treated as the same event, not a new one (2026-08-27 — the documented
     * multi-invoke-per-cycle risk: `maybeAutoConfigure()` can run more than once within one 5-min
     * cycle before an earlier `preferences.put`/`markResolved` write is visible, so a rapid
     * re-derivation can compute-and-apply the SAME already-correct values a second time). Without
     * this, that harmless-but-redundant re-apply would burn a backup slot on a content-free
     * duplicate, potentially evicting a genuinely different older state early. Wide enough (5 min)
     * to cover an invoke burst within one cycle; narrow enough that two genuinely separate applies
     * weeks apart that happen to land on the same values are BOTH still recorded (not deduped away).
     */
    private const val DEDUPE_WINDOW_MS = 5 * 60_000L

    /** Same tolerance as [BoostV5AutoConfigApply]'s DEFAULT_EPS (private there, matched here, not
     *  imported — no shared-constant coupling for one number): stored preference doubles can
     *  round-trip through Float (AdaptiveDoublePreference persists floats), so two captures of the
     *  "same" logical value a few ms apart can differ by a tiny epsilon. Exact `==` on the dedupe
     *  check below would then FAIL to recognise the duplicate it exists to catch — the whole point
     *  of [DEDUPE_WINDOW_MS] — so doubles compare within this tolerance, not exactly. */
    private const val DOUBLE_EPS = 1e-4

    private fun doublesApproxEqual(a: Map<DoubleKey, Double>, b: Map<DoubleKey, Double>): Boolean =
        a.keys == b.keys && a.all { (k, v) -> kotlin.math.abs(v - b.getValue(k)) < DOUBLE_EPS }

    data class Snapshot(
        val atMs: Long,
        val trigger: String, // "autoConfig" or "periodicReview" — display-only, not parsed back
        val doubles: Map<DoubleKey, Double>,
        val booleans: Map<BooleanKey, Boolean>
    )

    /**
     * Returns [existingJson] with [snapshot] pushed onto the front (newest first), capped at
     * [MAX_SNAPSHOTS]. A snapshot with no values at all (nothing actually changed) is NOT worth
     * storing — callers should only push when at least one knob was actually written. A push
     * whose content exactly matches the current front entry within [DEDUPE_WINDOW_MS] is a no-op
     * (see [DEDUPE_WINDOW_MS] KDoc) — [existingJson] is returned unchanged.
     */
    fun pushSnapshot(existingJson: String, snapshot: Snapshot): String {
        val front = parseSnapshots(existingJson).firstOrNull()
        if (front != null &&
            doublesApproxEqual(front.doubles, snapshot.doubles) &&
            front.booleans == snapshot.booleans &&
            kotlin.math.abs(snapshot.atMs - front.atMs) < DEDUPE_WINDOW_MS
        ) {
            return existingJson
        }
        val existing = parseArray(existingJson)
        val entry = JSONObject()
            .put("atMs", snapshot.atMs)
            .put("trigger", snapshot.trigger)
            .put("doubles", JSONObject().apply { snapshot.doubles.forEach { (k, v) -> put(k.name, v) } })
            .put("booleans", JSONObject().apply { snapshot.booleans.forEach { (k, v) -> put(k.name, v) } })
        val out = JSONArray()
        out.put(entry)
        var i = 0
        while (i < existing.length() && out.length() < MAX_SNAPSHOTS) {
            out.put(existing.get(i))
            i++
        }
        return out.toString()
    }

    /**
     * Parses [json] into snapshots, newest first. Never throws: a corrupt blob (hand-edited
     * prefs, a future format this build doesn't know) parses to an empty list rather than
     * crashing the Advanced settings screen — the restore button simply won't offer anything.
     * Unknown key names inside a snapshot (e.g. a knob retired since it was captured) are
     * skipped individually rather than invalidating the whole snapshot.
     */
    fun parseSnapshots(json: String): List<Snapshot> {
        val arr = parseArray(json)
        val out = mutableListOf<Snapshot>()
        for (i in 0 until arr.length()) {
            runCatching {
                val o = arr.getJSONObject(i)
                val doublesJson = o.optJSONObject("doubles") ?: JSONObject()
                val doubles = doublesJson.keys().asSequence().mapNotNull { name ->
                    runCatching { DoubleKey.valueOf(name) }.getOrNull()?.let { it to doublesJson.getDouble(name) }
                }.toMap()
                val booleansJson = o.optJSONObject("booleans") ?: JSONObject()
                val booleans = booleansJson.keys().asSequence().mapNotNull { name ->
                    runCatching { BooleanKey.valueOf(name) }.getOrNull()?.let { it to booleansJson.getBoolean(name) }
                }.toMap()
                out.add(
                    Snapshot(
                        atMs = o.getLong("atMs"),
                        trigger = o.optString("trigger", "?"),
                        doubles = doubles,
                        booleans = booleans
                    )
                )
            }
        }
        return out
    }

    /**
     * After restoring the snapshot at [index] (0 = newest), returns the blob with that snapshot
     * AND everything newer than it removed — both are now superseded by the restore. Snapshots
     * OLDER than [index] are kept (still restorable). Restoring the oldest (last) entry therefore
     * empties the stack; restoring the newest (0) leaves only the older one, if any.
     */
    fun consume(json: String, index: Int): String {
        val arr = parseArray(json)
        val out = JSONArray()
        for (i in (index + 1) until arr.length()) out.put(arr.get(i))
        return out.toString()
    }

    private fun parseArray(json: String): JSONArray =
        runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrElse { JSONArray() }
}
