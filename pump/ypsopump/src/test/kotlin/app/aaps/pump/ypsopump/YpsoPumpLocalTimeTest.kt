package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.history.YpsoHistoryClassifier
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoPumpLocalTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class YpsoPumpLocalTimeTest {
    private fun hex(raw: String) = ByteArray(raw.length / 2) { raw.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Clock change rows read from a pump with a probe build: set back after the DST change, then date, time and date again. */
    private val setBack = YpsoHistoryEntry.decodeWire(hex("4b3970320d020033000000b5d8000003022fe8"))!!
    private val dateForward = YpsoHistoryEntry.decodeWire(hex("69b673320c1c000a001a00ccda00000400c6e6"))!!
    private val time = YpsoHistoryEntry.decodeWire(hex("120875320d130017000000cdda00000300bbc3"))!!
    private val dateBack = YpsoHistoryEntry.decodeWire(hex("1c0875320c1b000a001a00cfda000001004826"))!!

    private fun seconds(wall: LocalDateTime) = ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 0, 0), wall)
    private fun dose(sequence: Long, wall: LocalDateTime) = YpsoHistoryEntry(seconds(wall), 2, 100, 0, 0, sequence, 0)
    private fun read(newestFirst: List<YpsoHistoryEntry>, offsetMs: Long?) =
        YpsoHistorySnapshot(newestFirst.size, newestFirst.size, 21, 21, newestFirst.first(), newestFirst.first(), newestFirst, true, pumpClockOffsetMs = offsetMs)
    private fun ms(utc: String) = Instant.parse(utc).toEpochMilli()
    private fun wall(row: YpsoHistoryEntry) = YpsoPumpLocalTime.localDateTime(row.factorySeconds)
    private fun setTo(row: YpsoHistoryEntry) = YpsoHistoryClassifier.classify(row).clockSetTo

    @Test
    fun `paired target factory seconds decode to pump local display time`() {
        assertEquals(LocalDateTime.of(2026, 9, 15, 14, 2, 16), YpsoPumpLocalTime.localDateTime(842_796_136))
    }

    @Test
    fun `a pump clock offset is the pump wall time read as UTC minus the phone clock`() {
        // A pump on CEST, 36 s behind the phone, read at 12:00Z.
        assertEquals(2 * 3_600_000L - 36_000L, YpsoPumpLocalTime.pumpOffsetMs(LocalDateTime.of(2026, 9, 15, 13, 59, 24), ms("2026-09-15T12:00:00Z")))
    }

    @Test
    fun `clock change rows carry the clock they replaced and decode to the setting they made`() {
        assertEquals(listOf(55_477L, 56_012L, 56_013L, 56_015L), listOf(setBack, dateForward, time, dateBack).map { it.sequence })
        assertEquals(
            listOf(YpsoHistoryKind.TIME_CHANGED, YpsoHistoryKind.DATE_CHANGED, YpsoHistoryKind.TIME_CHANGED, YpsoHistoryKind.DATE_CHANGED),
            listOf(setBack, dateForward, time, dateBack).map { YpsoHistoryClassifier.classify(it).kind },
        )
        // The manual set-back after the DST change: from CEST 03:51:39 to 02:51:00.
        assertEquals(LocalDateTime.of(2026, 10, 25, 3, 51, 39), wall(setBack))
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 51, 0), setTo(setBack))
        assertEquals(-3_639L, Duration.between(wall(setBack), setTo(setBack)).seconds)
        // A date change keeps the time of day, a time change the date.
        assertEquals(LocalDateTime.of(2026, 10, 27, 19, 22, 17), wall(dateForward))
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 22, 17), setTo(dateForward))
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 22, 58), wall(time))
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 23, 0), setTo(time))
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 23, 8), wall(dateBack))
        assertEquals(LocalDateTime.of(2026, 10, 27, 19, 23, 8), setTo(dateBack))
    }

    @Test
    fun `impossible clock settings decode to none`() {
        assertNull(setTo(time.copy(value1 = 24)))
        assertNull(setTo(time.copy(value3 = 60)))
        assertNull(setTo(dateBack.copy(value1 = 31, value2 = 2)))
        assertNull(setTo(dateBack.copy(value2 = 13)))
        assertNull(setTo(dateBack.copy(value3 = 100)))
    }

    @Test
    fun `every row is placed from the live reading back through the clock changes newer than it`() {
        // Read on CET (+1 h) after the probe's three changes; the set-back after DST lies further back.
        val rows = listOf(
            dose(56_016, LocalDateTime.of(2026, 10, 27, 19, 30)), dateBack,
            dose(56_014, LocalDateTime.of(2026, 10, 28, 19, 23, 5)), time, dateForward,
            dose(56_000, LocalDateTime.of(2026, 10, 26, 12, 0)), setBack,
            dose(55_000, LocalDateTime.of(2026, 10, 25, 3, 0)),
        )
        val times = YpsoPumpLocalTime.rowTimes(read(rows, 3_600_000L))

        assertNull(times.stop)
        assertEquals(ms("2026-10-27T18:30:00Z"), times.at(rows[0]))
        assertEquals(ms("2026-10-27T18:23:05Z"), times.at(rows[2]))
        // The time change moved the clock 2 s forward; the date changes cancel out.
        assertEquals(ms("2026-10-26T11:00:02Z"), times.at(rows[5]))
        // Before the set-back the pump ran on CEST, 37 s ahead of what it was set to.
        assertEquals(ms("2026-10-25T00:59:23Z"), times.at(rows[7]))
        // A change row is stamped with the clock it replaced.
        assertEquals(ms("2026-10-27T18:22:19Z"), times.at(dateForward))
    }

    @Test
    fun `rows after a sequence reset are placed and rows before it are not`() {
        // Before the reset the pump was set to 2026; after it, the clock starts again at 2000 and is set to 2026.
        val afterReset = YpsoHistoryEntry(seconds(LocalDateTime.of(2000, 1, 1, 0, 1)), 12, 1, 7, 26, 1, 0)
        val dose = dose(2, LocalDateTime.of(2026, 7, 1, 0, 30))
        val beforeReset = dose(55_478, LocalDateTime.of(2026, 10, 25, 3, 0))

        val times = YpsoPumpLocalTime.rowTimes(read(listOf(dose, afterReset, beforeReset, setBack), 2 * 3_600_000L))

        assertEquals(ms("2026-06-30T22:30:00Z"), times.at(dose))
        assertEquals(beforeReset, times.stop)
        assertNull(times.at(beforeReset))
    }

    @Test
    fun `without a live reading no row is placed`() {
        val rows = listOf(dose(2, LocalDateTime.of(2026, 10, 27, 19, 30)), dose(1, LocalDateTime.of(2026, 10, 27, 19, 0)))

        val times = YpsoPumpLocalTime.rowTimes(read(rows, null))

        assertNull(times.at(rows[0]))
        assertNull(times.stop)
    }

    @Test
    fun `a change row with an impossible setting or out of order places nothing at or before it`() {
        val newer = dose(56_016, LocalDateTime.of(2026, 10, 27, 19, 30))
        val older = dose(56_000, LocalDateTime.of(2026, 10, 26, 12, 0))
        val impossible = time.copy(value1 = 25)

        val broken = YpsoPumpLocalTime.rowTimes(read(listOf(newer, impossible, older), 0L))
        assertEquals(impossible, broken.stop)
        assertEquals(ms("2026-10-27T19:30:00Z"), broken.at(newer))
        assertNull(broken.at(older))

        // The date set forward to the 28th, then a change that replaced a clock still on the 27th.
        val stale = time.copy(factorySeconds = seconds(LocalDateTime.of(2026, 10, 27, 19, 25)))
        val outOfOrder = YpsoPumpLocalTime.rowTimes(read(listOf(newer, stale, dateForward, older), 0L))
        assertEquals(dateForward, outOfOrder.stop)
        assertNull(outOfOrder.at(older))
    }
}
