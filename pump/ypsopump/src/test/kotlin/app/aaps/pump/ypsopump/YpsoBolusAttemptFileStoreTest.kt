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
            detail = "awaiting terminal pump evidence",
        )

        YpsoBolusAttemptFileStore(file).commit(expected)

        assertEquals(expected, YpsoBolusAttemptFileStore(file).load())
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
    fun `unknown fields and malformed values fail closed`() {
        val directory = Files.createTempDirectory("ypso-bolus-store").toFile()
        val file = directory.resolve("attempt.json")
        val store = YpsoBolusAttemptFileStore(file)
        store.commit(attempt())
        file.writeText(file.readText().replaceFirst("{", "{\"unexpected\":1,"))
        assertThrows(IllegalArgumentException::class.java) { store.load() }

        store.commit(attempt())
        file.writeText(file.readText().replace("\"requestedCentiUnits\":100", "\"requestedCentiUnits\":0"))
        assertThrows(IllegalArgumentException::class.java) { store.load() }
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
        treatment = YpsoBolusTreatment.SMB,
        requestedCentiUnits = 100,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(44, 9, 100, 10, 20, 21, 1_000),
        createdAt = 1_500,
        shape = YpsoBolusShape.EXTENDED,
        durationMinutes = 15,
    )
}
