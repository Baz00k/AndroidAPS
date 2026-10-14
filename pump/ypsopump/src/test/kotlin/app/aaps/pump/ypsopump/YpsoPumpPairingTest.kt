package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBondPort
import app.aaps.pump.ypsopump.ble.YpsoBondState
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing.Phase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Pairing reports what Android's bond broadcasts establish, never Android's mere acceptance of the request. */
class YpsoPumpPairingTest {

    private val mac = "EC:2A:F0:00:00:01"
    private val otherMac = "EC:2A:F0:00:00:02"

    private class FakeBondPort : YpsoBondPort {
        var state = YpsoBondState.NOT_BONDED
        var accepts = true
        var bondRequests = 0
        var listener: ((String, YpsoBondState, YpsoBondState) -> Unit)? = null
        var closed = 0

        override fun bondState(mac: String) = state
        override fun createBond(mac: String): Boolean {
            bondRequests++
            return accepts
        }

        override fun observe(listener: (String, YpsoBondState, YpsoBondState) -> Unit): AutoCloseable {
            this.listener = listener
            return AutoCloseable { closed++; this.listener = null }
        }

        fun report(mac: String, previous: YpsoBondState, current: YpsoBondState) {
            state = current
            listener?.invoke(mac, previous, current)
        }
    }

    private val port = FakeBondPort()
    private val scheduler = ManualScheduler()
    private val pairing = YpsoPumpPairing(port, scheduler, timeoutMs = 60_000)
    private var followUps = 0

    private fun start(target: String = mac) = pairing.start(target) { followUps++ }

    @Test
    fun `an accepted request waits for Android's bond before reporting paired`() {
        start()

        assertThat(port.bondRequests).isEqualTo(1)
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.WAITING)
        port.report(mac, YpsoBondState.NOT_BONDED, YpsoBondState.BONDING)
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.WAITING)
        assertThat(followUps).isEqualTo(0)

        port.report(mac, YpsoBondState.BONDING, YpsoBondState.BONDED)

        assertThat(pairing.attempt.value).isEqualTo(YpsoPumpPairing.Attempt(1, mac, Phase.PAIRED))
        assertThat(followUps).isEqualTo(1)
        assertThat(port.closed).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `a bonding attempt that ends unbonded is not completed and checks nothing`() {
        start()
        port.report(mac, YpsoBondState.NOT_BONDED, YpsoBondState.BONDING)
        port.report(mac, YpsoBondState.BONDING, YpsoBondState.NOT_BONDED)

        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.NOT_COMPLETED)
        assertThat(followUps).isEqualTo(0)
        assertThat(port.closed).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun `changes for another device or without a bonding step leave the attempt waiting`() {
        start()
        port.report(otherMac, YpsoBondState.BONDING, YpsoBondState.BONDED)
        port.report(mac, YpsoBondState.BONDED, YpsoBondState.NOT_BONDED)

        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.WAITING)
        assertThat(followUps).isEqualTo(0)
    }

    @Test
    fun `no answer within the timeout is reported as timed out and a later bond checks nothing`() {
        start()
        val listener = port.listener!!
        port.report(mac, YpsoBondState.NOT_BONDED, YpsoBondState.BONDING)
        assertThat(scheduler.scheduled.values.single()).isEqualTo(60_000L)

        scheduler.fire()

        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.TIMED_OUT)
        assertThat(port.closed).isEqualTo(1)
        // A broadcast already in flight when the listener closed must not revive the attempt.
        listener(mac, YpsoBondState.BONDING, YpsoBondState.BONDED)
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.TIMED_OUT)
        assertThat(followUps).isEqualTo(0)
    }

    @Test
    fun `a bond Android finished without a broadcast counts at the timeout`() {
        start()
        port.state = YpsoBondState.BONDED

        scheduler.fire()

        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.PAIRED)
        assertThat(followUps).isEqualTo(1)
    }

    @Test
    fun `a refused request is not started unless Android is already bonding`() {
        port.accepts = false
        start()
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.NOT_STARTED)
        assertThat(port.closed).isEqualTo(1)
        assertThat(scheduler.scheduled).isEmpty()

        port.state = YpsoBondState.BONDING
        start()
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.WAITING)
        port.report(mac, YpsoBondState.BONDING, YpsoBondState.BONDED)
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.PAIRED)
    }

    @Test
    fun `Bluetooth off or a missing permission asks Android for nothing`() {
        port.state = YpsoBondState.BLUETOOTH_OFF
        start()
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.BLUETOOTH_OFF)

        port.state = YpsoBondState.PERMISSION_REQUIRED
        start()
        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.PERMISSION_REQUIRED)

        assertThat(port.bondRequests).isEqualTo(0)
        assertThat(port.listener).isNull()
        assertThat(followUps).isEqualTo(0)
    }

    @Test
    fun `a second request while one is waiting is ignored`() {
        start()
        start(otherMac)

        assertThat(port.bondRequests).isEqualTo(1)
        assertThat(pairing.attempt.value!!.mac).isEqualTo(mac)
    }

    @Test
    fun `an existing bond is reported paired and still checks the saved pump`() {
        port.state = YpsoBondState.BONDED
        start()

        assertThat(port.bondRequests).isEqualTo(0)
        assertThat(pairing.attempt.value).isEqualTo(YpsoPumpPairing.Attempt(1, mac, Phase.PAIRED))
        assertThat(followUps).isEqualTo(1)
    }

    @Test
    fun `a failing follow-up still ends the attempt as paired`() {
        pairing.start(mac) { error("queue unavailable") }
        port.report(mac, YpsoBondState.BONDING, YpsoBondState.BONDED)

        assertThat(pairing.attempt.value!!.phase).isEqualTo(Phase.PAIRED)
    }

    @Test
    fun `a follow-up superseded by a newer attempt before it runs does nothing`() {
        val deferred = mutableListOf<Runnable>()
        scheduler.execute = { deferred += it }
        start()
        port.report(mac, YpsoBondState.BONDING, YpsoBondState.BONDED)
        assertThat(deferred).hasSize(1)

        port.state = YpsoBondState.NOT_BONDED
        start(otherMac)
        deferred.forEach(Runnable::run)

        assertThat(followUps).isEqualTo(0)
        assertThat(pairing.attempt.value!!.mac).isEqualTo(otherMac)
    }
}
