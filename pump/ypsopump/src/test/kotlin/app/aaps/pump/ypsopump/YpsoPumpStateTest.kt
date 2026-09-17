package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.data.YpsoBasalSchedule
import app.aaps.pump.ypsopump.history.YpsoHistoryKind
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoPumpStateTest {

    @Test
    fun `disconnect preserves last read configuration`() {
        val state = YpsoPumpState().apply {
            elapsedRealtime = { 2001L }
            currentZone = { ZoneId.of("Europe/Warsaw") }
        }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        assertTrue(state.hasFreshProfileEvidence)
        state.connectionState = ConnectionState.DISCONNECTED
        assertTrue(state.hasFreshProfileEvidence)
        state.connectionState = ConnectionState.CONNECTED
        assertTrue(state.hasFreshProfileEvidence)
    }

    @Test
    fun `DST transition and phone clock jump do not erase pump configuration`() {
        for (start in listOf("2026-10-25T00:59:59Z", "2026-03-29T00:59:59Z", "2026-09-16T10:00:00Z")) {
            var instant = Instant.parse(start)
            var elapsed = 2001L
            val state = YpsoPumpState().apply {
                elapsedRealtime = { elapsed }
                currentZone = { ZoneId.of("Europe/Warsaw") }
                currentInstant = { instant }
            }
            state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
            assertTrue(state.hasFreshProfileEvidence)
            elapsed += 2000
            instant = instant.plusSeconds(if (start.contains("09-16")) 33 else 2)
            assertTrue(state.hasFreshProfileEvidence)
        }
    }

    @Test
    fun `last read configuration is zone bound and does not expire with status`() {
        var elapsed = 2_001L
        val zone = ZoneId.of("Europe/Warsaw")
        val state = YpsoPumpState().apply {
            elapsedRealtime = { elapsed }
            currentZone = { zone }
            currentInstant = { Instant.parse("2026-09-16T10:30:00Z") }
        }
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        assertTrue(state.hasFreshProfileEvidence)
        val matching = listOf(YpsoBasalSchedule.EffectiveSegment(0, 0.5))

        assertTrue(state.profileMatches(matching))
        assertEquals(0.5, state.scheduledBaseBasalRateIfFresh())
        state.invalidateStatus()
        assertTrue(state.profileMatches(matching), "a status poll must not erase independently acquired profile evidence")
        elapsed = 2_000L + YpsoPumpState.PROFILE_MAX_AGE_MS
        assertTrue(state.hasFreshProfileEvidence)
        assertTrue(state.profileMatches(matching))
        assertEquals(0.5, state.scheduledBaseBasalRateIfFresh())
    }

    @Test
    fun `timezone change and disconnect invalidate profile evidence`() {
        var zone = ZoneId.of("Europe/Warsaw")
        val state = YpsoPumpState().apply {
            elapsedRealtime = { 2_001L }
            currentZone = { zone }
        }
        val matching = listOf(YpsoBasalSchedule.EffectiveSegment(0, 0.5))
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        zone = ZoneId.of("UTC")
        assertFalse(state.profileMatches(matching))
        zone = ZoneId.of("Europe/Warsaw")
        state.invalidateProfileEvidence()
        assertFalse(state.profileMatches(matching))
    }

    @Test
    fun `comparison distinguishes unread configuration from proven disagreement`() {
        var zone = ZoneId.of("Europe/Warsaw")
        val state = YpsoPumpState().apply {
            elapsedRealtime = { 2_001L }
            currentZone = { zone }
        }
        val matching = listOf(YpsoBasalSchedule.EffectiveSegment(0, 0.5))
        val different = listOf(YpsoBasalSchedule.EffectiveSegment(0, 0.9))
        assertEquals(YpsoPumpState.ProfileComparison.UNREAD, state.profileComparison)

        state.profileMatches(matching)
        assertEquals(YpsoPumpState.ProfileComparison.UNREAD, state.profileComparison, "no configuration was ever read")

        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        assertEquals(YpsoPumpState.ProfileComparison.UNREAD, state.profileComparison, "a new read is not compared yet")
        state.profileMatches(matching)
        assertEquals(YpsoPumpState.ProfileComparison.MATCHES, state.profileComparison)
        state.profileMatches(different)
        assertEquals(YpsoPumpState.ProfileComparison.MISMATCH, state.profileComparison)

        // A zone change makes the retained schedules incomparable; that is unproven, not disagreement.
        zone = ZoneId.of("UTC")
        state.profileMatches(different)
        assertEquals(YpsoPumpState.ProfileComparison.UNREAD, state.profileComparison)

        zone = ZoneId.of("Europe/Warsaw")
        state.profileMatches(different)
        state.invalidateProfileEvidence()
        assertEquals(YpsoPumpState.ProfileComparison.UNREAD, state.profileComparison)
    }

    @Test
    fun `historical rows do not erase explicit last read configuration`() {
        val state = YpsoPumpState().apply {
            elapsedRealtime = { 2_001L }
            currentZone = { ZoneId.of("Europe/Warsaw") }
        }
        val matching = listOf(YpsoBasalSchedule.EffectiveSegment(0, 0.5))
        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        state.observeHistory(YpsoHistoryKind.BOLUS_STEP_CHANGED)
        assertTrue(state.profileMatches(matching))
        state.observeHistory(YpsoHistoryKind.BASAL_PROFILE_CHANGED)
        assertTrue(state.profileMatches(matching))

        state.publishProfileEvidence(YpsoProfileReadbackTest.verified())
        state.observeHistory(YpsoHistoryKind.TIME_CHANGED)
        assertTrue(state.profileMatches(matching))
    }
    @Test
    fun `idle sample expires at five minutes and reconnect does not refresh it`() {
        var elapsed = 20_000L
        val state = YpsoPumpState().apply { elapsedRealtime = { elapsed } }
        state.publishStatus(17.25, 63, false, 100, 900_000L)
        state.connectionState = ConnectionState.DISCONNECTED
        elapsed += 299_999L
        assertEquals(17.25, state.statusSnapshot?.reservoirUnits)
        assertEquals(63, state.statusSnapshot?.batteryPercent)
        assertEquals(900_000L, state.lastStatusTime)
        elapsed++
        assertNull(state.statusSnapshot)
        assertEquals(0L, state.lastStatusTime)
        state.connectionState = ConnectionState.CONNECTED
        assertNull(state.reservoirUnitsIfFresh())
        state.publishStatus(16.5, 62, false, 100, 100L)
        assertEquals(16.5, state.statusSnapshot?.reservoirUnits)
        assertEquals(100L, state.lastStatusTime)
        elapsed += 300_000L
        assertNull(state.statusSnapshot)
    }

    @Test
    fun `reservoir is exposed only with a fresh published status`() {
        val state = YpsoPumpState()

        assertNull(state.reservoirUnitsIfFresh())

        state.publishStatus(
            reservoirUnits = 42.5,
            batteryPercent = 75,
            isSuspended = false,
            activeTbrPercent = 100,
            timestamp = 1234L,
        )

        assertEquals(42.5, state.reservoirUnitsIfFresh())

        state.invalidateStatus()

        assertNull(state.reservoirUnitsIfFresh())
    }

    @Test
    fun `fresh status derives base basal from measured current rate and TBR percent`() {
        var elapsed = 10L
        val state = YpsoPumpState().apply { elapsedRealtime = { elapsed } }
        state.publishStatus(
            reservoirUnits = 42.5,
            batteryPercent = 75,
            isSuspended = false,
            activeTbrPercent = 130,
            timestamp = 1_234L,
            activeBasalRate = 0.78,
        )

        assertEquals(0.6, state.baseBasalRateIfFresh()!!, 0.000_001)

        state.publishStatus(
            reservoirUnits = 42.5,
            batteryPercent = 75,
            isSuspended = false,
            activeTbrPercent = 0,
            timestamp = 1_235L,
            activeBasalRate = 0.0,
        )
        assertNull(state.baseBasalRateIfFresh())

        state.publishStatus(
            reservoirUnits = 42.5,
            batteryPercent = 75,
            isSuspended = true,
            activeTbrPercent = 100,
            timestamp = 1_236L,
            activeBasalRate = 0.0,
        )
        assertNull(state.baseBasalRateIfFresh())

        elapsed += YpsoPumpState.STATUS_MAX_AGE_MS
        assertNull(state.baseBasalRateIfFresh())
    }

    @Test
    fun `successful status remains healthy while queue is idle`() {
        val state = YpsoPumpState()
        state.publishStatus(42.5, 75, false, 100, 1234L)
        state.connectionState = ConnectionState.DISCONNECTED

        assertTrue(state.hasVerifiedStatus)
        assertTrue(state.connectionHealthy)
    }

    @Test
    fun `disconnected before first status is not healthy`() {
        val state = YpsoPumpState()

        assertFalse(state.hasVerifiedStatus)
        assertFalse(state.connectionHealthy)
    }
}
