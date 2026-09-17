package app.aaps.ypso.writebench

import app.aaps.pump.ypsopump.ble.YpsoQualificationWritePolicy
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class YpsoQualificationWritePolicyTest {
    @Test
    fun `qualification selector allowlist cannot be widened by relabelling`() {
        assertTrue(
            YpsoQualificationWritePolicy.allowsSelector(
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                YpsoGlb.encode(1),
            ),
        )
        assertFalse(
            YpsoQualificationWritePolicy.allowsSelector(
                YpsoRemoteWrite.HISTORY_SELECTOR,
                UUID.fromString("669a0c20-0008-969e-e211-fcbee18b7bc5"),
                YpsoGlb.encode(1),
            ),
        )
        assertFalse(
            YpsoQualificationWritePolicy.allowsSelector(
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                YpsoGlb.encode(1),
            ),
        )
        assertFalse(
            YpsoQualificationWritePolicy.allowsSelector(
                YpsoRemoteWrite.THERAPY_COMMAND,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                YpsoGlb.encode(1),
            ),
        )
    }
}
