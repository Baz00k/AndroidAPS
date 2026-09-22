package app.aaps.pump.ypsopump

import app.aaps.core.data.model.EB
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoExtendedBolusAccountingTest {

    private val expected = EB(
        timestamp = 1_000L,
        duration = 900_000L,
        amount = 1.2,
        ids = IDs(pumpId = 42L, pumpType = PumpType.YPSOPUMP, pumpSerial = "serial"),
    )

    @Test
    fun `persisted terminal update is accepted by stable pump identity`() {
        assertTrue(YpsoExtendedBolusAccounting.matches(expected, 42L, 1_000L, 1.2, 900_000L, "serial"))
    }

    @Test
    fun `persisted cancellation replacement accepts partial amount and elapsed duration`() {
        val partial = expected.copy(amount = 0.08, duration = 18_000L)

        assertTrue(YpsoExtendedBolusAccounting.matches(partial, 42L, 1_000L, 0.08, 18_000L, "serial"))
    }

    @Test
    fun `partial terminal row preserves start and closes at observation time`() {
        val window = YpsoExtendedBolusAccounting.terminalWindow(attempt(), 8, observedAt = 21_200L)

        assertEquals(1_200L, window.start)
        assertEquals(20_000L, window.duration)
        assertEquals(21_200L, window.end)
    }

    @Test
    fun `delayed partial recovery never extends beyond programmed duration`() {
        val window = YpsoExtendedBolusAccounting.terminalWindow(attempt(), 8, observedAt = 2_000_000L)

        assertEquals(900_000L, window.duration)
    }

    @Test
    fun `late history correction retains observed early terminal time`() {
        val stopped = attempt().copy(blockTerminalAt = 21_200L)
        val window = YpsoExtendedBolusAccounting.terminalWindow(stopped, 8, observedAt = 86_400_000L)
        assertEquals(20_000L, window.duration)
        assertEquals(21_200L, window.end)
    }

    @Test
    fun `full completion keeps programmed duration`() {
        val window = YpsoExtendedBolusAccounting.terminalWindow(attempt(), 100, observedAt = 21_200L)

        assertEquals(900_000L, window.duration)
    }

    @Test
    fun `full amount after a cancel request still keeps programmed duration`() {
        val window = YpsoExtendedBolusAccounting.terminalWindow(
            attempt().copy(
                outcome = YpsoBolusOutcome.CANCEL_PENDING,
                cancelRequestId = "cancel",
                cancelCounter = 2,
                cancelBlock = YpsoBolusBlock.SLOW,
            ),
            100,
            observedAt = 21_200L,
        )

        assertEquals(900_000L, window.duration)
    }

    @Test
    fun `missing or mismatched persisted record is rejected`() {
        assertFalse(YpsoExtendedBolusAccounting.matches(null, 42L, 1_000L, 1.2, 900_000L, "serial"))
        assertFalse(YpsoExtendedBolusAccounting.matches(expected, 43L, 1_000L, 1.2, 900_000L, "serial"))
        assertFalse(YpsoExtendedBolusAccounting.matches(expected.copy(duration = 1L), 42L, 1_000L, 1.2, 900_000L, "serial"))
        assertFalse(YpsoExtendedBolusAccounting.matches(expected.copy(isValid = false), 42L, 1_000L, 1.2, 900_000L, "serial"))
    }

    @Test
    fun `same sequence idle status after cancel proves exact delivered amount`() {
        val observation = YpsoExtendedBolusAccounting.cancelledStatusObservation(
            attempt().copy(
                outcome = YpsoBolusOutcome.CANCEL_PENDING,
                cancelRequestId = "cancel",
                cancelCounter = 2,
                cancelBlock = YpsoBolusBlock.SLOW,
            ),
            slowStatus(BolusCommand.STATUS_IDLE, sequence = 101, deliveredCentiUnits = 8),
            observedAt = 21_200L,
        )

        assertEquals(8, observation?.deliveredCentiUnits)
        assertEquals(21_200L, observation?.observedAt)
    }

    @Test
    fun `active stale or malformed status cannot resolve cancellation`() {
        val cancelling = attempt().copy(
            outcome = YpsoBolusOutcome.CANCEL_PENDING,
            cancelRequestId = "cancel",
            cancelCounter = 2,
            cancelBlock = YpsoBolusBlock.SLOW,
        )

        assertEquals(
            null,
            YpsoExtendedBolusAccounting.cancelledStatusObservation(
                cancelling,
                slowStatus(BolusCommand.STATUS_DELIVERING, sequence = 101, deliveredCentiUnits = 8),
                21_200L,
            ),
        )
        assertEquals(
            null,
            YpsoExtendedBolusAccounting.cancelledStatusObservation(
                cancelling,
                slowStatus(BolusCommand.STATUS_IDLE, sequence = 102, deliveredCentiUnits = 8),
                21_200L,
            ),
        )
        assertEquals(
            null,
            YpsoExtendedBolusAccounting.cancelledStatusObservation(
                cancelling,
                slowStatus(BolusCommand.STATUS_IDLE, sequence = 101, deliveredCentiUnits = 8, totalCentiUnits = 90),
                21_200L,
            ),
        )
        assertEquals(
            null,
            YpsoExtendedBolusAccounting.cancelledStatusObservation(
                cancelling.copy(cancelObservedCentiUnits = 9),
                slowStatus(BolusCommand.STATUS_IDLE, sequence = 101, deliveredCentiUnits = 8),
                21_200L,
            ),
        )
    }

    @Test
    fun `unproven cancellation truncates the record to the elapsed schedule`() {
        val cancelling = attempt().copy(
            outcome = YpsoBolusOutcome.CANCEL_PENDING,
            cancelRequestId = "cancel",
            cancelCounter = 2,
            cancelBlock = YpsoBolusBlock.SLOW,
        )

        // 1.0 U over 15 min, stopped 5 min in: the pump can only have scheduled a third of the dose.
        val window = YpsoExtendedBolusAccounting.unprovenCancelWindow(cancelling, stoppedAt = 301_200L)

        assertEquals(1_200L, window.start)
        assertEquals(300_000L, window.duration)
        assertEquals(33, YpsoExtendedBolusAccounting.elapsedCentiUnits(cancelling, window))
    }

    @Test
    fun `unproven cancellation never invents delivery beyond the programmed plan`() {
        val cancelling = attempt().copy(
            outcome = YpsoBolusOutcome.CANCEL_PENDING,
            cancelRequestId = "cancel",
            cancelCounter = 2,
            cancelBlock = YpsoBolusBlock.SLOW,
        )

        val window = YpsoExtendedBolusAccounting.unprovenCancelWindow(cancelling, stoppedAt = 5_000_000L)

        assertEquals(900_000L, window.duration)
        assertEquals(100, YpsoExtendedBolusAccounting.elapsedCentiUnits(cancelling, window))
    }

    @Test
    fun `unproven cancellation keeps proven partial evidence when it exceeds the schedule`() {
        val cancelling = attempt().copy(
            outcome = YpsoBolusOutcome.CANCEL_PENDING,
            cancelRequestId = "cancel",
            cancelCounter = 2,
            cancelBlock = YpsoBolusBlock.SLOW,
            cancelObservedCentiUnits = 60,
        )

        val window = YpsoExtendedBolusAccounting.unprovenCancelWindow(cancelling, stoppedAt = 301_200L)

        assertEquals(60, YpsoExtendedBolusAccounting.elapsedCentiUnits(cancelling, window))
    }

    private fun attempt() = YpsoBolusAttempt(
        requestId = "request",
        pumpSerial = "serial",
        sessionGeneration = "generation",
        treatment = YpsoBolusTreatment.NORMAL,
        requestedCentiUnits = 100,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(10, 20, 100, 1, 2, 3, 1_000),
        createdAt = 1_100,
        shape = YpsoBolusShape.EXTENDED,
        durationMinutes = 15,
        outcome = YpsoBolusOutcome.DELIVERING,
        dispatchCounter = 1,
        dispatchedAt = 1_200,
        pumpSlowSequence = 101,
    )

    private fun slowStatus(
        status: Int,
        sequence: Long,
        deliveredCentiUnits: Int,
        totalCentiUnits: Int = 100,
    ): BolusCommand {
        val payload = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            .put(BolusCommand.STATUS_IDLE.toByte()).putInt(0).putInt(0).putInt(0)
            .put(status.toByte()).putInt(sequence.toInt()).putInt(deliveredCentiUnits).putInt(totalCentiUnits)
            .putInt(0).putInt(0).putInt(1).putInt(15)
            .array()
        return BolusCommand(0.0).apply { decode(payload) }
    }
}
