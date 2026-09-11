package app.aaps.pump.ypsopump.ble

/**
 * Connection and command readiness are intentionally separate. A successful status read alone does
 * not establish command authorization or required notification setup.
 */
internal class YpsoCommandReadiness {
    data class Owner(
        val gatt: Any,
        val connectionId: String,
        val generation: String,
    )

    data class Snapshot(
        val connected: Boolean,
        val authenticated: Boolean,
        val readVerified: Boolean,
        val requiredSetupVerified: Boolean,
        val counterCertain: Boolean,
        val commandReady: Boolean,
        val reason: String?,
    )

    private var owner: Owner? = null
    private var authenticated = false
    private var readVerified = false
    private var requiredSetupVerified = false

    @Synchronized
    fun connected(owner: Owner) {
        this.owner = owner
        authenticated = false
        readVerified = false
        requiredSetupVerified = false
    }

    @Synchronized
    fun authenticated(owner: Owner): Boolean = mutateOwned(owner) { authenticated = true }

    @Synchronized
    fun readVerified(owner: Owner): Boolean = mutateOwned(owner) { readVerified = true }

    @Synchronized
    fun requiredSetupVerified(owner: Owner): Boolean = mutateOwned(owner) { requiredSetupVerified = true }

    @Synchronized
    fun disconnected(gatt: Any) {
        if (owner?.gatt === gatt) clear()
    }

    @Synchronized
    fun clear() {
        owner = null
        authenticated = false
        readVerified = false
        requiredSetupVerified = false
    }

    @Synchronized
    fun snapshot(
        expected: Owner?,
        counterCertain: Boolean,
        setupRequired: Boolean,
    ): Snapshot {
        val connected = expected != null && sameOwner(owner, expected)
        val setup = !setupRequired || requiredSetupVerified
        val reason =
            when {
                !connected -> "connection owner changed"
                !authenticated -> "connection is not authenticated"
                !readVerified -> "current session has no verified encrypted read"
                !setup -> "required command setup is not verified"
                !counterCertain -> "write counter is uncertain"
                else -> null
            }
        return Snapshot(
            connected,
            connected && authenticated,
            connected && readVerified,
            connected && setup,
            counterCertain,
            reason == null,
            reason,
        )
    }

    private fun mutateOwned(
        expected: Owner,
        change: () -> Unit,
    ): Boolean {
        if (!sameOwner(owner, expected)) return false
        change()
        return true
    }

    private fun sameOwner(
        left: Owner?,
        right: Owner?,
    ): Boolean =
        left != null &&
            right != null &&
            left.gatt === right.gatt &&
            left.connectionId == right.connectionId &&
            left.generation == right.generation
}
