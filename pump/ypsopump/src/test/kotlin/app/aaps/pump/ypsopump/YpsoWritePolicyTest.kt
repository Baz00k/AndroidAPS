package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoArtifactPolicy
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoGlb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class YpsoWritePolicyTest {
    @Test
    fun `distributed artifact permits authentication only`() {
        val permitted = YpsoRemoteWrite.entries.filter { YpsoWritePolicy.allows(it, YpsoArtifactPolicy.STATUS_ONLY) }

        assertEquals(listOf(YpsoRemoteWrite.AUTHENTICATION), permitted)
    }

    @Test
    fun `bench artifact permits only selectors and their required setup`() {
        val permitted = YpsoRemoteWrite.entries.filter { YpsoWritePolicy.allows(it, YpsoArtifactPolicy.NON_THERAPY_BENCH) }

        assertEquals(
            listOf(
                YpsoRemoteWrite.AUTHENTICATION,
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR
            ),
            permitted
        )
        assertFalse(YpsoWritePolicy.allows(YpsoRemoteWrite.THERAPY_COMMAND, YpsoArtifactPolicy.NON_THERAPY_BENCH))
        assertFalse(YpsoWritePolicy.allows(YpsoRemoteWrite.CONFIGURATION_MUTATION, YpsoArtifactPolicy.NON_THERAPY_BENCH))
    }

    @Test
    fun `bench characteristic allowlist cannot be widened by relabelling`() {
        val auth = ByteArray(16) { 1 }
        assertTrue(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoArtifactPolicy.NON_THERAPY_BENCH,
                YpsoRemoteWrite.HISTORY_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                YpsoGlb.encode(1),
                auth,
                false
            )
        )
        assertFalse(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoArtifactPolicy.NON_THERAPY_BENCH,
                YpsoRemoteWrite.HISTORY_SELECTOR,
                UUID.fromString("669a0c20-0008-969e-e211-fcbee18b7bc5"),
                YpsoGlb.encode(1),
                auth,
                false
            )
        )
        assertFalse(
            YpsoWritePolicy.allowsCharacteristic(
                YpsoArtifactPolicy.NON_THERAPY_BENCH,
                YpsoRemoteWrite.SETTINGS_SELECTOR,
                YpsoWritePolicy.EVENT_INDEX_UUID,
                YpsoGlb.encode(1),
                auth,
                false
            )
        )
    }

    @Test
    fun `only control notification CCCD enable is a bench descriptor write`() {
        assertTrue(
            YpsoWritePolicy.allowsDescriptor(
                YpsoArtifactPolicy.NON_THERAPY_BENCH,
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                YpsoWritePolicy.CCCD_UUID,
                byteArrayOf(1, 0)
            )
        )
        assertFalse(
            YpsoWritePolicy.allowsDescriptor(
                YpsoArtifactPolicy.NON_THERAPY_BENCH,
                YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR,
                YpsoWritePolicy.CONTROL_NOTIFY_UUID,
                YpsoWritePolicy.CCCD_UUID,
                byteArrayOf(0, 0)
            )
        )
    }
}
