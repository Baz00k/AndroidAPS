package app.aaps.pump.ypsopump

import android.content.Context
import androidx.work.WorkManager
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.implementation.queue.CommandQueueImplementation
import app.aaps.implementation.queue.CommandQueueName
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

/**
 * Saving a replacement key while AAPS already waits on a status read for the old session. Uses the
 * real starter, provisioning service, session owner and command queue; no worker or BLE runs.
 */
class YpsoProvisioningQueueTest : TestBase() {

    private val serial = "10000001"
    private val mac = "EC:2A:F0:00:00:01"
    private val oldKey = ByteArray(32) { (it + 1).toByte() }
    private val newKey = ByteArray(32) { (it + 33).toByte() }

    private class MemoryStore : PumpSession.Store {
        var saved = PumpSession.State()
        override fun load() = saved
        override fun commit(state: PumpSession.State) { saved = state }
    }

    private lateinit var service: YpsoProvisioningService
    private lateinit var queue: CommandQueueImplementation
    private lateinit var starter: ProvisioningVerificationStarter

    @BeforeEach
    fun setUp() {
        service = YpsoProvisioningService(PumpSession(MemoryStore()), YpsoPumpState())
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, oldKey.hex()), Instant.ofEpochMilli(1_000))
        service.markVerified(serial, 1_500)
        val injector = HasAndroidInjector { AndroidInjector { } }
        val pumpEnactResult = mock<PumpEnactResult>(defaultAnswer = { it.mock })
        queue = CommandQueueImplementation(
            injector, aapsLogger, rxBus, aapsSchedulers, mock(), mock(), mock(), mock(), mock<Context>(), mock(),
            mock(), mock(), mock(), mock(), mock(), { pumpEnactResult }, CommandQueueName("YpsoProvisioningQueueTest"), mock<WorkManager>()
        )
        starter = ProvisioningVerificationStarter(service, queue, "verification")
    }

    @Test
    fun `manual replacement key is staged for the status read already queued`() {
        assertThat(queue.readStatus("foreground", null)).isTrue()
        val committed = service.owner.committedRecord()

        val installation = runBlocking {
            starter.installManual(YpsoProvisioningService.ManualDraft(serial, mac, newKey.hex()))
        }

        assertReplacementAwaitsQueuedRead(installation, committed)
    }

    @Test
    fun `imported replacement key is staged for the status read already queued`() {
        assertThat(queue.readStatus("foreground", null)).isTrue()
        val committed = service.owner.committedRecord()
        val document = YpsoSessionDocument(
            serial, mac, newKey.copyOf(), Instant.parse("2026-10-08T22:21:22Z"), Instant.parse("2026-10-08T22:24:41Z"), null,
            mapOf("profile" to "test")
        )

        val installation = runBlocking { starter.installDocument(document) }

        assertReplacementAwaitsQueuedRead(installation, committed)
    }

    @Test
    fun `replacement key bypasses the old session's backoff once`() {
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.TRANSPORT), now = System.currentTimeMillis())
        assertThat(service.retryAllowed()).isFalse()
        queue.readStatus("foreground", null)

        runBlocking { starter.installManual(YpsoProvisioningService.ManualDraft(serial, mac, newKey.hex())) }

        assertThat(service.connectionSession()!!.candidate).isTrue()
        assertThat(service.retryAllowed()).isTrue()
    }

    @Test
    fun `failure to request the verification read keeps the old key and reports a typed error`() {
        val committed = service.owner.committedRecord()
        val failingQueue = mock<CommandQueue> { on { ensureStatusReadQueued("verification") } doThrow IllegalStateException("queue unavailable") }
        val failingStarter = ProvisioningVerificationStarter(service, failingQueue, "verification")

        val error = runCatching {
            runBlocking { failingStarter.installManual(YpsoProvisioningService.ManualDraft(serial, mac, newKey.hex())) }
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(YpsoProvisioningService.VerificationStartException::class.java)
        assertThat(service.owner.committedRecord()).isEqualTo(committed)
        assertThat(service.pending()).isNull()
        assertThat(service.verificationState()!!.status).isEqualTo(PumpSession.AttemptStatus.CANCELLED)
        val restored = service.connectionSession()!!
        assertThat(restored.candidate).isFalse()
        assertThat(service.isCurrentConnection(restored)).isTrue()
    }

    private fun assertReplacementAwaitsQueuedRead(installation: PumpSession.Installation, committed: PumpSession.Record?) {
        assertThat(installation).isEqualTo(PumpSession.Installation.ROTATED_KEY)
        assertThat(service.owner.committedRecord()).isEqualTo(committed)
        assertThat(service.owner.committedRecord()!!.keyId).isEqualTo(PumpSession.fingerprint(oldKey))
        assertThat(service.owner.candidateRecord()!!.keyId).isEqualTo(PumpSession.fingerprint(newKey))
        assertThat(service.verificationState()!!.status).isEqualTo(PumpSession.AttemptStatus.PENDING)
        // Exactly one read waits: the queued one now connects with the candidate; none is duplicated.
        assertThat(queue.size()).isEqualTo(1)
        assertThat(queue.isReadStatusScheduled()).isTrue()
        assertThat(queue.performing()).isNull()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
