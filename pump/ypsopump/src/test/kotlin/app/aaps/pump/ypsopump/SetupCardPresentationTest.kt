package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.ypsopump.ble.YpsoBondState
import app.aaps.pump.ypsopump.ble.YpsoFoundPump
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing
import app.aaps.pump.ypsopump.ble.YpsoPumpScanner
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

class SetupCardPresentationTest {

    private val serial = "10000001"
    private val mac = "EC:2A:F0:00:00:01"
    private val otherMac = "EC:2A:F0:00:00:02"
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun attempt(phase: Phase, target: String = mac) = YpsoPumpPairing.Attempt(1, target, phase)

    @Test
    fun `the address to pair follows from a supported serial`() {
        assertThat(pairingTarget(serial)).isEqualTo(mac)
        // The last six digits are the address's last three bytes: 12345 is 0x003039, 999999 is 0x0F423F.
        assertThat(pairingTarget(" 10012345 ")).isEqualTo("EC:2A:F0:00:30:39")
        assertThat(pairingTarget("10999999")).isEqualTo("EC:2A:F0:0F:42:3F")
        assertThat(pairingTarget("")).isNull()
        assertThat(pairingTarget("20000001")).isNull()
        assertThat(pairingTarget("1000001")).isNull()
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
    private val ready = ProvisioningFeedback(R.string.ypsopump_setup_complete, ProvisioningFeedbackTone.SUCCESS)
    private val checkingFeedback = ProvisioningFeedback(R.string.ypsopump_cause_bond_missing, ProvisioningFeedbackTone.NEUTRAL)
    private val unconfigured = ProvisioningFeedback(R.string.ypsopump_cause_unconfigured, ProvisioningFeedbackTone.NEUTRAL)
    private val found = YpsoFoundPump(serial, mac)

    private fun card(
        pairing: PairingPresentation,
        configured: Boolean = false,
        feedback: ProvisioningFeedback = if (configured) ready else unconfigured,
        checking: Boolean = false,
        scan: YpsoPumpScanner.Scan? = null,
        busy: Int? = null,
    ) = setupCard(busy, feedback, checking, configured, pairing, scan)

    @Test
    fun `a new setup gives one instruction, to find the pump`() {
        val view = card(PairingPresentation.DETAILS_REQUIRED)
        assertThat(view).isEqualTo(SetupCard(R.string.ypsopump_setup_start, ProvisioningFeedbackTone.NEUTRAL, actions = listOf(SetupAction.FIND_PUMP)))
    }

    @Test
    fun `a search lists the pumps it finds and then asks for a choice`() {
        val running = card(PairingPresentation.DETAILS_REQUIRED, scan = YpsoPumpScanner.Scan(1, YpsoPumpScanner.Phase.SCANNING, listOf(found)))
        assertThat(running).isEqualTo(SetupCard(R.string.ypsopump_scan_running, ProvisioningFeedbackTone.NEUTRAL, progress = true, showFoundPumps = true))

        val finished = card(PairingPresentation.DETAILS_REQUIRED, scan = YpsoPumpScanner.Scan(1, YpsoPumpScanner.Phase.FINISHED, listOf(found)))
        assertThat(finished.message).isEqualTo(R.string.ypsopump_scan_choose)
        assertThat(finished.showFoundPumps).isTrue()
        assertThat(finished.actions).containsExactly(SetupAction.FIND_PUMP)
    }

    @Test
    fun `a search that found nothing or could not run says why and can be repeated`() {
        val expected = mapOf(
            YpsoPumpScanner.Phase.FINISHED to R.string.ypsopump_scan_none,
            YpsoPumpScanner.Phase.FAILED to R.string.ypsopump_scan_failed,
            YpsoPumpScanner.Phase.BLUETOOTH_OFF to R.string.ypsopump_scan_bluetooth_off,
            YpsoPumpScanner.Phase.PERMISSION_REQUIRED to R.string.ypsopump_scan_permission,
            YpsoPumpScanner.Phase.LOCATION_OFF to R.string.ypsopump_scan_location_off,
        )
        expected.forEach { (phase, message) ->
            val view = card(PairingPresentation.DETAILS_REQUIRED, scan = YpsoPumpScanner.Scan(1, phase))
            assertThat(view).isEqualTo(SetupCard(message, ProvisioningFeedbackTone.ERROR, actions = listOf(SetupAction.FIND_PUMP)))
        }
    }

    @Test
    fun `pairing comes before the setup status and is the only instruction`() {
        assertThat(card(PairingPresentation.NOT_PAIRED)).isEqualTo(
            SetupCard(R.string.ypsopump_pairing_not_paired, ProvisioningFeedbackTone.NEUTRAL, actions = listOf(SetupAction.PAIR_PUMP))
        )
        assertThat(card(PairingPresentation.TIMED_OUT, configured = true)).isEqualTo(
            SetupCard(R.string.ypsopump_pairing_timed_out, ProvisioningFeedbackTone.ERROR, actions = listOf(SetupAction.PAIR_PUMP))
        )
        assertThat(card(PairingPresentation.WAITING)).isEqualTo(SetupCard(R.string.ypsopump_pairing_waiting, ProvisioningFeedbackTone.NEUTRAL, progress = true))
    }

    @Test
    fun `once paired the card shows the setup status alone`() {
        assertThat(card(PairingPresentation.PAIRED)).isEqualTo(SetupCard(R.string.ypsopump_setup_add_key, ProvisioningFeedbackTone.NEUTRAL))
        assertThat(card(PairingPresentation.PAIRED, configured = true)).isEqualTo(SetupCard(ready.message, ready.tone))
    }

    @Test
    fun `a running check keeps cancel and still offers pairing`() {
        assertThat(card(PairingPresentation.NOT_PAIRED, configured = true, feedback = checkingFeedback, checking = true)).isEqualTo(
            SetupCard(checkingFeedback.message, checkingFeedback.tone, progress = true, actions = listOf(SetupAction.CANCEL_CHECK, SetupAction.PAIR_PUMP))
        )
        assertThat(card(PairingPresentation.WAITING, configured = true, feedback = checkingFeedback, checking = true).actions)
            .containsExactly(SetupAction.CANCEL_CHECK)
    }

    @Test
    fun `saving or importing hides every action`() {
        assertThat(card(PairingPresentation.NOT_PAIRED, busy = R.string.ypsopump_saving))
            .isEqualTo(SetupCard(R.string.ypsopump_saving, ProvisioningFeedbackTone.NEUTRAL, progress = true))
    }
}
