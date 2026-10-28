package app.aaps.pump.ypsopump

import app.aaps.core.data.model.BS
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryState
import app.aaps.pump.ypsopump.history.YpsoHistoryStateFileStore
import app.aaps.pump.ypsopump.history.YpsoMutableHistoryState
import app.aaps.pump.ypsopump.history.YpsoPendingBolusSync
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class YpsoHistoryStateStoreTest {
    @Test
    fun `cursor mutable state and pending DB intent survive restart`() {
        val file = Files.createTempDirectory("ypso-history").resolve("state.json").toFile()
        val row = YpsoHistoryEntry(100, 9, 150, 15, 0, 50, 2)
        val identity = YpsoEventIdentity("10000001", 3, 50)
        val expected = YpsoHistoryState(
            YpsoHistoryCursor(
                identity,
                row.fingerprint(),
                21,
                YpsoMutableHistoryState(identity, row.fingerprint(), row.stateFingerprint(), 150, 15),
            ),
            YpsoPendingBolusSync("10000001", (3L shl 32) or 51, 1_700_000_000_000, 100, 51, BS.Type.SMB),
        )

        YpsoHistoryStateFileStore(file).commit(expected)

        assertEquals(expected, YpsoHistoryStateFileStore(file).load())
    }

    @Test
    fun `unknown fields fail closed`() {
        val file = Files.createTempDirectory("ypso-history").resolve("state.json").toFile()
        val store = YpsoHistoryStateFileStore(file)
        store.commit(YpsoHistoryState())
        file.writeText(file.readText().replaceFirst("{", "{\"unknown\":1,"))
        assertThrows(IllegalArgumentException::class.java) { store.load() }
    }
}
