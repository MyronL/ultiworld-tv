package local.ultiworldtv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedCheckInsTest {
    @Test
    fun `stops after three misses in a row`() {
        val missed = MissedCheckIns()
        assertFalse(missed.record(checkedIn = false))
        assertFalse(missed.record(checkedIn = false))
        assertTrue(missed.record(checkedIn = false))
    }

    @Test
    fun `a check-in that goes through starts the count again`() {
        val missed = MissedCheckIns()
        assertFalse(missed.record(checkedIn = false))
        assertFalse(missed.record(checkedIn = false))
        assertFalse(missed.record(checkedIn = true))
        assertFalse(missed.record(checkedIn = false))
        assertFalse(missed.record(checkedIn = false))
        assertTrue(missed.record(checkedIn = false))
    }

    @Test
    fun `check-ins that go through never stop playback`() {
        val missed = MissedCheckIns()
        repeat(10) { assertFalse(missed.record(checkedIn = true)) }
    }
}
