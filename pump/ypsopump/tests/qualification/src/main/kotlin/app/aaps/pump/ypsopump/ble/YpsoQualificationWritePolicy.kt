package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoGlb
import java.util.UUID

/** Executable selector policy compiled only into the standalone qualification APK. */
internal object YpsoQualificationWritePolicy {
    fun allowsSelector(write: YpsoRemoteWrite, destination: UUID, payload: ByteArray): Boolean =
        when (write) {
            YpsoRemoteWrite.HISTORY_SELECTOR ->
                destination in setOf(
                    YpsoWritePolicy.EVENT_INDEX_UUID,
                    YpsoWritePolicy.ALARM_INDEX_UUID,
                    YpsoWritePolicy.SYSTEM_INDEX_UUID,
                ) && YpsoGlb.decodeExact(payload) != null
            YpsoRemoteWrite.SETTINGS_SELECTOR ->
                destination == YpsoWritePolicy.SETTING_ID_UUID &&
                    YpsoGlb.decodeExact(payload)?.let { it >= 0 } == true
            else -> false
        }
}
