package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptFileStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YpsoBolusAttemptFileStoreTest {
    @Test
    fun `all attempt identity and outcome fields survive restart`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        val expected = attempt().copy(
            outcome = YpsoBolusOutcome.CANCEL_PENDING,
            dispatchCounter = 4810,
            dispatchedAt = 2_000,
            pumpSlowSequence = 46,
            cancelRequestId = "cancel-1",
            cancelCounter = 4811,
            cancelBlock = YpsoBolusBlock.SLOW,
            cancelObservedCentiUnits = 17,
            cancelStoppedAt = 2_500,
            cancelDispatchedAt = 2_400,
            detail = "awaiting terminal pump evidence",
        )

        YpsoBolusAttemptFileStore(file).commit(expected)

        assertEquals(expected, YpsoBolusAttemptFileStore(file).load())
    }

    @Test
    fun `unknown fields and malformed values fail closed`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        val store = YpsoBolusAttemptFileStore(file)
        store.commit(attempt())
        file.writeText(file.readText().replaceFirst("{", "{\"unexpected\":1,"))
        assertThrows(IllegalArgumentException::class.java) { store.load() }

        file.delete()
        store.commit(attempt())
        file.writeText(file.readText().replaceFirst("\"requestedCentiUnits\":100", "\"requestedCentiUnits\":0"))
        assertThrows(IllegalArgumentException::class.java) { store.load() }
    }

    @Test
    fun `later not sent attempts cannot erase older durable counter allocations`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        val store = YpsoBolusAttemptFileStore(file)
        val allocated = attempt().copy(
            requestId = "allocated",
            dispatchCounter = 9_034,
            dispatchedAt = 2_000,
            cancelRequestId = "cancel-allocated",
            cancelCounter = 9_035,
            cancelBlock = YpsoBolusBlock.SLOW,
            outcome = YpsoBolusOutcome.UNRESOLVED,
        )
        val notSent = attempt().copy(requestId = "not-sent", createdAt = 3_000)

        store.commit(allocated)
        store.commit(notSent)

        assertEquals(notSent, store.load())
        assertEquals(listOf(allocated, notSent), store.loadAll())
        assertEquals(allocated, store.recoveryEvidence()?.attempt)
    }

    @Test
    fun `updates replace the matching attempt without discarding earlier attempts`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val store = YpsoBolusAttemptFileStore(directory.resolve("attempt.json"))
        val first = attempt().copy(requestId = "first", outcome = YpsoBolusOutcome.COMPLETED,
            confirmedCentiUnits = 100, deliveryTimestamp = 2_000)
        val current = attempt().copy(requestId = "current", createdAt = 3_000)

        store.commit(first)
        store.commit(current)
        store.commit(current.copy(outcome = YpsoBolusOutcome.PROVEN_REJECTED, detail = "pump rejected"))

        assertEquals(2, store.loadAll().size)
        assertEquals(YpsoBolusOutcome.COMPLETED, store.loadAll().first().outcome)
        assertEquals(YpsoBolusOutcome.PROVEN_REJECTED, store.load()?.outcome)
    }

    private fun attempt() = YpsoBolusAttempt(
        requestId = "request-1",
        pumpSerial = "10000001",
        sessionGeneration = "generation-1",
        sessionKeyId = "cd".repeat(32),
        treatment = YpsoBolusTreatment.SMB,
        requestedCentiUnits = 100,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(44, 9, 100, 10, 20, 21, 1_000),
        createdAt = 1_500,
        shape = YpsoBolusShape.EXTENDED,
        durationMinutes = 15,
    )
}
