package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.history.YpsoBolusPumpIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoBolusPumpIdentityTest {

    @Test
    fun `identity keeps the baseline generation for a later sequence`() {
        val baseline = (3L shl 32) or 48_100L

        assertEquals((3L shl 32) or 48_101L, YpsoBolusPumpIdentity.of(baseline, 48_101L))
        assertEquals((3L shl 32) or 48_100L, YpsoBolusPumpIdentity.of(baseline, 48_100L))
    }

    @Test
    fun `a wrapped sequence advances the generation exactly once`() {
        val baseline = (3L shl 32) or 0xfffffff0L

        assertEquals((4L shl 32) or 5L, YpsoBolusPumpIdentity.of(baseline, 5L))
    }

    @Test
    fun `an unrepresentable generation yields no identity rather than a wrong one`() {
        val baseline = (Int.MAX_VALUE.toLong() shl 32) or 0xfffffff0L

        assertNull(YpsoBolusPumpIdentity.of(baseline, 1L))
    }

    @Test
    fun `sequences outside the pump range are rejected instead of packed`() {
        assertThrows(IllegalArgumentException::class.java) { YpsoBolusPumpIdentity.of(100L, -1L) }
        assertThrows(IllegalArgumentException::class.java) { YpsoBolusPumpIdentity.of(100L, 0x1_0000_0000L) }
    }

    @Test
    fun `ordering only moves forward across the counter wrap`() {
        assertTrue(YpsoBolusPumpIdentity.isStrictlyNewer(48_101L, 48_100L))
        assertFalse(YpsoBolusPumpIdentity.isStrictlyNewer(48_100L, 48_100L))
        assertFalse(YpsoBolusPumpIdentity.isStrictlyNewer(48_099L, 48_100L))
        assertTrue(YpsoBolusPumpIdentity.isStrictlyNewer(1L, 0xfffffff0L))
        assertFalse(YpsoBolusPumpIdentity.isStrictlyNewer(0xfffffff0L, 1L))
    }
}
