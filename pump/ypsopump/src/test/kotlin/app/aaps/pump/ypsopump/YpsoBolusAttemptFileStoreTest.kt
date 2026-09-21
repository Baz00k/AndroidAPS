package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusAttemptFileStore
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import java.nio.file.Files
import org.json.JSONObject
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
        assertEquals(expected, YpsoBolusAttemptFileStore(file).recoveryEvidence()?.attempt)
    }

    @Test
    fun `version 2 journals remain readable as immediate attempts`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        file.writeText(version2Json())

        val loaded = YpsoBolusAttemptFileStore(file).load()

        assertEquals(YpsoBolusShape.IMMEDIATE, loaded?.shape)
        assertEquals(0, loaded?.durationMinutes)
        assertEquals(0, loaded?.immediateCentiUnits)
        assertEquals(null, loaded?.pumpSlowSequence)
        assertEquals(null, loaded?.cancelBlock)
        assertEquals(null, loaded?.sessionKeyId)
        assertEquals("pre-upgrade rejection", loaded?.detail)
    }

    @Test
    fun `version 2 cancellation ownership migrates to the fast block`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        file.writeText(
            version2Json()
                .replace("\"outcome\":\"PROVEN_REJECTED\"", "\"outcome\":\"CANCEL_PENDING\"")
                .replace("\"cancelRequestId\":null", "\"cancelRequestId\":\"cancel-1\"")
                .replace("\"cancelCounter\":null", "\"cancelCounter\":4811"),
        )

        val loaded = YpsoBolusAttemptFileStore(file).load()

        assertEquals(YpsoBolusBlock.FAST, loaded?.cancelBlock)
        assertEquals("cancel-1", loaded?.cancelRequestId)
        assertEquals(4811, loaded?.cancelCounter)
    }

    @Test
    fun `version 3 journals remain readable but have no recovery key binding`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        val current = attempt()
        YpsoBolusAttemptFileStore(file).commit(current)
        file.writeText(
            file.readText()
                .replaceFirst("\"version\":5", "\"version\":3")
                .replace(Regex(",\"sessionKeyId\":\"[0-9a-f]{64}\""), "")
                .replace(",\"cancelStoppedAt\":null", "")
                .replace(",\"cancelDispatchedAt\":null", ""),
        )

        val loaded = YpsoBolusAttemptFileStore(file).load()

        assertEquals(current.copy(sessionKeyId = null), loaded)
        assertEquals(null, YpsoBolusAttemptFileStore(file).recoveryEvidence()?.attempt?.sessionKeyId)
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

    @Test
    fun `legacy singleton journal migrates to multi attempt storage on next commit`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        val store = YpsoBolusAttemptFileStore(file)
        val legacy = attempt().copy(requestId = "legacy", dispatchCounter = 4_810, dispatchedAt = 2_000)
        store.commit(legacy)
        val singleton = JSONObject(file.readText()).getJSONArray("attempts").getJSONObject(0).toString()
        file.writeText(singleton)

        store.commit(attempt().copy(requestId = "next", createdAt = 3_000))

        assertEquals(listOf("legacy", "next"), store.loadAll().map { it.requestId })
        assertEquals(6, JSONObject(file.readText()).getInt("version"))
    }

    private fun version2Json() = """
        {"version":2,"requestId":"request-1","pumpSerial":"10000001","sessionGeneration":"generation-1",
        "treatment":"NORMAL","requestedCentiUnits":100,"payloadHash":"${"ab".repeat(32)}",
        "createdAt":1500,"outcome":"PROVEN_REJECTED","dispatchCounter":4810,"dispatchedAt":2000,
        "pumpFastSequence":null,"pumpHistoryId":null,"confirmedCentiUnits":null,"deliveryTimestamp":null,
        "cancelRequestId":null,"cancelCounter":null,"detail":"pre-upgrade rejection",
        "baseline":{"fastSequence":44,"slowSequence":9,"historyPumpId":100,
        "historyFingerprintHigh":10,"historyFingerprintLow":20,"pumpReboot":21,"observedAt":1000}}
    """.trimIndent()

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
