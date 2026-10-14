package app.aaps.pump.ypsopump.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.ParcelUuid
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A pump found nearby: its advertised name gave [serial], and its address is the one that serial implies. */
internal data class YpsoFoundPump(val serial: String, val mac: String)

/** Android's Bluetooth LE scanner, limited to the service YpsoPumps advertise. */
internal interface YpsoScanPort {
    sealed interface Start {
        class Started(val scan: AutoCloseable) : Start
        data object BluetoothOff : Start
        data object PermissionRequired : Start
        data object LocationOff : Start
        data object Failed : Start
    }

    /** Reports each advertisement's name and address until the returned scan is closed. */
    fun start(onResult: (name: String?, mac: String) -> Unit, onFailed: () -> Unit): Start
}

/**
 * One person-started search for pumps. Only a pump whose advertised name and address name the same
 * serial is listed: an address alone never supplies a serial.
 */
@Singleton
class YpsoPumpScanner internal constructor(
    private val port: YpsoScanPort,
    private val scheduler: YpsoLinkScheduler,
    private val durationMs: Long = DURATION_MS,
) {

    @Inject constructor(context: Context) : this(YpsoAndroidScanPort(context.applicationContext), YpsoWatchdogScheduler())

    internal enum class Phase { SCANNING, FINISHED, FAILED, BLUETOOTH_OFF, PERMISSION_REQUIRED, LOCATION_OFF }

    internal data class Scan(val id: Long, val phase: Phase, val pumps: List<YpsoFoundPump> = emptyList())

    private val lock = Any()
    private var nextId = 0L
    private var running: AutoCloseable? = null
    private var timeout: Runnable? = null
    private val latest = MutableStateFlow<Scan?>(null)

    /** The latest search, kept after it ends so its results stay visible. */
    internal val scan: StateFlow<Scan?> = latest.asStateFlow()

    internal fun start() {
        synchronized(lock) {
            if (latest.value?.phase == Phase.SCANNING) return
            val id = ++nextId
            latest.value = Scan(id, Phase.SCANNING)
            val started = runCatching {
                port.start(onResult = { name, mac -> onResult(id, name, mac) }, onFailed = { finish(id, Phase.FAILED) })
            }.getOrDefault(YpsoScanPort.Start.Failed)
            when (started) {
                is YpsoScanPort.Start.Started -> {
                    // Android may report a failure before the scan handle is returned.
                    if (latest.value?.phase != Phase.SCANNING) {
                        runCatching { started.scan.close() }
                        return
                    }
                    running = started.scan
                    timeout = Runnable { finish(id, Phase.FINISHED) }.also { scheduler.schedule(it, durationMs) }
                }
                YpsoScanPort.Start.BluetoothOff -> finish(id, Phase.BLUETOOTH_OFF)
                YpsoScanPort.Start.PermissionRequired -> finish(id, Phase.PERMISSION_REQUIRED)
                YpsoScanPort.Start.LocationOff -> finish(id, Phase.LOCATION_OFF)
                YpsoScanPort.Start.Failed -> finish(id, Phase.FAILED)
            }
        }
    }

    /** Ends a running search and keeps what it found. */
    internal fun stop() {
        synchronized(lock) { latest.value?.takeIf { it.phase == Phase.SCANNING }?.let { finish(it.id, Phase.FINISHED) } }
    }

    private fun onResult(id: Long, name: String?, mac: String) {
        val serial = PumpIdentity.serialFromDeviceName(name) ?: return
        val found = runCatching { YpsoFoundPump(serial, PumpIdentity.normalizeMac(mac).also { PumpIdentity.validatePair(serial, it) }) }
            .getOrNull() ?: return
        synchronized(lock) {
            val current = latest.value?.takeIf { it.id == id && it.phase == Phase.SCANNING } ?: return
            if (found !in current.pumps) latest.value = current.copy(pumps = (current.pumps + found).sortedBy { it.serial })
        }
    }

    private fun finish(id: Long, phase: Phase) {
        synchronized(lock) {
            val current = latest.value?.takeIf { it.id == id && it.phase == Phase.SCANNING } ?: return
            timeout?.let(scheduler::cancel)
            timeout = null
            running?.let { runCatching { it.close() } }
            running = null
            latest.value = current.copy(phase = phase)
        }
    }

    companion object {
        internal const val DURATION_MS = 30_000L
    }
}

/** [YpsoScanPort] over Android's public Bluetooth LE scanner. */
internal class YpsoAndroidScanPort(private val context: Context) : YpsoScanPort {

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun start(onResult: (name: String?, mac: String) -> Unit, onFailed: () -> Unit): YpsoScanPort.Start {
        // Without disavowing location in the manifest, Android withholds scan results from an app that lacks
        // location access or while Location is off, so both are checked rather than reported as no pump.
        if (!granted(Manifest.permission.BLUETOOTH_SCAN) || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return YpsoScanPort.Start.PermissionRequired
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return YpsoScanPort.Start.BluetoothOff
        if ((context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.isLocationEnabled != true) return YpsoScanPort.Start.LocationOff
        val scanner = adapter.bluetoothLeScanner ?: return YpsoScanPort.Start.BluetoothOff
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = report(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::report)
            override fun onScanFailed(errorCode: Int) = onFailed()

            private fun report(result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                onResult(name, result.device.address)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ADVERTISED_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(listOf(filter), settings, callback)
            YpsoScanPort.Start.Started { runCatching { scanner.stopScan(callback) } }
        } catch (_: SecurityException) {
            YpsoScanPort.Start.PermissionRequired
        }
    }

    companion object {
        /** The service UUID YpsoPumps advertise; the genuine app scans for it. */
        val ADVERTISED_SERVICE: UUID = UUID.fromString("669a0c20-0008-969e-e211-ffffffffffff")
    }
}
