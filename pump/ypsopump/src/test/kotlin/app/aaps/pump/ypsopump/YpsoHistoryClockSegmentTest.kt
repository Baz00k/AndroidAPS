package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryEvent
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestion
import app.aaps.pump.ypsopump.history.YpsoHistoryIngestionResult
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import app.aaps.pump.ypsopump.history.YpsoHistoryState
import app.aaps.pump.ypsopump.history.YpsoHistoryStateStore
import app.aaps.pump.ypsopump.tbr.YpsoTbrHistoryAccounting
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

/**
 * History rows across pump clock changes. Each scenario follows the pump clock as the user sets it by
 * hand and where each row must land on the phone clock, placed from one live reading back through the
 * change rows.
 */
class YpsoHistoryClockSegmentTest {
    private val serial = "10000001"
    private val hour = 3_600_000L

    private fun ms(utc: String) = Instant.parse(utc).toEpochMilli()

    private fun seconds(wall: LocalDateTime) = ChronoUnit.SECONDS.between(LocalDateTime.of(2000, 1, 1, 0, 0), wall)

    /** A row the pump wrote at [wall] on its own clock. */
    private fun row(sequence: Long, wall: LocalDateTime, type: Int = 2, value1: Int = 100, value2: Int = 0) =
        YpsoHistoryEntry(seconds(wall), type, value1, value2, 0, sequence, 0)

    /** A time change row: at [replaced] on the pump clock, the user set it to [setTo] on the same date. */
    private fun setTime(sequence: Long, replaced: LocalDateTime, setTo: LocalDateTime): YpsoHistoryEntry {
        require(replaced.toLocalDate() == setTo.toLocalDate())
        return YpsoHistoryEntry(seconds(replaced), 13, setTo.hour, setTo.minute, setTo.second, sequence, 0)
    }

    /** A date change row: at [replaced] on the pump clock, the user set it to [setTo] at the same time of day. */
    private fun setDate(sequence: Long, replaced: LocalDateTime, setTo: LocalDateTime): YpsoHistoryEntry {
        require(replaced.toLocalTime() == setTo.toLocalTime())
        return YpsoHistoryEntry(seconds(replaced), 12, setTo.dayOfMonth, setTo.monthValue, setTo.year - 2000, sequence, 0)
    }

    /** A stable read of [rows], given oldest first, with the pump clock [pumpHours] from UTC, or unread. */
    private fun read(rows: List<YpsoHistoryEntry>, pumpHours: Double?) =
        rows.asReversed().mapIndexed { index, row -> row.copy(index = index) }.let { newestFirst ->
            YpsoHistorySnapshot(
                newestFirst.size, newestFirst.size, 21, 21, newestFirst.first(), newestFirst.first(), newestFirst, true,
                pumpClockOffsetMs = pumpHours?.let { (it * hour).toLong() },
            )
        }

    private class Store(var value: YpsoHistoryState = YpsoHistoryState()) : YpsoHistoryStateStore {
        override fun load() = value
        override fun commit(value: YpsoHistoryState) { this.value = value }
    }

    /** Bolus timestamps PumpSync received, and the start each TBR or stop row reached accounting with, by sequence. */
    private inner class Pump(val store: Store = Store()) {
        val boluses = mutableMapOf<Long, MutableList<Long>>()
        val basal = mutableMapOf<Long, MutableList<Long>>()
        val sync: PumpSync = mock {
            on { replayConfirmedBolusWithPumpIdDetailed(any(), any(), any(), any(), any(), any()) } doAnswer {
                boluses.getOrPut(it.getArgument(3)) { mutableListOf() } += it.getArgument<Long>(0)
                PumpSync.BolusSyncResult.INSERTED
            }
        }
        val accounting: YpsoTbrHistoryAccounting = mock {
            on { apply(any(), any(), any()) } doAnswer {
                basal.getOrPut(it.getArgument<YpsoHistoryEvent>(0).identity.sequence) { mutableListOf() } += it.getArgument<Long>(2)
                null
            }
        }
        val ingestion = YpsoHistoryIngestion(store, sync, accounting)

        fun bolusAt(sequence: Long) = boluses.getValue(sequence).last()
        fun ingest(snapshot: YpsoHistorySnapshot) = ingestion.ingest(serial, 21, snapshot)
        fun cursor() = store.value.cursor?.identity?.sequence
    }

    private fun cursorAt(row: YpsoHistoryEntry) = YpsoHistoryCursor(YpsoEventIdentity(serial, 0, row.sequence), row.fingerprint(), 21)

    @Test
    fun `after a DST change the pump is set hours later, and rows on either side keep their own clock`() {
        // Warsaw falls back at 01:00Z; the pump stays on CEST (+2) until the user sets it to CET at 08:00Z.
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 10, 25, 2, 40))
        pump.ingest(read(listOf(anchor), 2.0))
        // 09:30 on the pump twice: at 07:30Z still on CEST, and at 08:30Z after the set.
        val beforeSet = row(101, LocalDateTime.of(2026, 10, 25, 9, 30))
        val clockSet = setTime(102, LocalDateTime.of(2026, 10, 25, 10, 0), LocalDateTime.of(2026, 10, 25, 9, 0))
        val afterSet = row(103, LocalDateTime.of(2026, 10, 25, 9, 30))

        val result = pump.ingest(read(listOf(anchor, beforeSet, clockSet, afterSet), 1.0))

        assertTrue(result is YpsoHistoryIngestionResult.Applied, result.toString())
        assertEquals(ms("2026-10-25T07:30:00Z"), pump.bolusAt(101))
        assertEquals(ms("2026-10-25T08:30:00Z"), pump.bolusAt(103))
    }

    @Test
    fun `after a journey the pump keeps its home clock until it is set`() {
        // The phone is in New York; the pump still shows Warsaw summer time (+2), then is set to EDT (-4).
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 7, 1, 21, 0))
        pump.ingest(read(listOf(anchor), 2.0))
        // A dose after landing, then the clock set; the next read returns both.
        val dose = row(101, LocalDateTime.of(2026, 7, 1, 22, 30))
        val clockSet = setTime(102, LocalDateTime.of(2026, 7, 1, 23, 0), LocalDateTime.of(2026, 7, 1, 17, 0))
        val later = row(103, LocalDateTime.of(2026, 7, 1, 17, 30))

        pump.ingest(read(listOf(anchor, dose, clockSet, later), -4.0))

        assertEquals(ms("2026-07-01T20:30:00Z"), pump.bolusAt(101))
        assertEquals(ms("2026-07-01T21:30:00Z"), pump.bolusAt(103))
    }

    @Test
    fun `a pump clock moved by a few minutes places rows on either side by their own setting`() {
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 7, 1, 12, 0))
        pump.ingest(read(listOf(anchor), 2.0))
        // At 10:30Z the user moves the pump clock 3 minutes back, from 12:30 to 12:27.
        val before = row(101, LocalDateTime.of(2026, 7, 1, 12, 20))
        val clockSet = setTime(102, LocalDateTime.of(2026, 7, 1, 12, 30), LocalDateTime.of(2026, 7, 1, 12, 27))
        val after = row(103, LocalDateTime.of(2026, 7, 1, 12, 37))

        pump.ingest(read(listOf(anchor, before, clockSet, after), 2.0 - 0.05))

        assertEquals(ms("2026-07-01T10:20:00Z"), pump.bolusAt(101))
        assertEquals(ms("2026-07-01T10:40:00Z"), pump.bolusAt(103))
    }

    @Test
    fun `rows between sets the phone never saw are placed exactly`() {
        // The phone is away from 10:00Z. The pump (+1) is set to +5 at 12:00Z, a day ahead at 13:00Z, back a day at
        // 14:00Z, and to +2 at 17:00Z; the phone then reads it.
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 7, 1, 11, 0))
        pump.ingest(read(listOf(anchor), 1.0))
        val rows = listOf(
            anchor,
            setTime(101, LocalDateTime.of(2026, 7, 1, 13, 0), LocalDateTime.of(2026, 7, 1, 17, 0)),
            row(102, LocalDateTime.of(2026, 7, 1, 17, 30)),
            setDate(103, LocalDateTime.of(2026, 7, 1, 18, 0), LocalDateTime.of(2026, 7, 2, 18, 0)),
            row(104, LocalDateTime.of(2026, 7, 2, 18, 30), type = 10, value1 = 50, value2 = 20),
            setDate(105, LocalDateTime.of(2026, 7, 2, 19, 0), LocalDateTime.of(2026, 7, 1, 19, 0)),
            row(106, LocalDateTime.of(2026, 7, 1, 19, 30)),
            setTime(107, LocalDateTime.of(2026, 7, 1, 22, 0), LocalDateTime.of(2026, 7, 1, 19, 0)),
            row(108, LocalDateTime.of(2026, 7, 1, 19, 15)),
        )

        assertTrue(pump.ingest(read(rows, 2.0)) is YpsoHistoryIngestionResult.Applied)

        assertEquals(ms("2026-07-01T12:30:00Z"), pump.bolusAt(102))
        assertEquals(listOf(ms("2026-07-01T13:30:00Z")), pump.basal[104L])
        assertEquals(ms("2026-07-01T14:30:00Z"), pump.bolusAt(106))
        assertEquals(ms("2026-07-01T17:15:00Z"), pump.bolusAt(108))
        assertEquals(108L, pump.cursor())
    }

    @Test
    fun `the real set-back after DST and the probe's three changes chain from one reading`() {
        val hex = { raw: String -> ByteArray(raw.length / 2) { raw.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
        val setBack = YpsoHistoryEntry.decodeWire(hex("4b3970320d020033000000b5d8000003022fe8"))!!
        val dateForward = YpsoHistoryEntry.decodeWire(hex("69b673320c1c000a001a00ccda00000400c6e6"))!!
        val time = YpsoHistoryEntry.decodeWire(hex("120875320d130017000000cdda00000300bbc3"))!!
        val dateBack = YpsoHistoryEntry.decodeWire(hex("1c0875320c1b000a001a00cfda000001004826"))!!
        val anchor = row(55_400, LocalDateTime.of(2026, 10, 25, 2, 0))
        val pump = Pump(Store(YpsoHistoryState(cursor = cursorAt(anchor))))
        val rows = listOf(
            anchor,
            row(55_476, LocalDateTime.of(2026, 10, 25, 3, 30)), setBack,
            row(55_478, LocalDateTime.of(2026, 10, 25, 3, 0)), dateForward, time, dateBack,
            row(56_016, LocalDateTime.of(2026, 10, 27, 19, 30)),
        )

        // Read on CET (+1 h), the pump exactly on the phone clock.
        assertTrue(pump.ingest(read(rows, 1.0)) is YpsoHistoryIngestionResult.Applied)

        assertEquals(ms("2026-10-27T18:30:00Z"), pump.bolusAt(56_016))
        // The date changes cancel out; the time change moved the clock 2 s forward.
        assertEquals(ms("2026-10-25T02:00:02Z"), pump.bolusAt(55_478))
        // Before the set-back of 3639 s the pump ran on CEST, 37 s ahead of the setting it then got.
        assertEquals(ms("2026-10-25T01:29:23Z"), pump.bolusAt(55_476))
    }

    @Test
    fun `a failed clock read stops history until a read places every row exactly`() {
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 10, 25, 2, 40))
        pump.ingest(read(listOf(anchor), 2.0))
        val rows = listOf(
            anchor, row(101, LocalDateTime.of(2026, 10, 25, 9, 30)),
            setTime(102, LocalDateTime.of(2026, 10, 25, 10, 0), LocalDateTime.of(2026, 10, 25, 9, 0)),
            row(103, LocalDateTime.of(2026, 10, 25, 9, 30)),
        )

        val stopped = pump.ingest(read(rows, null))

        assertEquals(YpsoHistoryIngestionResult.Retry.TRANSIENT, (stopped as YpsoHistoryIngestionResult.Blocked).retry)
        assertEquals(100L, pump.cursor())
        assertTrue(pump.boluses.isEmpty())

        assertTrue(pump.ingest(read(rows, 1.0)) is YpsoHistoryIngestionResult.Applied)
        assertEquals(ms("2026-10-25T07:30:00Z"), pump.bolusAt(101))
        assertEquals(ms("2026-10-25T08:30:00Z"), pump.bolusAt(103))
    }

    @Test
    fun `a change row with an impossible setting stops history with the incomplete history alert`() {
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 7, 1, 12, 0))
        pump.ingest(read(listOf(anchor), 2.0))
        val broken = YpsoHistoryEntry(seconds(LocalDateTime.of(2026, 7, 1, 12, 30)), 13, 25, 0, 0, 102, 0)

        val result = pump.ingest(read(listOf(anchor, row(101, LocalDateTime.of(2026, 7, 1, 12, 20)), broken, row(103, LocalDateTime.of(2026, 7, 1, 12, 40))), 2.0))

        assertEquals(YpsoHistoryIngestionResult.Retry.NONE, (result as YpsoHistoryIngestionResult.Blocked).retry)
        assertEquals(100L, pump.cursor())
        assertTrue(pump.boluses.isEmpty())
    }

    @Test
    fun `history never moves past an invalid clock change, even with no dose to place and no clock reading`() {
        for (pumpHours in listOf(2.0, null)) {
            val pump = Pump()
            val anchor = row(100, LocalDateTime.of(2026, 7, 1, 12, 0))
            pump.ingest(read(listOf(anchor), 2.0))
            val broken = YpsoHistoryEntry(seconds(LocalDateTime.of(2026, 7, 1, 12, 30)), 13, 25, 0, 0, 101, 0)

            val result = pump.ingest(read(listOf(anchor, broken), pumpHours))

            assertEquals(YpsoHistoryIngestionResult.Retry.NONE, (result as YpsoHistoryIngestionResult.Blocked).retry, "$pumpHours")
            assertEquals(100L, pump.cursor())
        }
    }

    @Test
    fun `after a pump restart a history row that cannot be placed never keeps doses refused`() {
        val anchor = row(55_000, LocalDateTime.of(2026, 7, 1, 12, 0))
        val dose = LocalDateTime.of(2026, 7, 1, 12, 20)
        val impossible = YpsoHistoryEntry(seconds(LocalDateTime.of(2026, 7, 1, 12, 10)), 13, 25, 0, 0, 0, 0)
        // Restarted as reboot 22, continuing its sequence or starting it again; the clock is unread or a change is invalid.
        for ((rows, pumpHours) in listOf(
            listOf(anchor, row(55_001, dose)) to null,
            listOf(anchor, row(1, dose)) to null,
            listOf(anchor, impossible.copy(sequence = 1), row(2, dose)) to 2.0,
        )) {
            val pump = Pump(Store(YpsoHistoryState(cursor = cursorAt(anchor))))
            val restarted = read(rows, pumpHours).copy(pumpRebootBefore = 22, pumpRebootAfter = 22)

            assertTrue(pump.ingestion.ingest(serial, 22, restarted) is YpsoHistoryIngestionResult.Blocked)

            assertNull(pump.ingestion.bolusReadiness(serial, 22), "$rows")
            assertEquals(22L, pump.ingestion.commandCursor()?.pumpReboot)
            // The cursor keeps its reboot, so the next read still recognises a sequence reset.
            assertEquals(21L, pump.store.value.cursor?.pumpReboot)
            assertTrue(pump.boluses.isEmpty())
            if (pumpHours == null) {
                assertTrue(pump.ingestion.ingest(serial, 22, restarted.copy(pumpClockOffsetMs = 0L)) is YpsoHistoryIngestionResult.Applied)
                assertEquals(ms("2026-07-01T12:20:00Z"), pump.boluses.values.single().single())
            }
        }
    }

    @Test
    fun `an upgraded cursor places its backlog across a set exactly`() {
        val anchor = row(100, LocalDateTime.of(2026, 7, 5, 3, 0))
        val pump = Pump(Store(YpsoHistoryState(cursor = cursorAt(anchor))))
        // The pump came back on New York time (-4) and was set to Warsaw time (+2) at 09:00Z.
        val rows = listOf(
            anchor, row(101, LocalDateTime.of(2026, 7, 5, 4, 0)),
            setTime(102, LocalDateTime.of(2026, 7, 5, 5, 0), LocalDateTime.of(2026, 7, 5, 11, 0)),
            row(103, LocalDateTime.of(2026, 7, 5, 11, 30)),
        )

        assertTrue(pump.ingest(read(rows, 2.0)) is YpsoHistoryIngestionResult.Applied)

        assertEquals(ms("2026-07-05T08:00:00Z"), pump.bolusAt(101))
        assertEquals(ms("2026-07-05T09:30:00Z"), pump.bolusAt(103))
    }

    @Test
    fun `a cursor a running bolus holds below a clock change places its rows the same on every read`() {
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 10, 25, 2, 40))
        pump.ingest(read(listOf(anchor), 2.0))
        // A bolus still running (type 19) holds the cursor; a pump dose, the set and another dose follow it.
        val rows = listOf(
            anchor, row(101, LocalDateTime.of(2026, 10, 25, 9, 20), type = 19, value1 = 500), row(102, LocalDateTime.of(2026, 10, 25, 9, 30)),
            setTime(103, LocalDateTime.of(2026, 10, 25, 10, 0), LocalDateTime.of(2026, 10, 25, 9, 0)), row(104, LocalDateTime.of(2026, 10, 25, 9, 30)),
        )

        pump.ingest(read(rows, 1.0))
        pump.ingest(read(rows, 1.0))

        assertEquals(101L, pump.cursor())
        assertEquals(listOf(ms("2026-10-25T07:30:00Z"), ms("2026-10-25T07:30:00Z")), pump.boluses[102L])
        assertEquals(ms("2026-10-25T08:30:00Z"), pump.bolusAt(104))
    }

    @Test
    fun `a running TBR's rewrite after the cursor crossed a clock change keeps its own clock`() {
        val pump = Pump()
        val anchor = row(100, LocalDateTime.of(2026, 10, 25, 2, 40))
        pump.ingest(read(listOf(anchor), 2.0))
        // A TBR set at 07:00Z on CEST, the pump set to CET, a later dose; the TBR ends and its row is rewritten.
        val running = row(101, LocalDateTime.of(2026, 10, 25, 9, 0), type = 9, value1 = 50, value2 = 120)
        val clockSet = setTime(102, LocalDateTime.of(2026, 10, 25, 10, 0), LocalDateTime.of(2026, 10, 25, 9, 0))
        val dose = row(103, LocalDateTime.of(2026, 10, 25, 9, 30))
        pump.ingest(read(listOf(anchor, running, clockSet, dose), 1.0))
        assertEquals(103L, pump.cursor())

        val ended = running.copy(eventType = 10, value2 = 80)
        assertTrue(pump.ingest(read(listOf(anchor, ended, clockSet, dose), 1.0)) is YpsoHistoryIngestionResult.Applied)

        assertEquals(listOf(ms("2026-10-25T07:00:00Z"), ms("2026-10-25T07:00:00Z")), pump.basal[101L])
        assertNull(pump.basal[103L])
    }
}
