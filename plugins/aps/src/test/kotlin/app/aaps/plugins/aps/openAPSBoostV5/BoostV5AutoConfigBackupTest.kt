package app.aaps.plugins.aps.openAPSBoostV5

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the AutoConfig/Periodic-Review undo safety net (2026-08-27): push/cap/parse/consume
 * on the pure JSON stack, independent of Android/Preferences.
 */
class BoostV5AutoConfigBackupTest {

    private fun snapshot(atMs: Long, trigger: String = "periodicReview", aggression: Double = 0.9) =
        BoostV5AutoConfigBackup.Snapshot(
            atMs = atMs,
            trigger = trigger,
            doubles = mapOf(DoubleKey.ApsBoostV5Aggression to aggression),
            booleans = mapOf(BooleanKey.ApsBoostV5FastCarbConfirm to true)
        )

    @Test fun `pushing onto an empty blob yields a single entry`() {
        val out = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L))
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(out)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].atMs).isEqualTo(1000L)
        assertThat(parsed[0].trigger).isEqualTo("periodicReview")
        assertThat(parsed[0].doubles.getValue(DoubleKey.ApsBoostV5Aggression)).isEqualTo(0.9)
        assertThat(parsed[0].booleans.getValue(BooleanKey.ApsBoostV5FastCarbConfirm)).isTrue()
    }

    @Test fun `pushing treats a blank string the same as an empty array`() {
        val out = BoostV5AutoConfigBackup.pushSnapshot("", snapshot(1000L))
        assertThat(BoostV5AutoConfigBackup.parseSnapshots(out)).hasSize(1)
    }

    @Test fun `newest snapshot is always first`() {
        // Distinct aggression per push: pushes with IDENTICAL content within DEDUPE_WINDOW_MS are
        // deliberately deduped (see the dedicated dedupe tests below) — this test is about ordering
        // of genuinely different pushes, so content must differ to not accidentally exercise dedupe.
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(2000L, aggression = 0.8))
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(blob)
        assertThat(parsed.map { it.atMs }).containsExactly(2000L, 1000L).inOrder()
    }

    @Test fun `a third push drops the oldest entry, capped at MAX_SNAPSHOTS`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(2000L, aggression = 0.8))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(3000L, aggression = 0.7))
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(blob)
        assertThat(parsed).hasSize(BoostV5AutoConfigBackup.MAX_SNAPSHOTS)
        assertThat(parsed.map { it.atMs }).containsExactly(3000L, 2000L).inOrder()
    }

    @Test fun `a push with identical content shortly after the front entry is deduped (multi-invoke-per-cycle guard)`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        // Same content, 2s later — the exact "rapid re-invoke saw stale isResolved" scenario.
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(3000L, aggression = 0.9))
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(blob)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].atMs).isEqualTo(1000L) // the original push wins, unchanged
    }

    @Test fun `a push with identical content but OUTSIDE the dedupe window is recorded, not deduped`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        val farLater = 1000L + 10 * 60_000L // 10 min later, outside the 5-min dedupe window
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(farLater, aggression = 0.9))
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(blob)
        assertThat(parsed).hasSize(2)
        assertThat(parsed.map { it.atMs }).containsExactly(farLater, 1000L).inOrder()
    }

    @Test fun `a push with different content shortly after the front entry is NOT deduped`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(1500L, aggression = 0.8))
        assertThat(BoostV5AutoConfigBackup.parseSnapshots(blob)).hasSize(2)
    }

    @Test fun `a push whose double differs only by float round-trip noise is still deduped`() {
        // Real-world case this guards: AdaptiveDoublePreference persists as Float, so two reads of
        // the "same" logical value a few ms apart can differ by a tiny epsilon — exact `==` would
        // wrongly treat that as a real change and defeat the whole dedupe guard.
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(1200L, aggression = 0.9 + 1e-6))
        assertThat(BoostV5AutoConfigBackup.parseSnapshots(blob)).hasSize(1)
    }

    @Test fun `a push whose double differs by more than the float-noise tolerance is NOT deduped`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(1200L, aggression = 0.9005))
        assertThat(BoostV5AutoConfigBackup.parseSnapshots(blob)).hasSize(2)
    }

    @Test fun `a corrupt blob parses to an empty list, not a crash`() {
        assertThat(BoostV5AutoConfigBackup.parseSnapshots("{not valid json")).isEmpty()
        assertThat(BoostV5AutoConfigBackup.parseSnapshots("null")).isEmpty()
    }

    @Test fun `an unknown key name inside a snapshot is skipped, not fatal`() {
        val hand = """[{"atMs":1000,"trigger":"autoConfig",
            "doubles":{"ApsBoostV5Aggression":0.8,"SomeRetiredKeyThatNoLongerExists":1.0},
            "booleans":{}}]"""
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(hand)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].doubles).containsExactly(DoubleKey.ApsBoostV5Aggression, 0.8)
    }

    @Test fun `consuming the newest entry (index 0) leaves only the older one`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(2000L, aggression = 0.8))
        val out = BoostV5AutoConfigBackup.consume(blob, 0)
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(out)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].atMs).isEqualTo(1000L)
    }

    @Test fun `consuming the oldest entry (last index) empties the stack`() {
        var blob = BoostV5AutoConfigBackup.pushSnapshot("[]", snapshot(1000L, aggression = 0.9))
        blob = BoostV5AutoConfigBackup.pushSnapshot(blob, snapshot(2000L, aggression = 0.8))
        val out = BoostV5AutoConfigBackup.consume(blob, 1)
        assertThat(BoostV5AutoConfigBackup.parseSnapshots(out)).isEmpty()
    }

    @Test fun `round-trip preserves multiple double and boolean keys`() {
        val snap = BoostV5AutoConfigBackup.Snapshot(
            atMs = 42L,
            trigger = "autoConfig",
            doubles = mapOf(
                DoubleKey.ApsBoostV5Aggression to 0.85,
                DoubleKey.ApsBoostMaxIob to 8.0,
                DoubleKey.ApsBoostV5PrimerCapU to 0.38
            ),
            booleans = mapOf(
                BooleanKey.ApsBoostV5FastCarbConfirm to false,
                BooleanKey.ApsBoostV5AggressiveEarlyConfirm to true
            )
        )
        val out = BoostV5AutoConfigBackup.pushSnapshot("[]", snap)
        val parsed = BoostV5AutoConfigBackup.parseSnapshots(out).single()
        assertThat(parsed.doubles).containsExactly(
            DoubleKey.ApsBoostV5Aggression, 0.85,
            DoubleKey.ApsBoostMaxIob, 8.0,
            DoubleKey.ApsBoostV5PrimerCapU, 0.38
        )
        assertThat(parsed.booleans).containsExactly(
            BooleanKey.ApsBoostV5FastCarbConfirm, false,
            BooleanKey.ApsBoostV5AggressiveEarlyConfirm, true
        )
    }
}
