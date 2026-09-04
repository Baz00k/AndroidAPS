package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoWritePolicyTest {

    @Test
    fun `status viewer build remains read only`() {
        assertTrue(YpsoPumpConst.READ_ONLY_MODE)
    }

    @Test
    fun `read only permits authentication only`() {
        val permitted = YpsoRemoteWrite.entries.filter { YpsoWritePolicy.allows(it, readOnly = true) }

        assertEquals(listOf(YpsoRemoteWrite.AUTHENTICATION), permitted)
    }

    @Test
    fun `write enabled mode permits every classified write`() {
        val permitted = YpsoRemoteWrite.entries.filter { YpsoWritePolicy.allows(it, readOnly = false) }

        assertEquals(YpsoRemoteWrite.entries, permitted)
    }
}
