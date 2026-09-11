package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession

import app.aaps.pump.ypsopump.crypto.PumpSession.AvailabilityCause
import app.aaps.pump.ypsopump.crypto.PumpSession.AttemptStatus
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.pump.ypsopump.compose.PumpStatusState
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AvailabilityTextTest {

    private fun resources(vararg text: Pair<Int, String>): ResourceHelper {
        val resources = mock<ResourceHelper> {
            on { gs(org.mockito.kotlin.any<Int>()) } doReturn "Fallback"
            on { gs(org.mockito.kotlin.any<Int>(), org.mockito.kotlin.anyVararg()) } doReturn "Fallback"
        }
        text.forEach { (id, value) -> whenever(resources.gs(id)).thenReturn(value) }
        return resources
    }

    @Test
    fun `one operator state is derived from multiple diagnostic facts`() {
        val presentation = pumpSetupPresentation(
            causes = setOf(
                AvailabilityCause.TRANSPORT,
                AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE,
                AvailabilityCause.SUSPECTED_REKEY_REQUIRED,
            ),
            hasSavedDetails = true,
            verified = false,
        )

        assertThat(presentation).isEqualTo(PumpSetupPresentation.KEY_MAY_NEED_UPDATING)
    }

    @Test
    fun `identity mismatch takes priority over a possible rekey`() {
        val presentation = pumpSetupPresentation(
            causes = setOf(AvailabilityCause.IDENTITY_MISMATCH, AvailabilityCause.SUSPECTED_REKEY_REQUIRED),
            hasSavedDetails = true,
            verified = false,
        )

        assertThat(presentation).isEqualTo(PumpSetupPresentation.DETAILS_NEED_CHECKING)
    }

    @Test
    fun `a key that cannot decrypt the pump is presented as wrong or expired`() {
        val presentation = pumpSetupPresentation(
            causes = setOf(AvailabilityCause.KEY_REJECTED),
            hasSavedDetails = true,
            verified = false,
        )

        assertThat(presentation).isEqualTo(PumpSetupPresentation.KEY_MAY_NEED_UPDATING)
        assertThat(presentation.message).isEqualTo(R.string.ypsopump_cause_rekey)
    }

    @Test
    fun `internal counter state has a deliberate operator presentation`() {
        val presentation = pumpSetupPresentation(
            causes = setOf(AvailabilityCause.COUNTER_UNCERTAIN),
            hasSavedDetails = true,
            verified = false,
        )

        assertThat(presentation).isEqualTo(PumpSetupPresentation.DETAILS_NEED_VERIFICATION)
    }

    @Test
    fun `pump tab uses the same single prioritized action`() {
        val state = YpsoPumpState().apply {
            availability = app.aaps.pump.ypsopump.crypto.PumpSession.Availability(
                setOf(AvailabilityCause.TRANSPORT, AvailabilityCause.IDENTITY_MISMATCH)
            )
            claimedSerialNumber = "configured"
            serialNumber = "verified"
            connectionState = ConnectionState.DISCONNECTED
        }
        val resources = resources(
            R.string.ypsopump_serial to "Serial",
            R.string.ypsopump_value_unavailable to "Unavailable",
            R.string.ypsopump_cause_identity_mismatch to "check pump identity",
            R.string.ypsopump_disconnected to "Pump not connected",
        )

        val status = buildPumpStatusState(
            state,
            org.mockito.kotlin.mock<CommandQueue>(),
            org.mockito.kotlin.mock<DateUtil>(),
            resources
        )

        assertThat(status.connectionSummary).isEqualTo("Pump not connected")
        assertThat(status.connectionAction).isEqualTo("check pump identity")
        assertThat(status.rows.map { it.label }).containsExactly("Serial")
    }

    @Test
    fun `an unconfirmed serial is a setup state rather than a second half-true reading`() {
        val state = YpsoPumpState().apply {
            claimedSerialNumber = "10000001"
            serialNumber = ""
            availability = app.aaps.pump.ypsopump.crypto.PumpSession.Availability(emptySet())
            connectionState = ConnectionState.DISCONNECTED
        }
        val resources = resources(
            R.string.ypsopump_serial to "Serial",
            R.string.ypsopump_configured_unverified to "Not checked yet.",
            R.string.ypsopump_disconnected to "Not connected",
        )

        val status = buildPumpStatusState(state, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), resources)

        assertThat(status.rows).isEmpty()
        assertThat(status.connectionAction).isEqualTo("Not checked yet.")
    }

    @Test
    fun `an idle command queue is not rendered as pump status`() {
        val state = YpsoPumpState().apply {
            claimedSerialNumber = "10000001"
            serialNumber = "10000001"
            availability = app.aaps.pump.ypsopump.crypto.PumpSession.Availability(emptySet())
            connectionState = ConnectionState.DISCONNECTED
        }
        val queue = org.mockito.kotlin.mock<CommandQueue> {
            on { performing() } doReturn null
            on { size() } doReturn 0
        }
        val resources = resources()

        val status = buildPumpStatusState(state, queue, org.mockito.kotlin.mock(), resources)

        assertThat(status.queue).isEmpty()
    }

    @Test
    fun `pump tab does not expose driver error details`() {
        val state = YpsoPumpState().apply {
            lastErrorCode = 140
            lastErrorMessage = "driver authentication result"
            claimedSerialNumber = "configured"
            serialNumber = "verified"
            availability = app.aaps.pump.ypsopump.crypto.PumpSession.Availability(emptySet())
            connectionState = ConnectionState.DISCONNECTED
        }
        val resources = resources(
            R.string.ypsopump_serial to "Serial",
            R.string.ypsopump_value_unavailable to "Unavailable",
            R.string.ypsopump_disconnected to "Pump not connected",
        )

        val status = buildPumpStatusState(
            state,
            org.mockito.kotlin.mock<CommandQueue>(),
            org.mockito.kotlin.mock<DateUtil>(),
            resources
        )

        assertThat(status.connectionSummary).isEqualTo("Pump not connected")
        assertThat(status.rows.map { it.value }).doesNotContain("140 — driver authentication result")
    }

    @Test
    fun `a saved but unverified configuration is not presented as ready`() {
        val presentation = pumpSetupPresentation(
            causes = emptySet(),
            hasSavedDetails = true,
            verified = false,
        )

        assertThat(presentation).isEqualTo(PumpSetupPresentation.DETAILS_NEED_VERIFICATION)
    }

    @Test
    fun `verified counter state is presented as ready rather than an internal diagnostic`() {
        val presentation = pumpSetupPresentation(
            causes = setOf(AvailabilityCause.COUNTER_UNCERTAIN),
            hasSavedDetails = true,
            verified = true,
        )

        assertThat(presentation).isEqualTo(PumpSetupPresentation.READY)
    }

    @Test
    fun `verification UI renders only the authoritative attempt status`() {
        assertThat(verificationPresentation(null)).isEqualTo(VerificationPresentation.IDLE)
        assertThat(
            verificationPresentation(YpsoProvisioningService.VerificationState("attempt", AttemptStatus.PENDING))
        ).isEqualTo(VerificationPresentation.CHECKING)
        assertThat(
            verificationPresentation(YpsoProvisioningService.VerificationState("attempt", AttemptStatus.SUCCEEDED))
        ).isEqualTo(VerificationPresentation.SUCCEEDED)
        assertThat(
            verificationPresentation(YpsoProvisioningService.VerificationState("attempt", AttemptStatus.FAILED))
        ).isEqualTo(VerificationPresentation.FAILED)
        assertThat(
            verificationPresentation(YpsoProvisioningService.VerificationState("attempt", AttemptStatus.CANCELLED))
        ).isEqualTo(VerificationPresentation.CANCELLED)
    }

    @Test
    fun `first setup has neutral guidance rather than a failed verification`() {
        val feedback = provisioningFeedback(
            verification = VerificationPresentation.IDLE,
            presentation = PumpSetupPresentation.SETUP_REQUIRED,
        )

        assertThat(feedback.message).isEqualTo(R.string.ypsopump_cause_unconfigured)
        assertThat(feedback.tone).isEqualTo(ProvisioningFeedbackTone.NEUTRAL)
    }

    @Test
    fun `failed key check reports the key problem rather than a generic message`() {
        val feedback = provisioningFeedback(
            verification = VerificationPresentation.FAILED,
            presentation = PumpSetupPresentation.KEY_MAY_NEED_UPDATING,
        )

        assertThat(feedback.message).isEqualTo(R.string.ypsopump_cause_rekey)
        assertThat(feedback.tone).isEqualTo(ProvisioningFeedbackTone.ERROR)
    }

    @Test
    fun `failed check without a specific key cause uses the attempt result`() {
        val feedback = provisioningFeedback(
            verification = VerificationPresentation.FAILED,
            presentation = PumpSetupPresentation.CONNECTION_FAILED,
        )

        assertThat(feedback.message).isEqualTo(R.string.ypsopump_verification_failed_generic)
        assertThat(feedback.tone).isEqualTo(ProvisioningFeedbackTone.ERROR)
    }

    @Test
    fun `a verified setup reports completion without exposing an internal timestamp`() {
        val feedback = provisioningFeedback(
            verification = VerificationPresentation.SUCCEEDED,
            presentation = PumpSetupPresentation.READY,
        )

        assertThat(feedback.message).isEqualTo(R.string.ypsopump_setup_complete)
        assertThat(feedback.tone).isEqualTo(ProvisioningFeedbackTone.SUCCESS)
    }

    @Test
    fun `pump state keeps long action separate from compact connection summary`() {
        val state = YpsoPumpState().apply {
            claimedSerialNumber = "configured"
            serialNumber = "verified"
            availability = app.aaps.pump.ypsopump.crypto.PumpSession.Availability(setOf(AvailabilityCause.TRANSPORT))
            connectionState = ConnectionState.DISCONNECTED
        }
        val resources = resources(
            R.string.ypsopump_serial to "Serial",
            R.string.ypsopump_value_unavailable to "Unavailable",
            R.string.ypsopump_disconnected to "Pump not connected",
            R.string.ypsopump_cause_transport to "Keep the pump nearby and awake, then try again.",
        )

        val status = buildPumpStatusState(state, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), resources)

        assertThat(status.connectionSummary).isEqualTo("Pump not connected")
        assertThat(status.connectionAction).isEqualTo("Keep the pump nearby and awake, then try again.")
    }

    @Test
    fun `long action is modelled as a dedicated wrapping status field not compact metadata`() {
        val state = PumpStatusState(
            connectionSummary = "Pump not connected",
            connectionAction = "Move closer to the pump, wake it, check Bluetooth, then try again.",
            rows = listOf(app.aaps.pump.ypsopump.compose.PumpStatusRow("Firmware", "5.0")),
        )

        assertThat(state.connectionAction).isNotNull()
        assertThat(state.connectionAction).contains("Bluetooth")
        assertThat(state.rows.map { it.value }).doesNotContain(state.connectionAction)
    }

    @Test
    fun `detaching reviewed document gives apply exclusive key ownership`() {
        val reviewed = YpsoSessionDocument("serial", "AA:BB:CC:DD:EE:FF", ByteArray(32) { 7 }, Instant.EPOCH, Instant.EPOCH, null, emptyMap())

        val operation = detachDocumentForInstallation(reviewed)
        reviewed.sharedKey.fill(0) // equivalent to activity destruction after detachment

        assertThat(operation.sharedKey).isEqualTo(ByteArray(32) { 7 })
        assertThat(reviewed.sharedKey).isEqualTo(ByteArray(32))
    }

    @Test
    fun `verification start completes after activity coroutine cancellation`() {
        runBlocking {
            val service = mock<YpsoProvisioningService>()
            val queue = mock<CommandQueue>()
            val enteredInstall = CountDownLatch(1)
            val releaseInstall = CountDownLatch(1)
            whenever(service.installManualAndStartVerification(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenAnswer {
                enteredInstall.countDown()
                check(releaseInstall.await(2, TimeUnit.SECONDS))
                (it.arguments[2] as () -> Boolean).invoke()
                PumpSession.Installation.FIRST_PUMP
            }
            whenever(queue.readStatus("verification", null)).thenReturn(true)
            val starter = ProvisioningVerificationStarter(service, queue, "verification")

            val job = launch(kotlinx.coroutines.Dispatchers.IO) { starter.installManual(YpsoProvisioningService.ManualDraft("serial", "AA:BB:CC:DD:EE:FF", "key")) }
            assertThat(enteredInstall.await(2, TimeUnit.SECONDS)).isTrue()
            job.cancel()
            releaseInstall.countDown()
            job.join()

            verify(service).installManualAndStartVerification(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
            verify(queue).readStatus("verification", null)
        }
    }

    @Test
    fun `rejected verification enqueue cancels the just staged candidate`() {
        runBlocking {
            val service = mock<YpsoProvisioningService>()
            val queue = mock<CommandQueue>()
            whenever(service.installManualAndStartVerification(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenThrow(VerificationStartException())
            whenever(queue.readStatus("verification", null)).thenReturn(false)
            val starter = ProvisioningVerificationStarter(service, queue, "verification")

            val error = try {
                starter.installManual(YpsoProvisioningService.ManualDraft("serial", "AA:BB:CC:DD:EE:FF", "key"))
                null
            } catch (error: VerificationStartException) {
                error
            }

            assertThat(error).isInstanceOf(VerificationStartException::class.java)
            verify(service).installManualAndStartVerification(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        }
    }
}
