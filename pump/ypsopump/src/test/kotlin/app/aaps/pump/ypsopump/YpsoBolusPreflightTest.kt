package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusPreflight
import app.aaps.pump.ypsopump.bolus.YpsoBolusPreflightPolicy
import app.aaps.pump.ypsopump.bolus.YpsoBolusReadiness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class YpsoBolusPreflightTest {
    @Test
    fun `new dose requires every current therapy fact`() {
        assertSame(YpsoBolusPreflight.Ready, YpsoBolusPreflightPolicy.evaluate(ready()))
        val fields = listOf<(YpsoBolusReadiness) -> YpsoBolusReadiness>(
            { it.copy(connected = false) }, { it.copy(authenticatedCurrentSession = false) }, { it.copy(statusCurrent = false) },
            { it.copy(writeCounterCertain = false) }, { it.copy(profileScheduleMatches = false) },
            { it.copy(activeProgramContinuous = false) }, { it.copy(scheduleEditAbsent = false) }, { it.copy(pumpRunning = false) },
            { it.copy(reservoirHasInsulin = false) }, { it.copy(immediateBolusIdle = false) },
            { it.copy(extendedOrMixedBolusIdle = false) }, { it.copy(stableHistoryCursorCaptured = false) },
            { it.copy(unresolvedInsulinAbsent = false) },
        )
        fields.forEach { change ->
            val result = YpsoBolusPreflightPolicy.evaluate(change(ready()))
            require(result is YpsoBolusPreflight.Blocked)
        }
    }

    @Test
    fun `cancellation does not depend on profile reservoir or new-dose status preflight`() {
        assertSame(
            YpsoBolusPreflight.Ready,
            YpsoBolusPreflightPolicy.evaluateCancellation(true, true, true, true, true),
        )
        assertEquals(
            YpsoBolusPreflight.Reason.SESSION_NOT_CURRENT,
            (YpsoBolusPreflightPolicy.evaluateCancellation(true, false, true, true, true) as YpsoBolusPreflight.Blocked).reason,
        )
    }

    private fun ready() = YpsoBolusReadiness(true, true, true, true, true, true, true, true, true, true, true, true, true)
}
