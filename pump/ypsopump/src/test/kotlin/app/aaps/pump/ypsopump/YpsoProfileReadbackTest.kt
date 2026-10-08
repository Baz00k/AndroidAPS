package app.aaps.pump.ypsopump

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
    private fun finish(read: YpsoProfileReadback, active: Int = 3, connection: String = "connection", clock: LocalDateTime = local, at: Instant = instant) =
        read.finish("generation",21,connection,2000,YpsoGlb.encode(active),clock,at,zone,60_000,Duration.ofSeconds(30),3000)

    @Test
    fun `target clock bytes decode and malformed calendar dates fail`() {
        assertEquals(LocalDateTime.of(2026,9,16,23,53,28),
            YpsoProfileReadback.decodeClock(byteArrayOf(0xea.toByte(),7,9,16),byteArrayOf(23,53,28)))
        assertNull(YpsoProfileReadback.decodeClock(byteArrayOf(0xea.toByte(),7,2,30),byteArrayOf(23,53,28)))
        assertNull(YpsoProfileReadback.decodeClock(byteArrayOf(0xea.toByte(),7,9,16),byteArrayOf(24,0,0)))
    }

    @Test
    fun `clock offset is given only when the time read is fast enough to bound it`() {
        val at = instant.toEpochMilli()
        // Pump 2 s ahead, read in 1 s: the pump time was taken somewhere in that second.
        assertEquals(2_000L, YpsoProfileReadback.clockOffset(local, zone, at - 2_500, at - 1_500))
        val limit = YpsoProfileReadback.MAX_CLOCK_READ_MS
        assertEquals(0L, YpsoProfileReadback.clockOffset(local, zone, at - limit / 2, at + limit / 2))
        // A 40 s read could have sampled the pump anywhere in it; its midpoint can be 20 s off.
        assertNull(YpsoProfileReadback.clockOffset(local, zone, at - limit / 2, at + limit / 2 + 1))
        assertNull(YpsoProfileReadback.clockOffset(local, zone, at - 20_000, at + 20_000))
        // The phone clock went back during the read.
        assertNull(YpsoProfileReadback.clockOffset(local, zone, at, at - 1))
        // The date was read before the time: around midnight they may belong to different days.
        assertNull(YpsoProfileReadback.clockOffset(LocalDateTime.of(2026,9,16,23,59,55), zone, at, at + 100))
        assertNull(YpsoProfileReadback.clockOffset(LocalDateTime.of(2026,9,17,0,0,5), zone, at, at + 100))
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
        assertNull(finish(acquisition(),at=instant.plusSeconds(31)))
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
            return checkNotNull(
                read.finish(
                    "generation", 21, "connection", 2000, YpsoGlb.encode(active.wireValue), local,
                    local.atZone(zone).toInstant(), zone, 60_000, Duration.ofSeconds(30), 3000,
                ),
            )
        }
    }
}
