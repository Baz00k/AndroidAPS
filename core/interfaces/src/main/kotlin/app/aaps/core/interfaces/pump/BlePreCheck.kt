package app.aaps.core.interfaces.pump

import androidx.appcompat.app.AppCompatActivity

interface BlePreCheck {

    enum class Result {
        /** All prerequisites are met. */
        READY,

        /** Bluetooth is off. The user has been asked to enable it; check again when the screen resumes. */
        BLUETOOTH_DISABLED,

        /** A prerequisite failed and the user has been told why. */
        FAILED
    }

    fun prerequisitesCheck(activity: AppCompatActivity, additionalPermissions: List<String>? = null): Result

}
