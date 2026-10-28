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
    fun `a hand-set pump clock up to five minutes off still yields evidence`() {
        // The pump clock is set by hand to the minute and drifts; 40 s off failed every read in practice.
        assertNotNull(finish(acquisition(),at=instant.plusSeconds(40)))
        assertNotNull(finish(acquisition(),at=instant.minusSeconds(5 * 60)))
        assertNotNull(finish(acquisition(),at=instant.plusSeconds(5 * 60)))
    }

    @Test
    fun `a pump clock past five minutes is rejected with its signed offset`() {
        val behind = result(acquisition(),at=instant.plusSeconds(5 * 60 + 1)) as YpsoProfileReadback.Result.Rejected
        assertEquals(Duration.ofSeconds(-301), behind.pumpClockAhead)
        val ahead = result(acquisition(),at=instant.minusSeconds(3_638)) as YpsoProfileReadback.Result.Rejected
        assertEquals(Duration.ofSeconds(3_638), ahead.pumpClockAhead)
    }

    @Test
    fun `every other rejection names its own condition and does not blame the clock`() {
        // The clock is 40 s off in every case, so a rejection carrying an offset would misdirect the user.
        val drifted = instant.plusSeconds(40)
        fun rejected(
            read: YpsoProfileReadback = acquisition(), generation: String = "generation", reboot: Int = 21,
            finishedElapsedMs: Long = 2000, active: Int = 3, clock: LocalDateTime = local,
            maxClockSkew: Duration = Duration.ofMinutes(5), eventCount: Int = 3000,
        ) = read.finish(generation, reboot, "connection", finishedElapsedMs, YpsoGlb.encode(active), clock, drifted, zone, 60_000, maxClockSkew, eventCount)
            as YpsoProfileReadback.Result.Rejected

        val incomplete = YpsoProfileReadback("generation",21,"connection",1000,YpsoBasalSchedule.Program.A).also { it.add(14, YpsoGlb.encode(50)) }
        val invalidRate = YpsoProfileReadback("generation",21,"connection",1000,YpsoBasalSchedule.Program.A).also {
            (14..61).forEach { id -> it.add(id, YpsoGlb.encode(if (id == 20) 4_001 else 50)) }
        }
        val reasons = listOf(
            rejected(eventCount = -1),
            rejected(generation = "other"),
            rejected(reboot = 22),
            rejected(finishedElapsedMs = 61_001),
            rejected(finishedElapsedMs = 999),
            rejected(maxClockSkew = Duration.ZERO),
            rejected(active = 10),
            rejected(read = incomplete),
            rejected(clock = LocalDateTime.of(2026,3,29,2,30)),
            rejected(read = invalidRate),
        )
        assertTrue(reasons.all { it.pumpClockAhead == null })
        assertEquals(
            listOf(
                "event count is negative", "pump session changed", "pump session changed", "acquisition took too long",
                "acquisition took too long", "clock tolerance is not positive", "active program changed", "1 of 48 rows read",
                "pump time 2026-03-29T02:30 is ambiguous or skipped in Europe/Warsaw", "profile A has an invalid rate",
            ),
            reasons.map { it.reason },
        )
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
