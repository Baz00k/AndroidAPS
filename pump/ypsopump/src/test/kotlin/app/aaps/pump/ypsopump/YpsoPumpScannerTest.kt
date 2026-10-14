package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoFoundPump
import app.aaps.pump.ypsopump.ble.YpsoPumpScanner
import app.aaps.pump.ypsopump.ble.YpsoPumpScanner.Phase
import app.aaps.pump.ypsopump.ble.YpsoScanPort
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** A search lists only pumps whose advertised name and address agree, and stops on its own. */
class YpsoPumpScannerTest {

    private class FakeScanPort : YpsoScanPort {
        var answer: YpsoScanPort.Start? = null
        var starts = 0
        var closed = 0
        var onResult: ((String?, String) -> Unit)? = null
        var onFailed: (() -> Unit)? = null

        override fun start(onResult: (String?, String) -> Unit, onFailed: () -> Unit): YpsoScanPort.Start {
            starts++
            this.onResult = onResult
            this.onFailed = onFailed
            return answer ?: YpsoScanPort.Start.Started { closed++ }
        }
    }

    private val port = FakeScanPort()
    private val scheduler = ManualScheduler()
    private val scanner = YpsoPumpScanner(port, scheduler, durationMs = 30_000)

    private fun advertise(name: String?, mac: String) = port.onResult!!(name, mac)

    @Test
    fun `only pumps whose advertised name gives the serial their address implies are listed`() {
        scanner.start()

        advertise("mylife YpsoPump 000001", "ec:2a:f0:00:00:01")
        advertise("mylife YpsoPump 000001", "EC:2A:F0:00:00:01")
        // An address alone never supplies a serial.
        advertise(null, "EC:2A:F0:00:00:02")
        advertise("Some watch", "EC:2A:F0:00:00:03")
        // A name and an address that identify different pumps.
        advertise("mylife YpsoPump 000004", "EC:2A:F0:00:00:05")
        advertise("YpsoPump_10012345", "EC:2A:F0:00:30:39")

        val scan = scanner.scan.value!!
        assertThat(scan.phase).isEqualTo(Phase.SCANNING)
        assertThat(scan.pumps).containsExactly(
            YpsoFoundPump("10000001", "EC:2A:F0:00:00:01"),
            YpsoFoundPump("10012345", "EC:2A:F0:00:30:39"),
        ).inOrder()
    }

    @Test
    fun `the name a pump shows in Android's Bluetooth settings is recognised`() {
        scanner.start()
        // 123456 is 0x01E240.
        advertise("YpsoPump_10123456", "EC:2A:F0:01:E2:40")
        advertise("YpsoPump_10123456", "EC:2A:F0:01:E2:41")

        assertThat(scanner.scan.value!!.pumps).containsExactly(YpsoFoundPump("10123456", "EC:2A:F0:01:E2:40"))
    }

    @Test
    fun `a search ends after its duration and keeps what it found`() {
        scanner.start()
        advertise("mylife YpsoPump 000001", "EC:2A:F0:00:00:01")
        assertThat(scheduler.scheduled.values.single()).isEqualTo(30_000L)

        scheduler.fire()

        assertThat(scanner.scan.value!!.phase).isEqualTo(Phase.FINISHED)
        assertThat(scanner.scan.value!!.pumps).hasSize(1)
        assertThat(port.closed).isEqualTo(1)
        // Late advertisements no longer change the list.
        advertise("mylife YpsoPump 000002", "EC:2A:F0:00:00:02")
        assertThat(scanner.scan.value!!.pumps).hasSize(1)
    }

    @Test
    fun `stopping ends the search at once`() {
        scanner.start()
        scanner.stop()

        assertThat(scanner.scan.value!!.phase).isEqualTo(Phase.FINISHED)
        assertThat(port.closed).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `a second start while searching is ignored and a new search starts empty`() {
        scanner.start()
        advertise("mylife YpsoPump 000001", "EC:2A:F0:00:00:01")
        scanner.start()
        assertThat(port.starts).isEqualTo(1)

        scanner.stop()
        val stale = port.onResult!!
        scanner.start()
        stale("mylife YpsoPump 000002", "EC:2A:F0:00:00:02")

        assertThat(port.starts).isEqualTo(2)
        assertThat(scanner.scan.value!!.pumps).isEmpty()
    }

    @Test
    fun `Android's refusals and failures are reported, not shown as no pump`() {
        val refusals = mapOf(
            YpsoScanPort.Start.BluetoothOff to Phase.BLUETOOTH_OFF,
            YpsoScanPort.Start.PermissionRequired to Phase.PERMISSION_REQUIRED,
            YpsoScanPort.Start.LocationOff to Phase.LOCATION_OFF,
            YpsoScanPort.Start.Failed to Phase.FAILED,
        )
        refusals.forEach { (answer, phase) ->
            port.answer = answer
            scanner.start()
            assertThat(scanner.scan.value!!.phase).isEqualTo(phase)
        }
        assertThat(scheduler.scheduled).isEmpty()

        port.answer = null
        scanner.start()
        port.onFailed!!()
        assertThat(scanner.scan.value!!.phase).isEqualTo(Phase.FAILED)
        assertThat(port.closed).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `a failure Android reports while starting leaves no scan running`() {
        val failing = object : YpsoScanPort {
            var closed = 0
            override fun start(onResult: (String?, String) -> Unit, onFailed: () -> Unit): YpsoScanPort.Start {
                onFailed()
                return YpsoScanPort.Start.Started { closed++ }
            }
        }
        val scanner = YpsoPumpScanner(failing, scheduler)
        scanner.start()

        assertThat(scanner.scan.value!!.phase).isEqualTo(Phase.FAILED)
        assertThat(failing.closed).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()
    }
}
