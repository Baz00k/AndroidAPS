package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Blocking pump operations used by [YpsoBolusDelivery]. */
internal interface YpsoBolusLink {
    /** The connection one bolus or cancel write was dispatched on; status read through it is that command's. */
    interface Owner {
        val connectionKey: String
    }

    /** The active session's key identity and the pump reboot generation its replies carry. */
    data class Session(val keyId: String, val reboot: Int?)

    enum class Readiness { READY, CANCELLED, HISTORY_BUSY, STATUS_UNAVAILABLE }

    val isConnected: Boolean

    /** Takes the link from background history and reads fresh pump status; [stopWhen] abandons the wait. */
    fun therapyStatus(stopWhen: () -> Boolean): Readiness

    /** Lets background history step aside at a selector-safe boundary; false when it still owns the link. */
    fun yieldHistory(): Boolean

    /** Whether fresh status shows the pump stopped or its reservoir empty. */
    fun stoppedOrEmpty(): Boolean

    /** Starts background history recovery without waiting for it. */
    fun reconcileHistoryLater(reason: String)

    /** Replaces a lost link; true once it is connected again within [timeoutMs]. */
    fun reconnect(timeoutMs: Long): Boolean

    fun session(): Session?

    /** Generation of the active session record, which bolus attempts are bound to. */
    fun sessionGeneration(): String?

    /** Identity of the current authenticated connection, or null without one. */
    fun connectionKey(): String?

    /** One bolus status read, on [owner]'s connection when given; null when it could not be read. */
    fun status(owner: Owner? = null): BolusCommand?

    /**
     * Writes one bolus command on [expectedConnection]. [beforeDispatch] must durably record the write
     * counter before anything can leave the phone. Throws when no outcome arrives.
     */
    fun start(
        writeId: String,
        request: YpsoValidatedBolusRequest,
        expectedConnection: String,
        beforeDispatch: (counter: Long) -> Unit,
    ): Pair<YpsoWriteOutcome, Owner?>

    /** Writes a cancel for [block], like [start]. */
    fun cancel(
        writeId: String,
        block: YpsoBolusBlock,
        expectedConnection: String,
        beforeDispatch: (counter: Long) -> Unit,
    ): Pair<YpsoWriteOutcome, Owner?>

    /** Records that status proved the write took effect, releasing write ownership. */
    fun verifyAccepted(owner: Owner, writeId: String, evidenceHash: String, detail: String): Boolean

    /** Records that the write's effect stays unproven, releasing write ownership. */
    fun recordUnresolved(owner: Owner, writeId: String, evidenceHash: String, detail: String): Boolean
}

/** [YpsoBolusLink] over the production BLE manager; the plugin supplies the link handover around it. */
internal abstract class YpsoBolusBleLink(private val bleManager: YpsoBleManager) : YpsoBolusLink {

    private class BleOwner(val owner: YpsoBleManager.BolusCommandOwner, override val connectionKey: String) : YpsoBolusLink.Owner

    override val isConnected: Boolean get() = bleManager.isConnected

    override fun session(): YpsoBolusLink.Session? =
        bleManager.session?.snapshot()?.let { YpsoBolusLink.Session(it.keyId, it.reboot) }

    override fun sessionGeneration(): String? = bleManager.session?.activeRecord()?.generation

    override fun connectionKey(): String? = bleManager.currentBolusConnectionKey()

    override fun status(owner: YpsoBolusLink.Owner?): BolusCommand? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<BolusCommand?>()
        val onResult: (BolusCommand?) -> Unit = { result.set(it); latch.countDown() }
        if (owner == null) bleManager.readBolusStatus(onResult) else bleManager.readBolusStatus((owner as BleOwner).owner, onResult)
        return if (latch.await(STATUS_TIMEOUT_S, TimeUnit.SECONDS)) result.get() else null
    }

    override fun start(
        writeId: String,
        request: YpsoValidatedBolusRequest,
        expectedConnection: String,
        beforeDispatch: (counter: Long) -> Unit,
    ) = awaitWrite { callback ->
        bleManager.startBolus(writeId, request, expectedConnection, { beforeDispatch(it.counter) }, callback)
    }

    override fun cancel(
        writeId: String,
        block: YpsoBolusBlock,
        expectedConnection: String,
        beforeDispatch: (counter: Long) -> Unit,
    ) = awaitWrite { callback ->
        bleManager.cancelBolus(writeId, block, expectedConnection, { beforeDispatch(it.counter) }, callback)
    }

    override fun verifyAccepted(owner: YpsoBolusLink.Owner, writeId: String, evidenceHash: String, detail: String) =
        bleManager.verifyBolusAccepted((owner as BleOwner).owner, writeId, evidenceHash, detail)

    override fun recordUnresolved(owner: YpsoBolusLink.Owner, writeId: String, evidenceHash: String, detail: String) =
        bleManager.recordBolusUnresolved((owner as BleOwner).owner, writeId, evidenceHash, detail)

    private fun awaitWrite(
        dispatch: ((YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?) -> Unit) -> Unit,
    ): Pair<YpsoWriteOutcome, YpsoBolusLink.Owner?> {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Pair<YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?>?>()
        dispatch { outcome, owner ->
            value.set(outcome to owner)
            latch.countDown()
        }
        if (!latch.await(WRITE_CALLBACK_TIMEOUT_S, TimeUnit.SECONDS)) error("bolus write callback did not arrive within 45 seconds")
        val (outcome, owner) = requireNotNull(value.get())
        return outcome to owner?.let { BleOwner(it, bleManager.connectionKey(it)) }
    }

    private companion object {
        const val WRITE_CALLBACK_TIMEOUT_S = 45L
        const val STATUS_TIMEOUT_S = 15L
    }
}
