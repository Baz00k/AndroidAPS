package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.tbr.YpsoTbrAttempt
import app.aaps.pump.ypsopump.tbr.YpsoTbrAttemptFileStore
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoTbrAttemptFileStoreTest {
    private val directory: File = Files.createTempDirectory("ypso-tbr").toFile()
    private val file = File(directory, "ypsopump-tbr-attempts.json")

    @Test
    fun `every field survives a round trip`() {
        val attempt = YpsoTbrAttempt(
            id = "a", kind = YpsoTbrAttempt.Kind.START, pumpSerial = "10054912", percent = 150, durationMinutes = 30,
            type = "NORMAL", temporaryId = 42L, baselinePumpId = 48_222L, createdAt = 1L, state = YpsoTbrAttempt.State.EFFECTIVE,
            dispatchedAt = 2L, effectiveAt = 3L, effectiveBy = 4L, accounted = true, stoppedAt = 6L,
            pumpId = 48_224L, detail = "d",
        )
        YpsoTbrAttemptFileStore(file).commitAll(listOf(attempt))

        assertEquals(listOf(attempt), YpsoTbrAttemptFileStore(file).loadAll())
    }

    @Test
    fun `a settled pre-release journal is archived and a new one starts`() {
        file.writeText("""{"version":1,"attempts":[{"state":"STARTED","pumpId":48224},{"state":"NOT_STARTED","pumpId":null}]}""")

        assertEquals(emptyList<YpsoTbrAttempt>(), YpsoTbrAttemptFileStore(file).loadAll())
        assertFalse(file.exists())
        assertTrue(File(directory, "ypsopump-tbr-attempts.json.v1").exists())
    }

    @Test
    fun `a pre-release journal with unresolved attempts stays unreadable`() {
        file.writeText("""{"version":2,"attempts":[{"state":"DISPATCHED","pumpId":null}]}""")

        assertTrue(runCatching { YpsoTbrAttemptFileStore(file).loadAll() }.isFailure)
        assertTrue(file.exists())
    }
}
