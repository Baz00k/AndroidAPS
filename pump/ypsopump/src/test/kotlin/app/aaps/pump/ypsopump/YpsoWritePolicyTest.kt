package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoWritePolicyTest {
    @Test
    fun `distributed artifact permits authentication read selectors and their setup only`() {
        val permitted = YpsoRemoteWrite.entries.filter { YpsoWritePolicy.allows(it) }

        assertEquals(
            listOf(
                YpsoRemoteWrite.AUTHENTICATION,
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
            ),
            permitted,
        )
        assertTrue(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                YpsoGlb.encode(0),
                byteArrayOf(),
                false,
            ),
        )
        assertFalse(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoWritePolicy.ALARM_INDEX_UUID,
                YpsoGlb.encode(0),
                byteArrayOf(),
                false,
            ),
        )
        assertTrue(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoWritePolicy.SETTING_ID_UUID,
                YpsoGlb.encode(1),
                byteArrayOf(),
                false,
            ),
        )
        assertTrue(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoWritePolicy.SETTING_ID_UUID,
                YpsoGlb.encode(61),
                byteArrayOf(),
                false,
            ),
        )
        assertFalse(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoWritePolicy.SETTING_ID_UUID,
                YpsoGlb.encode(62),
                byteArrayOf(),
                false,
            ),
        )
    }

    @Test
    fun `only control notification CCCD enable is a production descriptor write`() {
        assertTrue(
            YpsoWritePolicy.allowsDescriptor(
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                YpsoWritePolicy.CCCD_UUID,
                byteArrayOf(1, 0)
            )
        )
        assertFalse(
            YpsoWritePolicy.allowsDescriptor(
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                YpsoWritePolicy.CCCD_UUID,
                byteArrayOf(0, 0)
            )
        )
    }
}
