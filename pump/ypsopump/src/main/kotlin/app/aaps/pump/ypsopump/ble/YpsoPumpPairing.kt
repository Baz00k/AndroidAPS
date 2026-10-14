package app.aaps.pump.ypsopump.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What Android reports about this phone's Bluetooth bond with one address. */
internal enum class YpsoBondState { BLUETOOTH_OFF, PERMISSION_REQUIRED, NOT_BONDED, BONDING, BONDED }

/** Android's public bonding API. It never touches the pump session or its key. */
internal interface YpsoBondPort {
    fun bondState(mac: String): YpsoBondState

    /** Asks Android to start bonding; false when it refused. Android shows its own confirmation. */
    fun createBond(mac: String): Boolean

    /** Reports bond changes for every device until closed. */
    fun observe(listener: (mac: String, previous: YpsoBondState, current: YpsoBondState) -> Unit): AutoCloseable
}

/**
 * One person-initiated Bluetooth pairing at a time. Android's acceptance of the request is only
 * [Phase.WAITING]: the attempt is [Phase.PAIRED] once Android reports the bond. A bond is not a
 * working pump connection; the caller's follow-up runs the usual session checks.
 */
@Singleton
class YpsoPumpPairing internal constructor(
    private val port: YpsoBondPort,
    private val scheduler: YpsoLinkScheduler,
    private val timeoutMs: Long = TIMEOUT_MS,
) {

    @Inject constructor(context: Context) : this(YpsoAndroidBondPort(context.applicationContext), YpsoWatchdogScheduler())

    internal enum class Phase { WAITING, PAIRED, NOT_COMPLETED, TIMED_OUT, NOT_STARTED, BLUETOOTH_OFF, PERMISSION_REQUIRED }

    internal data class Attempt(val id: Long, val mac: String, val phase: Phase)

    private val lock = Any()
    private var nextId = 0L
    private var observation: AutoCloseable? = null
    private var timeout: Runnable? = null
    private var afterBond: (() -> Unit)? = null
    private val latest = MutableStateFlow<Attempt?>(null)

    /** The latest attempt, kept after it ends so the outcome stays visible. */
    internal val attempt: StateFlow<Attempt?> = latest.asStateFlow()

    internal fun bondState(mac: String): YpsoBondState = port.bondState(mac)

    /**
     * Asks Android to pair with [mac], unless an attempt is already waiting. [onBonded] runs once, on
     * the scheduler's thread, when Android reports the bond, unless another attempt has started since.
     */
    internal fun start(mac: String, onBonded: () -> Unit) {
        synchronized(lock) {
            if (latest.value?.phase == Phase.WAITING) return
            val id = ++nextId
            when (port.bondState(mac)) {
                YpsoBondState.BLUETOOTH_OFF -> return publish(Attempt(id, mac, Phase.BLUETOOTH_OFF))
                YpsoBondState.PERMISSION_REQUIRED -> return publish(Attempt(id, mac, Phase.PERMISSION_REQUIRED))
                YpsoBondState.BONDED -> {
                    publish(Attempt(id, mac, Phase.PAIRED))
                    return runAfterBond(id, onBonded)
                }
                YpsoBondState.NOT_BONDED, YpsoBondState.BONDING -> Unit
            }
            afterBond = onBonded
            publish(Attempt(id, mac, Phase.WAITING))
            // Listen before asking, so a quick answer from Android cannot be missed.
            observation = runCatching { port.observe { changed, previous, current -> onBondChanged(id, changed, previous, current) } }
                .getOrElse { return finish(id, Phase.NOT_STARTED) }
            timeout = Runnable { onTimeout(id) }.also { scheduler.schedule(it, timeoutMs) }
            if (runCatching { port.createBond(mac) }.getOrDefault(false)) return
            // A refused request still counts if Android is already bonding or has just bonded this pump.
            when (port.bondState(mac)) {
                YpsoBondState.BONDED -> finish(id, Phase.PAIRED)
                YpsoBondState.BONDING -> Unit
                YpsoBondState.PERMISSION_REQUIRED -> finish(id, Phase.PERMISSION_REQUIRED)
                YpsoBondState.BLUETOOTH_OFF -> finish(id, Phase.BLUETOOTH_OFF)
                YpsoBondState.NOT_BONDED -> finish(id, Phase.NOT_STARTED)
            }
        }
    }

    private fun onBondChanged(id: Long, mac: String, previous: YpsoBondState, current: YpsoBondState) {
        val target = synchronized(lock) { latest.value?.takeIf { it.id == id }?.mac } ?: return
        if (!mac.equals(target, ignoreCase = true)) return
        when {
            current == YpsoBondState.BONDED -> finish(id, Phase.PAIRED)
            // Public Android APIs do not say whether the person cancelled or pairing failed.
            current == YpsoBondState.NOT_BONDED && previous == YpsoBondState.BONDING -> finish(id, Phase.NOT_COMPLETED)
        }
    }

    private fun onTimeout(id: Long) {
        val target = synchronized(lock) { latest.value?.takeIf { it.id == id && it.phase == Phase.WAITING }?.mac } ?: return
        // A bond reported while the listener was closing still counts.
        val bonded = runCatching { port.bondState(target) }.getOrNull() == YpsoBondState.BONDED
        finish(id, if (bonded) Phase.PAIRED else Phase.TIMED_OUT)
    }

    private fun finish(id: Long, phase: Phase) {
        val followUp = synchronized(lock) {
            val current = latest.value
            if (current?.id != id || current.phase != Phase.WAITING) return
            timeout?.let(scheduler::cancel)
            timeout = null
            observation?.let { runCatching { it.close() } }
            observation = null
            val action = afterBond
            afterBond = null
            publish(current.copy(phase = phase))
            action.takeIf { phase == Phase.PAIRED }
        } ?: return
        runAfterBond(id, followUp)
    }

    // Held under the lock, so no later attempt can start until a follow-up still current has run.
    // The follow-up never calls back into pairing.
    private fun runAfterBond(id: Long, onBonded: () -> Unit) = scheduler.execute {
        synchronized(lock) {
            if (latest.value?.id == id) runCatching(onBonded)
        }
    }

    private fun publish(attempt: Attempt) {
        latest.value = attempt
    }

    companion object {
        /** Longer than Android's own pairing prompt, so Android normally reports the outcome first. */
        internal const val TIMEOUT_MS = 60_000L
    }
}

/** [YpsoBondPort] over Android's public Bluetooth API. */
internal class YpsoAndroidBondPort(private val context: Context) : YpsoBondPort {

    private fun permitted() = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun device(mac: String): BluetoothDevice? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return null
        return adapter.getRemoteDevice(mac)
    }

    @SuppressLint("MissingPermission")
    override fun bondState(mac: String): YpsoBondState {
        if (!permitted()) return YpsoBondState.PERMISSION_REQUIRED
        return try {
            device(mac)?.bondState?.let(::bondState) ?: YpsoBondState.BLUETOOTH_OFF
        } catch (_: SecurityException) {
            YpsoBondState.PERMISSION_REQUIRED
        }
    }

    @SuppressLint("MissingPermission")
    override fun createBond(mac: String): Boolean = permitted() && try {
        device(mac)?.createBond() == true
    } catch (_: SecurityException) {
        false
    }

    override fun observe(listener: (mac: String, previous: YpsoBondState, current: YpsoBondState) -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                listener(
                    device.address,
                    bondState(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)),
                    bondState(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)),
                )
            }
        }
        // Bluetooth sends this from its own app, which a non-exported receiver would not hear. Only the
        // system may send this protected action, so exporting the receiver admits no other sender.
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }

    private fun bondState(platform: Int): YpsoBondState = when (platform) {
        BluetoothDevice.BOND_BONDED -> YpsoBondState.BONDED
        BluetoothDevice.BOND_BONDING -> YpsoBondState.BONDING
        else -> YpsoBondState.NOT_BONDED
    }
}
