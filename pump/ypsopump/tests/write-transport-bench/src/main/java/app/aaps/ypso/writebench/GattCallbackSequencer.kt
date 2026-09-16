package app.aaps.ypso.writebench

/**
 * Defers processing until the platform GATT callback that selected it has returned.
 *
 * The callback and [post] queue must use the same serialized Android [android.os.Handler].
 */
internal class GattCallbackSequencer(
    private val post: (Runnable) -> Boolean,
) {
    private var platformCallbackActive = false

    internal fun afterPlatformCallback(action: () -> Unit) {
        check(!platformCallbackActive) { "Nested platform GATT callback" }
        platformCallbackActive = true
        try {
            check(
                post(
                    Runnable {
                        check(!platformCallbackActive) { "Follow-up GATT operation ran before its callback returned" }
                        action()
                    },
                ),
            ) { "Failed to queue follow-up GATT operation" }
        } finally {
            platformCallbackActive = false
        }
    }
}
