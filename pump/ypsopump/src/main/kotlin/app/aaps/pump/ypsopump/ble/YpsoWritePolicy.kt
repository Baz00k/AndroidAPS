package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoGlb
import java.util.UUID

internal enum class YpsoRemoteWrite {
    AUTHENTICATION,
    HISTORY_SELECTOR,
    SETTINGS_SELECTOR,
    THERAPY_COMMAND,
    CONFIGURATION_MUTATION,
    CONTROL_NOTIFICATION_DESCRIPTOR
}

internal object YpsoWritePolicy {
    val AUTH_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
    val EVENT_INDEX_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecc3b7bc5")
    val ALARM_COUNT_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbec83b7bc5")
    val ALARM_INDEX_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbec93b7bc5")
    val SYSTEM_COUNT_UUID: UUID = UUID.fromString("86a5a431-d442-2c8d-304b-19ee355571fc")
    val SYSTEM_INDEX_UUID: UUID = UUID.fromString("381ddce9-e934-b4ae-e345-eb87283db426")
    // Conflicting complaint-index descriptions have no independently verified UUID; fail closed.
    val SETTING_ID_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb3147bc5")
    val CONTROL_NOTIFY_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee58b7bc5")
    val BOLUS_START_STOP_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee18b7bc5")
    val TBR_START_STOP_UUID: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee38b7bc5")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun allowsCharacteristic(
        write: YpsoRemoteWrite,
        destination: UUID,
        payload: ByteArray,
        expectedAuthentication: ByteArray,
        authenticating: Boolean
    ): Boolean = when (write) {
        YpsoRemoteWrite.AUTHENTICATION -> destination == AUTH_UUID && authenticating &&
            payload.size == 16 && payload.contentEquals(expectedAuthentication)
        YpsoRemoteWrite.HISTORY_SELECTOR -> destination == EVENT_INDEX_UUID &&
            YpsoGlb.decodeExact(payload)?.let { it >= 0 } == true
        YpsoRemoteWrite.SETTINGS_SELECTOR -> destination == SETTING_ID_UUID &&
            YpsoGlb.decodeExact(payload)?.let { settingId ->
                settingId == 1 || settingId in 14..61
            } == true
        YpsoRemoteWrite.THERAPY_COMMAND,
        YpsoRemoteWrite.CONFIGURATION_MUTATION,
        YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR -> false
    }

    fun allowsDescriptor(
        write: YpsoRemoteWrite,
        characteristic: UUID?,
        descriptor: UUID,
        payload: ByteArray
    ): Boolean = write == YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR &&
        characteristic == CONTROL_NOTIFY_UUID && descriptor == CCCD_UUID &&
        payload.contentEquals(byteArrayOf(1, 0))

    fun allows(write: YpsoRemoteWrite): Boolean = when (write) {
        YpsoRemoteWrite.AUTHENTICATION -> true
        YpsoRemoteWrite.HISTORY_SELECTOR,
        YpsoRemoteWrite.SETTINGS_SELECTOR,
        YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR ->
            true
        YpsoRemoteWrite.THERAPY_COMMAND,
        YpsoRemoteWrite.CONFIGURATION_MUTATION -> false
    }
}
