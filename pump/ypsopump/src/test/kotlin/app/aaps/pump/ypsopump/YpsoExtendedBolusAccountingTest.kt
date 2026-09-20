package app.aaps.pump.ypsopump

import app.aaps.core.data.model.EB
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.ypsopump.bolus.YpsoExtendedBolusAccounting
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
    fun `missing or mismatched persisted record is rejected`() {
        assertFalse(YpsoExtendedBolusAccounting.matches(null, 42L, 1_000L, 1.2, 900_000L, "serial"))
        assertFalse(YpsoExtendedBolusAccounting.matches(expected, 43L, 1_000L, 1.2, 900_000L, "serial"))
        assertFalse(YpsoExtendedBolusAccounting.matches(expected.copy(duration = 1L), 42L, 1_000L, 1.2, 900_000L, "serial"))
        assertFalse(YpsoExtendedBolusAccounting.matches(expected.copy(isValid = false), 42L, 1_000L, 1.2, 900_000L, "serial"))
    }
}
