package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.YpsoPumpConst
import java.util.UUID

internal enum class YpsoRemoteWrite {
    AUTHENTICATION,
    COMMAND_CHARACTERISTIC,
    CONTROL_NOTIFICATION_DESCRIPTOR
}

internal object YpsoWritePolicy {

    private val authUuid = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")

    fun allowsCharacteristic(
        write: YpsoRemoteWrite,
        destination: UUID,
        payload: ByteArray,
        expectedAuthentication: ByteArray,
        authenticating: Boolean
    ): Boolean = write == YpsoRemoteWrite.AUTHENTICATION && destination == authUuid &&
        authenticating && payload.size == 16 && payload.contentEquals(expectedAuthentication)

    fun allows(write: YpsoRemoteWrite, readOnly: Boolean = YpsoPumpConst.READ_ONLY_MODE): Boolean =
        !readOnly || write == YpsoRemoteWrite.AUTHENTICATION
}
