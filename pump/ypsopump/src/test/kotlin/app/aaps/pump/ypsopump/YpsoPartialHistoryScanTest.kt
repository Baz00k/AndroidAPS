package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Routine status polling interrupts long history scans every few minutes. Rows kept from an
 * interrupted scan may only be reused while the ring is provably unchanged: reusing them across a
 * moved ring would claim continuity the pump never proved.
 */
class YpsoPartialHistoryScanTest {

    private fun row(sequence: Long, index: Int, eventType: Int = 2, value1: Int = 100) =
        YpsoHistoryEntry(1_000_000 + sequence, eventType, value1, 0, 0, sequence, index)

    private fun prefix(vararg sequences: Long) =
        sequences.mapIndexed { index, sequence -> row(sequence, index) }

    @Test
    fun `an unchanged ring resumes the retained prefix`() {
        val cached = prefix(500, 499, 498)

        val resumed = YpsoBleManager.resumableRows(
            cachedReboot = 21, cachedCount = 900, cachedHead = cached.first(), cachedRows = cached,
            reboot = 21, count = 900, head = row(500, 0),
        )

        assertEquals(cached, resumed)
    }

    @Test
    fun `a new event at the head discards the prefix`() {
        val cached = prefix(500, 499, 498)

        // A new row shifts every index, so the retained rows no longer describe the current ring.
        assertNull(
            YpsoBleManager.resumableRows(
                cachedReboot = 21, cachedCount = 900, cachedHead = cached.first(), cachedRows = cached,
                reboot = 21, count = 901, head = row(501, 0),
            ),
        )
    }

    @Test
    fun `the same count with a different head is never resumed`() {
        val cached = prefix(500, 499, 498)

        // A full ring keeps its count while the head advances, which must not look unchanged.
        assertNull(
            YpsoBleManager.resumableRows(
                cachedReboot = 21, cachedCount = 900, cachedHead = cached.first(), cachedRows = cached,
                reboot = 21, count = 900, head = row(501, 0),
            ),
        )
    }

    @Test
    fun `a pump reboot discards the prefix`() {
        val cached = prefix(500, 499, 498)

        assertNull(
            YpsoBleManager.resumableRows(
                cachedReboot = 21, cachedCount = 900, cachedHead = cached.first(), cachedRows = cached,
                reboot = 22, count = 900, head = row(500, 0),
            ),
        )
    }

    @Test
    fun `an in place rewrite of the head discards the prefix`() {
        val cached = prefix(500, 499, 498)
        val rewrittenHead = row(500, 0, eventType = 2, value1 = 250)

        assertNull(
            YpsoBleManager.resumableRows(
                cachedReboot = 21, cachedCount = 900, cachedHead = cached.first(), cachedRows = cached,
                reboot = 21, count = 900, head = rewrittenHead,
            ),
        )
    }

    @Test
    fun `a reconnect alone does not invalidate the prefix`() {
        val cached = prefix(500, 499, 498)

        // The link drops constantly during normal operation. Reuse is proven from the ring itself, so
        // a new connection reading the same reboot, count and head must still resume: clearing on
        // disconnect stopped the scan from ever accumulating enough rows to reach the cursor.
        val resumed = YpsoBleManager.resumableRows(
            cachedReboot = 21, cachedCount = 900, cachedHead = cached.first(), cachedRows = cached,
            reboot = 21, count = 900, head = row(500, 0),
        )

        assertEquals(3, resumed?.size)
    }

    @Test
    fun `a prefix with non contiguous indices is rejected`() {
        val gapped = listOf(row(500, 0), row(499, 1), row(497, 3))

        assertNull(
            YpsoBleManager.resumableRows(
                cachedReboot = 21, cachedCount = 900, cachedHead = gapped.first(), cachedRows = gapped,
                reboot = 21, count = 900, head = row(500, 0),
            ),
        )
    }
}
