package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.YpsoPumpConst

internal enum class YpsoRemoteWrite {
    AUTHENTICATION,
    COMMAND_CHARACTERISTIC,
    CONTROL_NOTIFICATION_DESCRIPTOR
}

internal object YpsoWritePolicy {

    fun allows(write: YpsoRemoteWrite, readOnly: Boolean = YpsoPumpConst.READ_ONLY_MODE): Boolean =
        !readOnly || write == YpsoRemoteWrite.AUTHENTICATION
}
