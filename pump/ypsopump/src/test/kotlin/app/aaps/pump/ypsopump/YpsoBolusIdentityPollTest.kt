package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusIdentityPoll
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.bolus.YpsoValidatedBolusRequest
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoBolusIdentityPollTest {

    private val request = YpsoValidatedBolusRequest(YpsoBolusTreatment.NORMAL, YpsoBolusShape.IMMEDIATE, 100)

    @Test
    fun `the pump's first matching delivery after dispatch proves this command`() {
        val step = YpsoBolusIdentityPoll.evaluate(attempt(), request, fastStatus(sequence = 48_001, programmed = 100), null)

        assertTrue(step is YpsoBolusIdentityPoll.Step.Proven)
    }

    @Test
    fun `an unchanged block sequence keeps polling rather than claiming identity`() {
        val step = YpsoBolusIdentityPoll.evaluate(attempt(), request, fastStatus(sequence = 48_000, programmed = 100), null)

        assertEquals(YpsoBolusIdentityPoll.Step.KeepGoing(null), step)
    }

    @Test
    fun `a same-size manual bolus appearing later is abandoned instead of adopted`() {
        // The pump was still idle when the first status arrived, then a manual 1.0 U dose appeared.
        // Its programmed amount matches this request exactly, so only sequence ordering separates
        // them. Adopting it would make a manual dose the target of a later AAPS cancellation.
        val first = YpsoBolusIdentityPoll.evaluate(attempt(), request, fastStatus(48_001, 0), null)
        assertEquals(YpsoBolusIdentityPoll.Step.KeepGoing(48_001L), first)

        val second = YpsoBolusIdentityPoll.evaluate(
            attempt(), request, fastStatus(48_002, 100), (first as YpsoBolusIdentityPoll.Step.KeepGoing).firstObservedSequence,
        )

        assertEquals(YpsoBolusIdentityPoll.Step.Abandon, second)
    }

    @Test
    fun `a stale sequence below the baseline never proves identity`() {
        val step = YpsoBolusIdentityPoll.evaluate(attempt(), request, fastStatus(sequence = 47_999, programmed = 100), null)

        assertEquals(YpsoBolusIdentityPoll.Step.KeepGoing(null), step)
    }

    @Test
    fun `a different programmed amount on the same sequence keeps polling`() {
        val step = YpsoBolusIdentityPoll.evaluate(attempt(), request, fastStatus(sequence = 48_001, programmed = 150), null)

        assertEquals(YpsoBolusIdentityPoll.Step.KeepGoing(48_001L), step)
    }

    private fun attempt() = YpsoBolusAttempt(
        requestId = "request",
        pumpSerial = "10000001",
        sessionGeneration = "generation",
        treatment = YpsoBolusTreatment.NORMAL,
        requestedCentiUnits = 100,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(48_000, 20, 100, 1, 2, 21, 1_000),
        createdAt = 1_100,
        outcome = YpsoBolusOutcome.ACCEPTED_UNVERIFIED,
        dispatchCounter = 1,
        dispatchedAt = 1_200,
    )

    private fun fastStatus(sequence: Long, programmed: Int): BolusCommand {
        val payload = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            .put(BolusCommand.STATUS_DELIVERING.toByte()).putInt(sequence.toInt()).putInt(0).putInt(programmed)
            .put(BolusCommand.STATUS_IDLE.toByte()).putInt(0).putInt(0).putInt(0)
            .putInt(0).putInt(0).putInt(0).putInt(0)
            .array()
        return BolusCommand(0.0).apply { decode(payload) }
    }
}
