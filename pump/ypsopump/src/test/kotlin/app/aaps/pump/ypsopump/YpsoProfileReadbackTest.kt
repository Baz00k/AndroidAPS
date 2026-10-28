package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.data.YpsoProfileReadback
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YpsoProfileReadbackTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val local = LocalDateTime.of(2026,9,16,12,0)
    private val instant = local.atZone(zone).toInstant()
    private fun acquisition(a: Int = 50, b: Int = 35) = YpsoProfileReadback("generation",21,"connection",1000,YpsoBasalSchedule.Program.A).also {
        (14..61).forEach { id -> assertTrue(it.add(id,YpsoGlb.encode(if (id < 38) a else b))) }
    }
    private fun result(read: YpsoProfileReadback, active: Int = 3, connection: String = "connection", clock: LocalDateTime = local, at: Instant = instant) =
        read.finish("generation",21,connection,2000,YpsoGlb.encode(active),clock,at,zone,60_000,YpsoBleManager.MAX_PROFILE_CLOCK_SKEW,3000)
    private fun finish(read: YpsoProfileReadback, active: Int = 3, connection: String = "connection", clock: LocalDateTime = local, at: Instant = instant) =
        (result(read, active, connection, clock, at) as? YpsoProfileReadback.Result.Verified)?.readback

    @Test
    fun `target clock bytes decode and malformed calendar dates fail`() {
        assertEquals(LocalDateTime.of(2026,9,16,23,53,28),
            YpsoProfileReadback.decodeClock(byteArrayOf(0xea.toByte(),7,9,16),byteArrayOf(23,53,28)))
        assertNull(YpsoProfileReadback.decodeClock(byteArrayOf(0xea.toByte(),7,2,30),byteArrayOf(23,53,28)))
        assertNull(YpsoProfileReadback.decodeClock(byteArrayOf(0xea.toByte(),7,9,16),byteArrayOf(24,0,0)))
    }

    @Test
    fun `only complete same connection same program acquisition may publish`() {
        val read = acquisition()
        val result = finish(read)!!
        assertEquals(.5,result.activeSchedule.rateAt(0))
        assertTrue(result.isCurrent("generation",21,"connection",2001,zone,1000))
        assertFalse(result.isCurrent("generation",21,"connection",3000,zone,1000))
        assertFalse(result.isCurrent("generation",21,"connection",1999,zone,1000))
        assertFalse(result.isCurrent("generation",21,"reconnected",2001,zone,1000))
        assertFalse(result.isCurrent("generation",21,"connection",2001,ZoneId.of("UTC"),1000))
        assertNull(finish(read))
        assertNull(finish(acquisition(),active=10))
        assertNull(finish(acquisition(),connection="reconnected"))
        assertNull(finish(YpsoProfileReadback("generation",21,"connection",1000,YpsoBasalSchedule.Program.A)))
    }

    @Test
    fun `manual switch invalidation cannot be undone by switching back`() {
        val read = acquisition()
        read.invalidate()
        assertNull(finish(read,active=3))
    }

    @Test
    fun `DST gap overlap and clock jump reject evidence`() {
        assertNull(finish(acquisition(),clock=LocalDateTime.of(2026,3,29,2,30)))
        assertNull(finish(acquisition(),clock=LocalDateTime.of(2026,10,25,2,30)))
        assertNull(finish(acquisition(),at=instant.plusSeconds(3_600)))
    }

    @Test
    fun `a hand-set pump clock a few minutes off still yields evidence`() {
        // The pump clock is set by hand to the minute and drifts; 40 s off failed every read in practice.
        assertNotNull(finish(acquisition(),at=instant.plusSeconds(40)))
        assertNotNull(finish(acquisition(),at=instant.minusSeconds(4 * 60 + 59)))
    }

    @Test
    fun `a pump clock past the limit is rejected with its offset`() {
        val behind = result(acquisition(),at=instant.plusSeconds(5 * 60 + 1)) as YpsoProfileReadback.Result.Rejected
        assertEquals(Duration.ofSeconds(-301), behind.pumpClockAhead)
        val ahead = result(acquisition(),at=instant.minusSeconds(3_638)) as YpsoProfileReadback.Result.Rejected
        assertEquals(Duration.ofSeconds(3_638), ahead.pumpClockAhead)
    }

    @Test
    fun `other rejections name their condition without blaming the clock`() {
        val changed = result(acquisition(),active=10) as YpsoProfileReadback.Result.Rejected
        assertEquals("active program changed", changed.reason)
        assertNull(changed.pumpClockAhead)
    }

    @Test
    fun `duplicate row invalidates rather than hides an incoherent read`() {
        val read = acquisition()
        assertFalse(read.add(14,YpsoGlb.encode(60)))
        assertNull(finish(read))
    }

    companion object {
        internal fun verified(
            active: YpsoBasalSchedule.Program = YpsoBasalSchedule.Program.A,
            a: Int = 50,
            b: Int = 35
        ): YpsoProfileReadback.VerifiedReadback {
            val zone = ZoneId.of("Europe/Warsaw")
            val local = LocalDateTime.of(2026, 9, 16, 12, 0)
            val read = YpsoProfileReadback("generation", 21, "connection", 1000, active)
            (14..61).forEach { id -> check(read.add(id, YpsoGlb.encode(if (id < 38) a else b))) }
            val result = read.finish(
                "generation", 21, "connection", 2000, YpsoGlb.encode(active.wireValue), local,
                local.atZone(zone).toInstant(), zone, 60_000, Duration.ofSeconds(30), 3000,
            )
            return (result as YpsoProfileReadback.Result.Verified).readback
        }
    }
}
