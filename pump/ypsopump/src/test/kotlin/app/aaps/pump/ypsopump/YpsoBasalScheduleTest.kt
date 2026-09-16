package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class YpsoBasalScheduleTest {
    private val a = listOf(45,45,45,60,60,75,75,75,60,60,60,50,50,50,50,50,50,50,50,50,50,50,50,50)

    @Test
    fun `compares all operator supplied hourly intervals not just current rate`() {
        val schedule = YpsoBasalSchedule.decode(a.map(YpsoGlb::encode))!!
        val matching = a.mapIndexed { h, v -> YpsoBasalSchedule.EffectiveSegment(h * 3600, v / 100.0) }
        assertTrue(schedule.matches(matching))
        assertFalse(schedule.matches(matching.mapIndexed { i, v -> if (i == 23) v.copy(unitsPerHour = .51) else v }))
        assertEquals(.75, schedule.rateAt(7 * 3600))
    }

    @Test
    fun `subhour change cannot hide between hourly samples`() {
        val schedule = YpsoBasalSchedule.decode(List(24) { YpsoGlb.encode(50) })!!
        assertFalse(schedule.matches(listOf(
            YpsoBasalSchedule.EffectiveSegment(0,.5),
            YpsoBasalSchedule.EffectiveSegment(1800,.6),
            YpsoBasalSchedule.EffectiveSegment(3600,.5),
        )))
        assertTrue(schedule.matches(listOf(YpsoBasalSchedule.EffectiveSegment(0,.5), YpsoBasalSchedule.EffectiveSegment(1800,.5))))
        assertFalse(schedule.matches(listOf(YpsoBasalSchedule.EffectiveSegment(0,.504))))
    }

    @Test
    fun `rejects incomplete malformed sentinel and excessive values`() {
        assertNull(YpsoBasalSchedule.decode(List(23) { YpsoGlb.encode(50) }))
        assertNull(YpsoBasalSchedule.decode(List(24) { YpsoGlb.encode(-1) }))
        assertNull(YpsoBasalSchedule.decode(List(24) { YpsoGlb.encode(4001) }))
        assertNull(YpsoBasalSchedule.decode(List(24) { YpsoGlb.encode(50) + byteArrayOf(0) }))
        val schedule = YpsoBasalSchedule.decode(List(24) { YpsoGlb.encode(0) })!!
        assertTrue(schedule.matches(listOf(YpsoBasalSchedule.EffectiveSegment(0,0.0))))
        assertFalse(schedule.matches(listOf(YpsoBasalSchedule.EffectiveSegment(0,Double.NaN))))
    }

    @Test
    fun `effective percentage arithmetic permits only floating point error`() {
        val schedule = YpsoBasalSchedule.decode(List(24) { YpsoGlb.encode(90) })!!
        assertTrue(schedule.matches(listOf(YpsoBasalSchedule.EffectiveSegment(0,.6 * 1.5))))
        assertFalse(schedule.matches(listOf(YpsoBasalSchedule.EffectiveSegment(0,.900001))))
    }

    @Test
    fun `active program requires exact GLB and known wire value`() {
        assertEquals(YpsoBasalSchedule.Program.A, YpsoBasalSchedule.Program.decode(YpsoGlb.encode(3)))
        assertEquals(YpsoBasalSchedule.Program.B, YpsoBasalSchedule.Program.decode(YpsoGlb.encode(10)))
        assertNull(YpsoBasalSchedule.Program.decode(YpsoGlb.encode(1)))
    }
}
