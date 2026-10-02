package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoWritePolicyTest {
    @Test
    fun `content policy never authorizes therapy or pump configuration writes`() {
        listOf(
            YpsoRemoteWrite.THERAPY_COMMAND to YpsoWritePolicy.BOLUS_START_STOP_UUID,
            YpsoRemoteWrite.THERAPY_COMMAND to YpsoWritePolicy.TBR_START_STOP_UUID,
            YpsoRemoteWrite.CONFIGURATION_MUTATION to YpsoWritePolicy.SETTING_ID_UUID,
        ).forEach { (write, destination) ->
            assertFalse(YpsoWritePolicy.allowsCharacteristic(write, destination, ByteArray(16), ByteArray(16), true))
        }
    }

    @Test
    fun `selector policy accepts only the history index and known setting ids`() {
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
