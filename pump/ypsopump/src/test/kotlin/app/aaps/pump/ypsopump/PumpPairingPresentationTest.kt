package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.ypsopump.ble.YpsoBondState
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing.Phase
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class PumpPairingPresentationTest {

    private val serial = "10000001"
    private val mac = "EC:2A:F0:00:00:01"
    private val otherMac = "EC:2A:F0:00:00:02"
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun attempt(phase: Phase, target: String = mac) = YpsoPumpPairing.Attempt(1, target, phase)

    @Test
    fun `only a supported pump address matching its serial can be paired`() {
        assertThat(pairingTarget(serial, "ec:2a:f0:00:00:01 ")).isEqualTo(mac)
        assertThat(pairingTarget(serial, otherMac)).isNull()
        assertThat(pairingTarget("", mac)).isNull()
        assertThat(pairingTarget(serial, "EC:2A:F0:00:00")).isNull()
        assertThat(pairingPresentation(null, YpsoBondState.NOT_BONDED, null)).isEqualTo(PairingPresentation.DETAILS_REQUIRED)
        assertThat(PairingPresentation.DETAILS_REQUIRED.canPair).isFalse()
    }

    @Test
    fun `a waiting request is not presented as paired`() {
        val view = pairingPresentation(mac, YpsoBondState.NOT_BONDED, attempt(Phase.WAITING))
        assertThat(view).isEqualTo(PairingPresentation.WAITING)
        assertThat(view.canPair).isFalse()
        assertThat(pairingPresentation(mac, YpsoBondState.BONDING, null)).isEqualTo(PairingPresentation.WAITING)
    }

    @Test
    fun `only the phone's current bond is shown as paired`() {
        assertThat(pairingPresentation(mac, YpsoBondState.BONDED, null)).isEqualTo(PairingPresentation.PAIRED)
        assertThat(pairingPresentation(mac, YpsoBondState.BONDED, attempt(Phase.PAIRED))).isEqualTo(PairingPresentation.PAIRED)
        // A bond that completed after the attempt gave up is still a bond.
        assertThat(pairingPresentation(mac, YpsoBondState.BONDED, attempt(Phase.TIMED_OUT))).isEqualTo(PairingPresentation.PAIRED)
        // A bond removed since pairing is not presented as paired.
        assertThat(pairingPresentation(mac, YpsoBondState.NOT_BONDED, attempt(Phase.PAIRED))).isEqualTo(PairingPresentation.NOT_PAIRED)
    }

    @Test
    fun `an attempt that ended is shown and retryable while Android is still bonding`() {
        val view = pairingPresentation(mac, YpsoBondState.BONDING, attempt(Phase.TIMED_OUT))
        assertThat(view).isEqualTo(PairingPresentation.TIMED_OUT)
        assertThat(view.canPair).isTrue()
        assertThat(pairingPresentation(mac, YpsoBondState.BONDING, attempt(Phase.NOT_COMPLETED))).isEqualTo(PairingPresentation.NOT_COMPLETED)
    }

    @Test
    fun `each failed outcome has its own retryable message`() {
        val views = listOf(Phase.NOT_COMPLETED, Phase.TIMED_OUT, Phase.NOT_STARTED)
            .map { pairingPresentation(mac, YpsoBondState.NOT_BONDED, attempt(it)) }

        assertThat(views).containsExactly(PairingPresentation.NOT_COMPLETED, PairingPresentation.TIMED_OUT, PairingPresentation.NOT_STARTED).inOrder()
        assertThat(views.map { it.message }.toSet()).hasSize(3)
        views.forEach {
            assertThat(it.tone).isEqualTo(ProvisioningFeedbackTone.ERROR)
            assertThat(it.canPair).isTrue()
        }
    }

    @Test
    fun `an outcome for another address does not describe this pump`() {
        assertThat(pairingPresentation(mac, YpsoBondState.NOT_BONDED, attempt(Phase.NOT_COMPLETED, otherMac)))
            .isEqualTo(PairingPresentation.NOT_PAIRED)
        assertThat(pairingPresentation(mac, YpsoBondState.NOT_BONDED, attempt(Phase.WAITING, otherMac)))
            .isEqualTo(PairingPresentation.NOT_PAIRED)
    }

    @Test
    fun `Bluetooth off cannot be paired, a missing permission can be requested`() {
        assertThat(pairingPresentation(mac, YpsoBondState.BLUETOOTH_OFF, null).canPair).isFalse()
        assertThat(pairingPresentation(mac, YpsoBondState.PERMISSION_REQUIRED, null).canPair).isTrue()
    }

    private class MemoryStore(var saved: PumpSession.State = PumpSession.State()) : PumpSession.Store {
        override fun load() = saved
        override fun commit(state: PumpSession.State) { saved = state }
    }

    private fun installedService(): YpsoProvisioningService = YpsoProvisioningService(PumpSession(MemoryStore()), YpsoPumpState()).also {
        it.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.joinToString("") { b -> "%02x".format(b) }), Instant.ofEpochMilli(1_000))
        it.markVerified(serial, 2_000)
    }

    @Test
    fun `a new bond checks the saved pump once, past the retry backoff`() {
        val service = installedService()
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.BOND_OR_PERMISSION), operation = "connect", now = 3_000)
        assertThat(service.retryAllowed(3_000)).isFalse()
        val queue = mock<CommandQueue>()

        assertThat(requestCheckAfterPairing(service, queue, mac.lowercase(), "paired")).isTrue()

        verify(queue).ensureStatusReadQueued("paired")
        assertThat(service.retryAllowed(3_000)).isTrue()
        assertThat(service.retryAllowed(3_000)).isFalse()
        // Pairing does not by itself change what the session reports.
        assertThat(service.availability().causes).contains(PumpSession.AvailabilityCause.BOND_OR_PERMISSION)
    }

    @Test
    fun `a new bond checks nothing without a saved session for that address`() {
        val queue = mock<CommandQueue>()
        val unconfigured = YpsoProvisioningService(PumpSession(MemoryStore()), YpsoPumpState())
        assertThat(requestCheckAfterPairing(unconfigured, queue, mac, "paired")).isFalse()

        val service = installedService()
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "connect", now = 3_000)
        assertThat(requestCheckAfterPairing(service, queue, otherMac, "paired")).isFalse()

        verify(queue, never()).ensureStatusReadQueued(any())
        assertThat(service.retryAllowed(3_000)).isFalse()
    }

    @Test
    fun `a new bond never grants an attempt after a suspected re-key`() {
        val service = installedService()
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED), code = 140, operation = "auth", now = 3_000)
        val queue = mock<CommandQueue>()

        assertThat(requestCheckAfterPairing(service, queue, mac, "paired")).isFalse()

        verify(queue, never()).ensureStatusReadQueued(any())
        assertThat(service.retryAllowed(Long.MAX_VALUE)).isFalse()
    }
}
