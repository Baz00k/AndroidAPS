package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.SessionDocumentException
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import org.junit.jupiter.api.Test

/** Setup failures reach the screen as one actionable message, classified from the real service paths. */
class ProvisioningFailureMessageTest {

    private val serial = "10000001"
    private val mac = "EC:2A:F0:00:00:01"
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val rotatedKey = ByteArray(32) { (it + 33).toByte() }
    private val now = Instant.parse("2026-10-09T00:00:00Z")

    private class MemoryStore(var saved: PumpSession.State = PumpSession.State()) : PumpSession.Store {
        override fun load() = saved
        override fun commit(state: PumpSession.State) { saved = state }
    }

    private fun service(store: PumpSession.Store = MemoryStore(), state: YpsoPumpState = YpsoPumpState()) =
        YpsoProvisioningService(PumpSession(store), state)

    private fun review(json: String) = runCatching { service().reviewDocument(ByteArrayInputStream(json.toByteArray()), now) }
        .exceptionOrNull()!!

    private fun saveFailure(service: YpsoProvisioningService, replacement: ByteArray = rotatedKey, enqueue: () -> Unit = {}) = runCatching {
        service.installManualAndStartVerification(YpsoProvisioningService.ManualDraft(serial, mac, replacement.hex()), now, enqueue)
    }.exceptionOrNull()!!

    @Test
    fun `a file that is not a session document is named as such`() {
        assertThat(importFailureMessage(review("{\"schema_version\": 2}"))).isEqualTo(R.string.ypsopump_import_invalid)
        assertThat(importFailureMessage(review("not json"))).isEqualTo(R.string.ypsopump_import_invalid)
        val oversized = runCatching {
            service().reviewDocument(ByteArrayInputStream(ByteArray(70 * 1024) { ' '.code.toByte() }), now)
        }.exceptionOrNull()!!
        assertThat(importFailureMessage(oversized)).isEqualTo(R.string.ypsopump_import_invalid)
    }

    @Test
    fun `a file for a different or unsupported pump is named as such`() {
        val mismatched = document().replace(mac, "EC:2A:F0:00:00:02")
        val unsupported = document().replace("\"$serial\"", "\"20000001\"")

        assertThat(importFailureMessage(review(mismatched))).isEqualTo(R.string.ypsopump_import_unsupported_pump)
        assertThat(importFailureMessage(review(unsupported))).isEqualTo(R.string.ypsopump_import_unsupported_pump)
    }

    @Test
    fun `a file dated after the phone clock points at the clock`() {
        val future = document(created = "2026-10-10T00:00:00Z", captured = "2026-10-10T00:01:00Z")

        assertThat(importFailureMessage(review(future))).isEqualTo(R.string.ypsopump_import_dated_after_clock)
    }

    @Test
    fun `a file that cannot be read is not blamed on its content`() {
        val failing = object : InputStream() { override fun read(): Int = throw IOException("provider gone") }
        val error = runCatching { service().reviewDocument(failing, now) }.exceptionOrNull()!!

        assertThat(importFailureMessage(error)).isEqualTo(R.string.ypsopump_import_unreadable)
    }

    @Test
    fun `a valid file still reviews`() {
        assertThat(service().reviewDocument(ByteArrayInputStream(document().toByteArray()), now).serial).isEqualTo(serial)
    }

    @Test
    fun `unresolved pump accounting blocks a key change with its own message`() {
        val store = MemoryStore()
        val first = service(store)
        first.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(1_000))
        val accepted = first.owner.open(mac, key)
        val transaction = first.owner.begin(accepted)
        try {
            first.owner.accept(accepted, transaction, SessionCrypto.Message(byteArrayOf(1), 8, 100))
        } finally {
            first.owner.finish(accepted, transaction)
        }
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(write = 41, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
        })
        val service = service(store)
        val token = service.owner.open(mac, key)
        service.owner.reserve(token, service.owner.begin(token))

        val error = saveFailure(service)

        assertThat(error).isInstanceOf(PumpSession.UnresolvedAccountingException::class.java)
        assertThat(saveFailureMessage(error)).isEqualTo(R.string.ypsopump_save_failed_unresolved)
    }

    @Test
    fun `a key known for another pump has its own message`() {
        val service = service()
        service.installManual(YpsoProvisioningService.ManualDraft("10000002", "EC:2A:F0:00:00:02", key.hex()), Instant.ofEpochMilli(1_000))
        service.markVerified("10000002", 1_500)

        val error = saveFailure(service, replacement = key)

        assertThat(saveFailureMessage(error)).isEqualTo(R.string.ypsopump_save_failed_other_pump)
    }

    @Test
    fun `unreadable session storage has its own message`() {
        val service = service(object : PumpSession.Store {
            override fun load(): PumpSession.State = error("journal unavailable")
            override fun commit(state: PumpSession.State) = error("journal unavailable")
        })

        assertThat(saveFailureMessage(saveFailure(service))).isEqualTo(R.string.ypsopump_save_failed_storage)
    }

    @Test
    fun `a check that cannot be requested asks to try again`() {
        val error = saveFailure(service()) { error("queue unavailable") }

        assertThat(saveFailureMessage(error)).isEqualTo(R.string.ypsopump_verification_start_failed)
    }

    @Test
    fun `a replacement check that cannot reach the pump says AAPS keeps trying only when it will`() {
        val service = verifiedService()
        saveReplacement(service)
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying)

        spendExplicitAttemptOnTransportFailure(service)

        assertThat(service.retryAllowed(System.currentTimeMillis() + 10 * 60_000)).isTrue()
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying_retry)
    }

    @Test
    fun `after a rejected key a failed replacement attempt asks for the new key again`() {
        val service = verifiedService()
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED), code = 140, operation = "status", now = 2_000)
        saveReplacement(service)
        // Before its one attempt, a fresh replacement is still only being checked.
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying)

        spendExplicitAttemptOnTransportFailure(service)

        assertThat(service.retryAllowed(System.currentTimeMillis() + 10 * 60_000)).isFalse()
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying_stalled)

        // The instructed retry: Cancel, then enter the new key again. Saving without it is refused.
        service.cancelCandidate()
        assertThat(runCatching {
            service.installManualAndStartVerification(YpsoProvisioningService.ManualDraft(serial, mac, null), now) {}
        }.exceptionOrNull()).isInstanceOf(YpsoProvisioningService.ReplacementKeyRequiredException::class.java)
        saveReplacement(service)
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying)
        assertThat(service.retryAllowed()).isTrue()
    }

    @Test
    fun `after a rejected key an attempt torn down without a recorded failure asks for the new key again`() {
        val state = YpsoPumpState()
        val service = verifiedService(state = state)
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED), code = 140, operation = "status", now = 2_000)
        saveReplacement(service)
        // The plugin counts a connection as starting before it takes the grant, until BLE leaves DISCONNECTED.
        state.connectionsStarting.incrementAndGet()
        assertThat(service.retryAllowed()).isTrue()
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying)
        state.connectionState = YpsoBleManager.ConnectionState.CONNECTING
        state.connectionsStarting.decrementAndGet()
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying)

        // The queue deadline closes the link; local teardown records no failure on the candidate.
        state.connectionState = YpsoBleManager.ConnectionState.DISCONNECTED

        assertThat(service.pending()!!.availability.failures).isEqualTo(0)
        assertThat(rendered(service).message).isEqualTo(R.string.ypsopump_verifying_stalled)
    }

    @Test
    fun `after a rejected key a replacement pending across a restart asks for the new key again`() {
        val store = MemoryStore()
        val service = verifiedService(store)
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED), code = 140, operation = "status", now = 2_000)
        saveReplacement(service)

        val restarted = service(store)

        assertThat(restarted.retryAllowed()).isFalse()
        assertThat(rendered(restarted).message).isEqualTo(R.string.ypsopump_verifying_stalled)
    }

    private fun verifiedService(store: PumpSession.Store = MemoryStore(), state: YpsoPumpState = YpsoPumpState()) = service(store, state).apply {
        installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(1_000))
        markVerified(serial, 1_500)
    }

    private fun saveReplacement(service: YpsoProvisioningService) {
        service.installManualAndStartVerification(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), now) {}
    }

    private fun spendExplicitAttemptOnTransportFailure(service: YpsoProvisioningService) {
        assertThat(service.retryAllowed()).isTrue()
        val candidate = service.connectionSession()!!
        service.recordCandidateOrUnavailable(candidate.generation, candidate.attemptId, setOf(PumpSession.AvailabilityCause.TRANSPORT), "connect-gatt")
    }

    /** The setup screen's status line for the service's real pending state. */
    private fun rendered(service: YpsoProvisioningService): ProvisioningFeedback {
        val verification = verificationPresentation(service.verificationState())
        assertThat(verification).isEqualTo(VerificationPresentation.CHECKING)
        val availability = service.pending()!!.availability
        return provisioningFeedback(
            verification,
            pumpSetupPresentation(displayedCauses(verification, availability.causes), hasSavedDetails = true, verified = true),
            pendingCheck(availability, service.verificationCanProceed()),
        )
    }

    @Test
    fun `importing a key the pump already rejected asks for a new extraction`() {
        assertThat(saveFailureMessage(YpsoProvisioningService.ReplacementKeyRequiredException()))
            .isEqualTo(R.string.ypsopump_import_rejected_key)
        assertThat(saveFailureMessage(IllegalStateException())).isEqualTo(R.string.ypsopump_save_failed)
    }

    @Test
    fun `failure logs carry exception types but never messages`() {
        val secret = key.hex()
        val log = setupFailureLog(
            "import review",
            SessionDocumentException(SessionDocumentException.Problem.NOT_A_SESSION_FILE, "Expected key $secret", IllegalArgumentException(secret))
        )

        assertThat(log).isEqualTo("YpsoPump setup import review failed: SessionDocumentException(NOT_A_SESSION_FILE) caused by IllegalArgumentException")
        assertThat(log).doesNotContain(secret)
    }

    private fun document(created: String = "2026-09-08T00:00:00Z", captured: String = "2026-09-08T00:01:00Z") = """
        {
          "schema_version": 1,
          "pump": {"mac": "$mac", "serial": "$serial"},
          "shared_key": "${key.hex()}",
          "created_at": "$created",
          "captured_at": "$captured",
          "reboot_counter": null,
          "source": {"profile": "mylife-v1", "package": "com.example", "app_version": "1.0", "identity": "mylife-db-v1"}
        }
    """.trimIndent()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
