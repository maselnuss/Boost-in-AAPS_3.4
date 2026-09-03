package app.aaps.plugins.aps.openAPSBoostV5

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The consequence prior computes and logs; nothing here asserts anything about a dose, because it
 * delivers none.
 *
 * The properties worth pinning are the ones that would break quietly. The onset must anchor at the
 * last non-rising sample and must survive a rise, since an anchor that drifted upward with the
 * trace would make the prior read the current glucose rather than the starting glucose and would
 * still look plausible in the log. The probability must move with onset glucose in the direction
 * the fit gives. And the clock terms must actually vary across the day, which is the thing a UTC
 * mistake would silently flatten.
 */
class ConsequencePriorShadowTest {

    private val hour = 3_600_000L
    private fun parse(tag: String) = tag.split(",")

    @Test fun `unusable glucose yields no tag`() {
        val s = ConsequencePriorShadow()
        assertThat(s.runCycle(0L, null)).isNull()
        assertThat(s.runCycle(0L, 0.0)).isNull()
        assertThat(s.runCycle(0L, Double.NaN)).isNull()
    }

    @Test fun `first cycle anchors the onset and shows no rise`() {
        val s = ConsequencePriorShadow()
        val f = parse(s.runCycle(12 * hour, 100.0)!!)
        assertThat(f[2]).isEqualTo("100")     // onset glucose
        assertThat(f[3]).isEqualTo("0")       // minutes since onset
        assertThat(f[4]).isEqualTo("0")       // rise so far
    }

    @Test fun `the onset survives a rise and the rise is measured from it`() {
        val s = ConsequencePriorShadow()
        s.runCycle(12 * hour, 90.0)
        s.runCycle(12 * hour + 300_000L, 110.0)
        val f = parse(s.runCycle(12 * hour + 600_000L, 140.0)!!)
        assertThat(f[2]).isEqualTo("90")      // still the starting glucose, not the current one
        assertThat(f[3]).isEqualTo("10")
        assertThat(f[4]).isEqualTo("50")
        assertThat(s.onsetGlucose()).isEqualTo(90.0)
    }

    @Test fun `a fall re-anchors the onset`() {
        val s = ConsequencePriorShadow()
        s.runCycle(12 * hour, 90.0)
        s.runCycle(12 * hour + 300_000L, 140.0)
        val f = parse(s.runCycle(12 * hour + 600_000L, 120.0)!!)
        assertThat(f[2]).isEqualTo("120")
        assertThat(f[4]).isEqualTo("0")
    }

    @Test fun `a stale anchor is replaced`() {
        val s = ConsequencePriorShadow()
        s.runCycle(0L, 90.0)
        val f = parse(s.runCycle(200 * 60_000L, 95.0)!!)
        assertThat(f[2]).isEqualTo("95")
    }

    @Test fun `higher onset glucose raises the probability of exceeding the high threshold`() {
        val low = parse(ConsequencePriorShadow().runCycle(12 * hour, 90.0)!!)[0].toDouble()
        val high = parse(ConsequencePriorShadow().runCycle(12 * hour, 200.0)!!)[0].toDouble()
        assertThat(high).isGreaterThan(low)
        assertThat(low).isGreaterThan(0.0)
        assertThat(high).isLessThan(1.0)
    }

    @Test fun `the probability varies across the day`() {
        val seen = (0 until 24).map {
            parse(ConsequencePriorShadow().runCycle(it * hour, 120.0)!!)[0].toDouble()
        }
        assertThat(seen.max() - seen.min()).isGreaterThan(0.05)
        seen.forEach { assertThat(it).isIn(com.google.common.collect.Range.open(0.0, 1.0)) }
    }
}
