package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.KeyTiming
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyTimingTest {
    private val created = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
    private val deadline = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()

    @Test fun `deadline is exactly 28 elapsed days from creation not extraction or import`() {
        val timing = KeyTiming()
        val status = timing.status(created, created + 20 * KeyTiming.DAY_MS, deadline - 1)
        assertEquals(deadline, status.expiresAt)
        assertEquals(1L, status.remainingMs)
        assertFalse(status.expiryDue)
        assertEquals(KeyTiming.Origin.SOURCE, status.origin)
        assertTrue(timing.status(created, deadline, deadline).expiryDue)
        assertTrue(timing.status(created, deadline, deadline + 1).expiryDue)
    }

    @Test fun `old import and exact advance reminder boundary are due immediately`() {
        val timing = KeyTiming()
        val reminder = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
        assertFalse(timing.status(created, reminder, reminder - 1).reminderDue)
        assertTrue(timing.status(created, reminder, reminder).reminderDue)
        assertTrue(timing.status(created, deadline + 1, deadline + 1).expiryDue)
    }

    @Test fun `missing and unusable creation dates use explicitly labeled import estimate without overflow`() {
        for (source in listOf(null, 0L, -1L, Long.MAX_VALUE, deadline)) {
            val status = KeyTiming().status(source, created, created)
            assertEquals(deadline, status.expiresAt)
            assertEquals(KeyTiming.Origin.IMPORT_ESTIMATE, status.origin)
        }
        assertNull(KeyTiming().status(null, null, created).expiresAt)
        assertNull(KeyTiming().status(Long.MAX_VALUE, Long.MAX_VALUE, created).expiresAt)
    }

    @Test fun `manual reminder is independent of expiry and observed due survives rollback`() {
        val timing = KeyTiming(deadline + KeyTiming.DAY_MS, created + 1)
        val observed = timing.observe(created, created, created + 1)
        assertEquals(KeyTiming.Origin.USER, observed.status(created, created, created).origin)
        assertEquals(KeyTiming.Origin.USER, observed.status(created, created, created).reminderOrigin)
        assertTrue(observed.status(created, created, created - KeyTiming.DAY_MS).reminderDue)
        assertEquals(deadline + KeyTiming.DAY_MS, observed.status(created, created, created).expiresAt)
    }

    @Test fun `clock rollback cannot increase remaining time or lose due expiry`() {
        val observed = KeyTiming().observe(created, created, created + KeyTiming.DAY_MS)
        assertEquals(27 * KeyTiming.DAY_MS, observed.status(created, created, created).remainingMs)
        val expired = observed.observe(created, created, deadline)
        assertTrue(expired.status(created, created, created).expiryDue)
        assertEquals(0L, expired.status(created, created, created).remainingMs)
    }

    @Test fun `DST and zone changes do not change absolute deadline`() {
        val start = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val status = KeyTiming().status(start, start, start)
        val end = Instant.ofEpochMilli(status.expiresAt!!)
        assertEquals(Instant.parse("2026-03-29T12:00:00Z"), end)
        assertEquals(14, end.atZone(ZoneId.of("Europe/Berlin")).hour)
        assertEquals(8, end.atZone(ZoneId.of("America/New_York")).hour)
    }

    @Test fun `publisher replaces reminder with urgent deadline and replays due after restart`() {
        var dismissed = 0
        val posted = mutableListOf<Pair<Long?, Boolean>>()
        val publisher = KeyExpiryNotification()
        fun publish(at: Long) = publisher.publish(KeyTiming().status(created, created, at), { dismissed++ }, { date, due -> posted.add(date to due) })
        publish(created)
        publish(deadline - 3 * KeyTiming.DAY_MS)
        publish(deadline - 1)
        assertEquals(listOf(deadline to false), posted)
        publish(deadline)
        assertEquals(listOf(deadline to false, deadline to true), posted)
        publisher.reset()
        publish(deadline)
        assertEquals(3, posted.size)
        assertEquals(4, dismissed)
        publisher.publish(null, { dismissed++ }, { _, _ -> fail("No active key") })
        assertEquals(5, dismissed)
    }
}
