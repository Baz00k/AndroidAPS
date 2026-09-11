package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class YpsoProvisioningServiceTest {
    private val serial = "10000001"
    private val mac = "EC:2A:F0:00:00:01"
    private val otherSerial = "10000002"
    private val otherMac = "EC:2A:F0:00:00:02"
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val rotatedKey = ByteArray(32) { (it + 33).toByte() }

    private class MemoryStore(initial: PumpSession.State = PumpSession.State()) : PumpSession.Store {
        var saved = initial
        var unavailable = false
        var commits = 0
        override fun load() = saved
        override fun commit(state: PumpSession.State) { check(!unavailable); commits++; saved = state }
    }

    private class Legacy(
        var credentials: YpsoProvisioningService.LegacyCredentials = YpsoProvisioningService.LegacyCredentials(null, null, null)
    ) : YpsoProvisioningService.LegacyStore {
        var clears = 0
        override fun load() = credentials
        override fun clear(): Boolean { clears++; credentials = YpsoProvisioningService.LegacyCredentials(null, null, null); return true }
    }

    private fun service(
        store: MemoryStore = MemoryStore(),
        state: YpsoPumpState = YpsoPumpState(),
        legacy: Legacy = Legacy()
    ) = Triple(YpsoProvisioningService(PumpSession(store), state, legacy), store, legacy)

    private fun install(service: YpsoProvisioningService, key: ByteArray = this.key) = service.installManual(
        YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(1_000)
    )

    private fun acceptFirst(owner: PumpSession, pump: String, key: ByteArray, counter: Long = 100) {
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        try {
            owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 8, counter))
        } finally {
            owner.finish(token, transaction)
        }
    }

    private fun advanceFloor(service: YpsoProvisioningService, generation: String, key: ByteArray, reboot: Int, counter: Long) {
        val token = service.owner.openGeneration(generation, mac, key)
        val transaction = service.owner.begin(token)
        try {
            service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), reboot, counter))
        } finally {
            service.owner.finish(token, transaction)
        }
    }

    @Test
    fun `invalid or cancelled document review does not mutate the active session`() {
        val (service, store) = service()
        install(service)
        val before = store.saved
        val wrongPump = validDocument().replace(serial, otherSerial)

        assertThrows(IllegalArgumentException::class.java) {
            service.reviewDocument(ByteArrayInputStream(wrongPump.toByteArray()), Instant.parse("2026-09-09T00:00:00Z"))
        }
        assertEquals(before, store.saved)
        // A cancelled system picker calls no installation method; the reviewed draft is never active.
        assertEquals(before, store.saved)
    }

    @Test
    fun `first install is pending across restart and rejected enqueue rolls back to empty`() {
        val store = MemoryStore()
        val service = service(store).first

        install(service)
        assertNull(service.installed())
        assertEquals(PumpSession.AttemptStatus.PENDING, service.verificationState()!!.status)
        val restarted = service(store).first
        assertNull(restarted.installed())
        assertEquals(serial, restarted.pending()!!.serial)
        assertEquals(PumpSession.AttemptStatus.PENDING, restarted.verificationState()!!.status)

        val failure = assertThrows(IllegalStateException::class.java) {
            restarted.installManualAndStartVerification(
                YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(2_000)
            ) { false }
        }
        assertTrue(failure.message!!.contains("not accepted"))
        assertNull(restarted.installed())
        assertNull(restarted.pending())
        assertEquals(PumpSession.AttemptStatus.CANCELLED, restarted.verificationState()!!.status)
        assertNull(store.saved.activeGeneration)
        assertTrue(store.saved.records.isEmpty())
    }

    @Test
    fun `invalid candidate preserves active identity key and replay floor`() {
        val (service, store) = service()
        install(service)
        acceptFirst(service.owner, mac, key)
        service.markVerified(serial, 1_500)
        val activeBefore = service.owner.committedRecord()!!

        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            Instant.ofEpochMilli(2_000)
        )
        val candidate = service.connectionSession()!!
        assertTrue(candidate.candidate)
        assertEquals(activeBefore, service.owner.committedRecord())

        assertTrue(service.rejectCandidate(candidate.generation, candidate.attemptId))

        assertEquals(activeBefore, service.owner.committedRecord())
        assertEquals(activeBefore.generation, store.saved.activeGeneration)
        assertNull(store.saved.candidateGeneration)
        assertArrayEquals(key, service.connectionSession()!!.key)
        assertEquals(serial, service.installed()!!.serial)
    }

    @Test
    fun `verified candidate is promoted atomically and survives restart`() {
        val (service, store) = service()
        install(service)
        service.markVerified(serial, 1_500)
        val old = service.owner.committedRecord()!!
        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            Instant.ofEpochMilli(2_000)
        )
        val candidate = service.connectionSession()!!

        service.markVerified(candidate.generation, candidate.attemptId, serial, 3_000)

        assertNull(store.saved.candidateGeneration)
        assertTrue(service.owner.committedRecord()!!.generation != old.generation)
        assertArrayEquals(rotatedKey, service.keyBytes())
        assertNull(service.pending())
        val restarted = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), Legacy())
        assertArrayEquals(rotatedKey, restarted.keyBytes())
        assertEquals(Instant.ofEpochMilli(3_000), restarted.installed()!!.verifiedAt)
    }

    @Test
    fun `canonical document key is selected for candidate connection and promoted only after protected verification`() {
        val (service) = service()
        install(service)
        service.markVerified(serial, 1_500)
        val committed = service.keyBytes()!!
        val documentText = validDocument().replace(key.hex(), rotatedKey.hex())
        val document = service.reviewDocument(ByteArrayInputStream(documentText.toByteArray()), Instant.parse("2026-09-09T00:00:00Z"))

        service.installDocument(document, Instant.parse("2026-09-09T00:01:00Z"))
        val connection = service.connectionSession()!!

        assertTrue(connection.candidate)
        assertArrayEquals(rotatedKey, connection.key)
        assertFalse(connection.key.contentEquals(committed))
        assertArrayEquals(rotatedKey, service.connectionSession()!!.key)
        service.markVerified(connection.generation, connection.attemptId, serial, 3_000)
        assertArrayEquals(rotatedKey, service.keyBytes())
        committed.fill(0)
        connection.key.fill(0)
    }

    @Test
    fun `stale verification and rejection callbacks cannot affect newer candidate`() {
        val (service, store) = service()
        install(service)
        service.markVerified(serial, 1_500)
        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            Instant.ofEpochMilli(2_000)
        )
        val staleGeneration = service.connectionSession()!!.generation
        val newestKey = ByteArray(32) { 99 }
        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, newestKey.hex()),
            Instant.ofEpochMilli(3_000)
        )
        val newest = service.connectionSession()!!
        val before = store.saved

        assertFalse(service.rejectCandidate(staleGeneration, "stale-attempt"))
        assertThrows(IllegalStateException::class.java) { service.markVerified(staleGeneration, "stale-attempt", serial, 4_000) }

        assertEquals(before, store.saved)
        assertEquals(newest.generation, service.connectionSession()!!.generation)
        assertArrayEquals(key, service.keyBytes())
        assertEquals(newest.serial, service.pending()!!.serial)
    }

    @Test
    fun `reverting a pending replacement to the committed key stages without duplicate corruption`() {
        val (service, store) = service()
        install(service)
        service.markVerified(serial, 1_500)
        val active = service.owner.committedRecord()!!
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(3_000))

        assertEquals(active.generation, service.owner.committedRecord()!!.generation)
        assertArrayEquals(key, service.connectionSession()!!.key)
        PumpSession.validate(store.saved)
        assertEquals(2, store.saved.records.count { it.keyId == PumpSession.fingerprint(key) })
    }

    @Test
    fun `reimporting an older retained key after verified rotation requires verification`() {
        val (service, store) = service()
        install(service)
        service.markVerified(serial, 1_500)
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
        service.connectionSession()!!.also { service.markVerified(it.generation, it.attemptId, serial, 2_500) }

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(3_000))

        assertArrayEquals(rotatedKey, service.keyBytes())
        assertArrayEquals(key, service.connectionSession()!!.key)
        assertEquals(PumpSession.AttemptStatus.PENDING, service.verificationState()!!.status)
        PumpSession.validate(store.saved)
    }

    @Test
    fun `failed fresh candidate retains its authenticated replay floor for reimport`() {
        val (service) = service()
        install(service, key = rotatedKey)
        val candidate = service.connectionSession()!!

        val token = service.owner.openGeneration(candidate.generation, mac, rotatedKey)
        val transaction = service.owner.begin(token)
        try {
            service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 8, 100))
        } finally {
            service.owner.finish(token, transaction)
        }
        assertTrue(service.failCandidateOrRecord(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), "encrypted-status", now = 2_000
        ))
        assertNull(service.connectionSession())

        install(service, key = rotatedKey)

        val reimported = service.connectionSession()!!
        val record = service.owner.recordForGeneration(reimported.generation)!!
        assertEquals(8, record.reboot)
        assertEquals(100L, record.read)
    }

    @Test
    fun `same-generation restage rejects a stale attempt at the promotion boundary`() {
        val (service) = service()
        install(service)
        val first = service.connectionSession()!!

        install(service)

        val second = service.connectionSession()!!
        assertEquals(first.generation, second.generation)
        assertTrue(first.attemptId != second.attemptId)
        assertThrows(IllegalStateException::class.java) {
            service.owner.markVerified(first.generation, first.attemptId, serial, 2_000)
        }
        service.owner.markVerified(second.generation, second.attemptId, serial, 2_500)
        assertEquals(serial, service.owner.committedRecord()!!.verifiedSerial)
    }

    @Test
    fun `restaging the same pending key preserves its advanced replay floor`() {
        val (service) = service()
        install(service)
        acceptFirst(service.owner, mac, key)
        service.markVerified(serial, 1_500)
        val committed = service.owner.committedRecord()!!

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(2_000))
        val first = service.connectionSession()!!
        advanceFloor(service, first.generation, key, reboot = 8, counter = 120)

        install(service)

        val second = service.connectionSession()!!
        assertEquals(first.generation, second.generation)
        assertEquals(120L, service.owner.recordForGeneration(second.generation)!!.read)

        service.markVerified(second.generation, second.attemptId, serial, 3_000)

        val promoted = service.owner.committedRecord()!!
        assertEquals(committed.generation, promoted.generation)
        assertEquals(120L, promoted.read)
    }

    @Test
    fun `stale attempt cannot mutate a promoted same-generation session`() {
        val (service) = service()
        install(service)
        val first = service.connectionSession()!!

        install(service)

        val second = service.connectionSession()!!
        service.markVerified(second.generation, second.attemptId, serial, 2_500)

        assertThrows(IllegalStateException::class.java) {
            service.owner.markVerified(first.generation, first.attemptId, serial, 3_000)
        }
        assertFalse(
            service.failCandidateOrRecord(
                first.generation, first.attemptId,
                setOf(PumpSession.AvailabilityCause.TRANSPORT), "read", now = 3_100
            )
        )
    }

    @Test
    fun `failure after a validated reboot keeps the new epoch replay tuple`() {
        val (service) = service()
        install(service)
        acceptFirst(service.owner, mac, key)
        service.markVerified(serial, 1_500)

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(2_000))
        val candidate = service.connectionSession()!!
        val token = service.owner.openGeneration(candidate.generation, mac, key)
        val transaction = service.owner.begin(token)
        try {
            assertThrows(PumpSession.RebootAdoptedException::class.java) {
                service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 9, 1), allowObservedReboot = true)
            }
        } finally {
            service.owner.finish(token, transaction)
        }

        assertTrue(
            service.failCandidateOrRecord(
                candidate.generation, candidate.attemptId,
                setOf(PumpSession.AvailabilityCause.TRANSPORT), "read", now = 3_000
            )
        )

        val record = service.owner.committedRecord()!!
        assertEquals(9, record.reboot)
        assertEquals(1L, record.read)
        assertNull(record.write)
    }

    @Test
    fun `promotion is rejected once a session mutation has started`() {
        val (service) = service()
        install(service)
        val candidate = service.connectionSession()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.quiesceConnection = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val installer = Executors.newSingleThreadExecutor()
        try {
            installer.submit {
                runCatching {
                    service.installManual(
                        YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
                        Instant.ofEpochMilli(2_000)
                    )
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                service.markVerified(candidate.generation, candidate.attemptId, serial, 3_000)
            }
        } finally {
            release.countDown()
            installer.shutdownNow()
        }
    }

    @Test
    fun `retryable candidate failure keeps the candidate selected with backoff`() {
        val (service) = service()
        install(service)
        val candidate = service.connectionSession()!!

        assertTrue(service.recordCandidateOrUnavailable(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.TRANSPORT), "read", now = 2_000
        ))

        val selected = service.connectionSession()!!
        assertTrue(selected.candidate)
        assertEquals(candidate.generation, selected.generation)
        assertEquals(PumpSession.AttemptStatus.PENDING, service.verificationState()!!.status)
        assertEquals(1, service.availability().failures)
        assertEquals(7_000L, service.availability().retryAt)
    }

    @Test
    fun `code 140 retires the candidate and preserves the sticky condition`() {
        val (service) = service()
        install(service)
        service.markVerified(serial, 1_500)
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
        val candidate = service.connectionSession()!!

        assertTrue(service.failCandidateOrRecord(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED), "auth", now = 3_000, code = 140
        ))

        assertNull(service.pending())
        assertEquals(PumpSession.AttemptStatus.FAILED, service.verificationState()!!.status)
        assertTrue(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in service.availability().causes)
        assertFalse(service.retryAllowed(Long.MAX_VALUE))
        assertArrayEquals(key, service.keyBytes())
    }

    @Test
    fun `sticky rekey keeps code and operation when a later replacement fails`() {
        val (service) = service()
        install(service)
        acceptFirst(service.owner, mac, key)
        service.markVerified(serial, 1_500)
        val thirdKey = ByteArray(32) { (it + 97).toByte() }

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
        val first = service.connectionSession()!!
        assertTrue(
            service.failCandidateOrRecord(
                first.generation, first.attemptId, setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED),
                "auth", now = 3_000, firmware = "V05.00.52", code = 140
            )
        )
        assertEquals(140, service.availability().code)

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, thirdKey.hex()), Instant.ofEpochMilli(4_000))
        val second = service.connectionSession()!!
        assertTrue(
            service.failCandidateOrRecord(
                second.generation, second.attemptId, setOf(PumpSession.AvailabilityCause.TRANSPORT), "read", now = 5_000
            )
        )

        assertEquals(140, service.availability().code)
        assertEquals("auth", service.availability().operation)
        assertEquals("V05.00.52", service.availability().firmware)
    }

    @Test
    fun `document review rejects future creation within capture skew before install`() {
        val (service) = service()
        val text = validDocument()
            .replace("2026-09-08T00:00:00Z", "2026-09-09T00:02:00Z")
            .replace("2026-09-08T00:01:00Z", "2026-09-09T00:03:00Z")
        assertThrows(IllegalArgumentException::class.java) {
            service.reviewDocument(ByteArrayInputStream(text.toByteArray()), Instant.parse("2026-09-09T00:00:00Z"))
        }
    }

    @Test
    fun `blank manual key and same key import preserve protected key generation and replay floor`() {
        val (service, store) = service()
        assertEquals(PumpSession.Installation.FIRST_PUMP, install(service))
        acceptFirst(service.owner, mac, key)
        val before = service.owner.activeRecord()!!

        val result = service.installManual(
            YpsoProvisioningService.ManualDraft(" $serial ", mac.lowercase(), null),
            Instant.ofEpochMilli(2_000)
        )

        val after = service.owner.activeRecord()!!
        assertEquals(PumpSession.Installation.SAME_KEY, result)
        assertEquals(before.generation, after.generation)
        assertNull(store.saved.candidateReplacesGeneration)
        assertEquals(100L, after.read)
        assertEquals(before.reboot, after.reboot)
        assertArrayEquals(key, service.connectionSession()!!.key)
        assertNull(store.saved.activeGeneration)
    }

    @Test
    fun `rotation and pump switch retain isolated generations and quiesce old tokens`() {
        val (service, store) = service()
        install(service)
        acceptFirst(service.owner, mac, key)
        val old = service.owner.open(mac, key)
        val oldGeneration = old.generation

        assertEquals(
            PumpSession.Installation.ROTATED_KEY,
            service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
        )
        assertThrows(IllegalStateException::class.java) { service.owner.begin(old) }
        val rotatedGeneration = service.owner.activeRecord()!!.generation
        assertTrue(rotatedGeneration != oldGeneration)
        service.markVerified(rotatedGeneration, service.connectionSession()!!.attemptId, serial, 2_500)

        val switchedKey = ByteArray(32) { (it + 65).toByte() }
        assertEquals(
            PumpSession.Installation.SWITCHED_PUMP,
            service.installManual(YpsoProvisioningService.ManualDraft(otherSerial, otherMac, switchedKey.hex()), Instant.ofEpochMilli(3_000))
        )
        // The authenticated rotation source and the verified rotation both remain as replay
        // tombstones; switching pumps adds the new identity without dropping learned floors.
        assertEquals(3, store.saved.records.size)
        assertEquals(otherMac, service.pending()!!.mac)
        assertEquals(setOf(mac, otherMac), store.saved.records.map { it.pump }.toSet())
        assertEquals(100L, store.saved.records.single { it.generation == oldGeneration }.read)
    }

    @Test
    fun `manual rotation never inherits the prior keys creation time`() {
        val (service) = service()
        val document = service.reviewDocument(ByteArrayInputStream(validDocument().toByteArray()), Instant.parse("2026-09-09T00:00:00Z"))
        service.installDocument(document, Instant.parse("2026-09-09T00:01:00Z"))
        service.connectionSession()!!.also { service.markVerified(it.generation, it.attemptId, serial, Instant.parse("2026-09-09T00:01:30Z").toEpochMilli()) }
        assertEquals(Instant.parse("2026-09-08T00:00:00Z"), service.installed()!!.createdAt)

        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            Instant.parse("2026-09-09T00:02:00Z")
        )

        assertNull(service.pending()!!.createdAt)
    }

    @Test
    fun `unresolved accounting refuses same key rotation and pump switch without mutation`() {
        val base = MemoryStore()
        val first = YpsoProvisioningService(PumpSession(base), YpsoPumpState(), Legacy())
        install(first)
        acceptFirst(first.owner, mac, key)
        base.saved = base.saved.copy(records = base.saved.records.map { it.copy(write = 41) })
        val service = YpsoProvisioningService(PumpSession(base), YpsoPumpState(), Legacy())
        val token = service.owner.open(mac, key)
        val transaction = service.owner.begin(token)
        service.owner.reserve(token, transaction)
        val before = base.saved

        val attempts = listOf(
            YpsoProvisioningService.ManualDraft(serial, mac, key.hex()),
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            YpsoProvisioningService.ManualDraft(otherSerial, otherMac, ByteArray(32) { 99 }.hex())
        )
        var quiesces = 0
        service.quiesceConnection = { quiesces++ }
        attempts.forEach { draft ->
            assertThrows(SecurityException::class.java) { service.installManual(draft, Instant.ofEpochMilli(3_000)) }
            assertEquals(before, base.saved)
        }
        assertEquals(0, quiesces)
    }

    @Test
    fun `verification requires independently observed matching serial and clears only status causes`() {
        val state = YpsoPumpState()
        val (service) = service(state = state)
        install(service)

        assertThrows(SecurityException::class.java) { service.markVerified(null, 2_000) }
        assertTrue(service.availability().causes.contains(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        assertEquals(PumpSession.AttemptStatus.FAILED, service.verificationState()!!.status)
        install(service)
        assertThrows(SecurityException::class.java) { service.markVerified(otherSerial, 3_000) }
        assertTrue(service.availability().causes.contains(PumpSession.AvailabilityCause.IDENTITY_MISMATCH))
        assertNull(service.installed())

        install(service)
        service.markVerified(serial, 4_000)
        assertEquals(Instant.ofEpochMilli(4_000), service.installed()!!.verifiedAt)
        assertEquals(serial, state.serialNumber)
        assertEquals(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN), service.availability().causes)
    }

    @Test
    fun `retry is bounded and transport notification starts at the documented threshold`() {
        val (service) = service()
        install(service)
        for (failure in 1..7) {
            val now = failure * 1_000L
            service.recordUnavailable(setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "poll", now = now)
            val availability = service.availability()
            assertEquals(failure.coerceAtMost(5), availability.failures)
            assertFalse(service.retryAllowed(now))
            assertEquals(failure >= 3, service.notificationRequired())
        }
        assertEquals(5 * 60_000L, service.availability().retryAt!! - 7_000L)
        assertTrue(service.retryAllowed(service.availability().retryAt!!))
    }

    @Test
    fun `legacy MAC key waits for real serial then upgrades the existing replay generation`() {
        val legacyRecord = PumpSession.Record(mac, PumpSession.fingerprint(key), "legacy-generation", 8, 77, null)
        val store = MemoryStore(PumpSession.State(records = listOf(legacyRecord)))
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(null, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)
        assertNull(service.installed())
        assertFalse(service.isConfigured())
        assertEquals(0, legacy.clears)

        assertEquals(
            PumpSession.Installation.SAME_KEY,
            service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, null), Instant.ofEpochMilli(2_000))
        )
        val installed = service.owner.activeRecord()!!
        assertTrue(installed.generation != "legacy-generation")
        assertEquals("legacy-generation", store.saved.candidateReplacesGeneration)
        assertEquals(77L, installed.read)
        assertEquals(serial, installed.serial)
        assertArrayEquals(key, service.connectionSession()!!.key)
        assertTrue(service.isConfigured())
        assertEquals(0, legacy.clears)
    }

    @Test
    fun `installation quiesce never holds the service monitor`() {
        val (service) = service()
        install(service)
        service.markVerified(serial, 1_500)
        val entered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        service.quiesceConnection = {
            entered.countDown()
            pool.execute {
                service.installed()
                completed.countDown()
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS), "quiesce called while service monitor was held")
        }
        try {
            service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `connection snapshot cannot acquire during install quiesce to commit interval`() {
        val (service) = service()
        install(service)
        val old = service.connectionSession()!!
        val quiescing = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val installer = Executors.newSingleThreadExecutor()
        service.quiesceConnection = {
            quiescing.countDown()
            assertTrue(releaseCommit.await(2, TimeUnit.SECONDS))
        }
        try {
            installer.submit {
                service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
            }
            assertTrue(quiescing.await(1, TimeUnit.SECONDS))
            assertFalse(service.isCurrentConnection(old), "old lease must be invalid while mutation is in progress")
        } finally {
            releaseCommit.countDown()
            installer.shutdown()
            assertTrue(installer.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `rejected verification enqueue restores a usable committed connection lease`() {
        val (service) = service()
        install(service)
        service.markVerified(serial, 1_500)

        assertThrows(IllegalStateException::class.java) {
            service.installManualAndStartVerification(
                YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
                Instant.ofEpochMilli(2_000)
            ) { false }
        }

        val restored = service.connectionSession()!!
        assertFalse(restored.candidate)
        assertTrue(service.isCurrentConnection(restored))
    }

    @Test
    fun `failed candidate over retained legacy credentials keeps the session connectable`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)
        val firstCandidate = service.connectionSession()!!

        assertTrue(firstCandidate.candidate)
        assertEquals(0, legacy.clears)
        assertTrue(service.failCandidateOrRecord(
            firstCandidate.generation, firstCandidate.attemptId,
            setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), "identity", now = 2_000
        ))
        service.awaitPendingSessionRestore()

        val restored = service.connectionSession()!!
        assertFalse(restored.candidate)
        assertArrayEquals(key, restored.key)
        assertEquals(serial, restored.serial)
        assertTrue(service.isConfigured())
        assertTrue(service.isCurrentConnection(restored))
        assertEquals(PumpSession.AttemptStatus.FAILED, service.verificationState()!!.status)
        assertEquals(key.hex(), legacy.credentials.key)
    }

    @Test
    fun `cancelling a legacy replacement keeps the retained session connectable`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)

        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            Instant.ofEpochMilli(2_000)
        )
        service.cancelCandidate()

        val restored = service.connectionSession()!!
        assertFalse(restored.candidate)
        assertArrayEquals(key, restored.key)
        assertFalse(PumpSession.AvailabilityCause.UNCONFIGURED in service.availability().causes)
        assertTrue(service.isCurrentConnection(restored))
        assertEquals(0, legacy.clears)
    }

    @Test
    fun `failed legacy candidate restores with its recorded backoff`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)
        val candidate = service.connectionSession()!!

        assertTrue(service.failCandidateOrRecord(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.TRANSPORT), "read", now = 2_000
        ))
        service.awaitPendingSessionRestore()

        val restored = service.connectionSession()!!
        assertFalse(restored.candidate)
        assertEquals(1, service.availability().failures)
        assertEquals(7_000L, service.availability().retryAt)
    }

    @Test
    fun `unscoped owner promotion and non-selected open are rejected`() {
        val (service) = service()
        install(service)
        assertThrows(IllegalStateException::class.java) { service.owner.markVerified(serial, 2_000) }

        assertTrue(service.markVerified(serial, 2_100))
        val committed = service.owner.committedRecord()!!
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(3_000))

        assertEquals(committed.generation, service.owner.committedRecord()!!.generation)
        assertThrows(SecurityException::class.java) { service.owner.open(mac, key) }
    }

    @Test
    fun `delayed retained restore keeps the failure recorded before scheduling`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)
        val candidate = service.connectionSession()!!
        val pending = mutableListOf<() -> Unit>()
        service.dispatchSessionRestore = { pending.add(it) }

        assertTrue(service.failCandidateOrRecord(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), "identity-read", now = 2_000
        ))
        val failure = service.availability()
        // Normal polling can observe an unconfigured session before the deferred restore runs.
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), operation = "connect", now = 2_500)
        assertTrue(PumpSession.AvailabilityCause.UNCONFIGURED in service.availability().causes)

        pending.forEach { it() }

        assertEquals(failure, service.availability())
        assertFalse(service.isSessionRestorePending())
    }

    @Test
    fun `promotion is rejected once cancellation has started`() {
        val (service) = service()
        install(service)
        val candidate = service.connectionSession()!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.quiesceConnection = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val canceller = Executors.newSingleThreadExecutor()
        try {
            val job = canceller.submit { service.cancelCandidate() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                service.markVerified(candidate.generation, candidate.attemptId, serial, 3_000)
            }
            release.countDown()
            job.get(2, TimeUnit.SECONDS)
            assertNull(service.connectionSession())
            assertEquals(PumpSession.AttemptStatus.CANCELLED, service.verificationState()!!.status)
        } finally {
            release.countDown()
            canceller.shutdownNow()
        }
    }

    @Test
    fun `a superseded deferred restore cannot replace newer failure evidence`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)
        val pending = mutableListOf<() -> Unit>()
        service.dispatchSessionRestore = { pending.add(it) }

        val firstCandidate = service.connectionSession()!!
        assertTrue(service.failCandidateOrRecord(
            firstCandidate.generation, firstCandidate.attemptId,
            setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), "identity-read", now = 2_000
        ))
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(3_000))
        val secondCandidate = service.connectionSession()!!
        assertTrue(service.failCandidateOrRecord(
            secondCandidate.generation, secondCandidate.attemptId,
            setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED), "auth", now = 4_000, code = 140
        ))

        pending.forEach { it() }

        assertEquals(2, pending.size)
        assertTrue(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in service.availability().causes)
        assertFalse(PumpSession.AvailabilityCause.IDENTITY_MISMATCH in service.availability().causes)
        assertEquals(140, service.availability().code)
    }

    @Test
    fun `failed dispatch cannot clear a newer restore reservation`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)
        val captured = mutableListOf<() -> Unit>()
        var firstDispatch = true
        service.dispatchSessionRestore = { task ->
            if (firstDispatch) {
                firstDispatch = false
                // Reserve a newer restore while the older dispatch is failing: the stale clear in the
                // failing dispatch must not disarm the newer reservation's pending marker.
                service.installManual(
                    YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
                    Instant.ofEpochMilli(3_000)
                )
                val newer = service.connectionSession()!!
                assertTrue(
                    service.failCandidateOrRecord(
                        newer.generation, newer.attemptId,
                        setOf(PumpSession.AvailabilityCause.TRANSPORT), "connect", now = 3_500
                    )
                )
                throw IllegalStateException("dispatch rejected")
            }
            captured.add(task)
        }

        val first = service.connectionSession()!!
        assertTrue(
            service.failCandidateOrRecord(
                first.generation, first.attemptId,
                setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), "identity-read", now = 2_000
            )
        )

        assertTrue(service.isSessionRestorePending(), "the newer reservation must stay armed")
        assertEquals(1, captured.size)
        captured.forEach { it() }
        assertFalse(service.isSessionRestorePending())
        val restored = service.availability().causes
        assertTrue(PumpSession.AvailabilityCause.TRANSPORT in restored, "newer evidence must survive the stale dispatch failure")
        assertFalse(PumpSession.AvailabilityCause.IDENTITY_MISMATCH in restored)
    }

    @Test
    fun `candidate failure never blocks on a held provisioning transaction`() {
        val service = YpsoProvisioningService(PumpSession(MemoryStore()), YpsoPumpState(), Legacy())
        install(service)
        val candidate = service.connectionSession()!!

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.quiesceConnection = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val installer = Executors.newSingleThreadExecutor()
        val failing = Executors.newSingleThreadExecutor()
        try {
            installer.submit {
                runCatching {
                    service.installManual(
                        YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
                        Instant.ofEpochMilli(2_000)
                    )
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val result = failing.submit<Boolean> {
                service.failCandidateOrRecord(
                    candidate.generation, candidate.attemptId,
                    setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), "read", now = 3_000
                )
            }
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            installer.shutdownNow()
            failing.shutdownNow()
        }
    }

    @Test
    fun `failed document install wipes the provided key`() {
        val (service) = service()
        val document = YpsoSessionDocument(
            serial = "12345678", mac = mac, sharedKey = ByteArray(32) { 7 },
            createdAt = Instant.EPOCH, capturedAt = Instant.EPOCH, rebootCounter = null, source = emptyMap<String, String>()
        )

        assertThrows(IllegalArgumentException::class.java) { service.installDocument(document, Instant.ofEpochMilli(2_000)) }

        assertArrayEquals(ByteArray(32), document.sharedKey)
    }

    @Test
    fun `a final attempt result is not replayed after restart`() {
        val (service, store) = service()
        install(service)
        val candidate = service.connectionSession()!!

        assertTrue(service.failCandidateOrRecord(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), "encrypted-status", now = 2_000
        ))
        assertEquals(PumpSession.AttemptStatus.FAILED, service.verificationState()!!.status)

        val restarted = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), Legacy())

        assertNull(restarted.verificationState())
    }

    @Test
    fun `candidate failure retains evidence while restoring the committed credentials`() {
        val (service) = service()
        install(service)
        service.markVerified(serial, 1_500)
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
        val candidate = service.connectionSession()!!

        assertTrue(service.failCandidateOrRecord(
            candidate.generation, candidate.attemptId,
            setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), "identity-read", now = 3_000
        ))

        assertNull(service.pending())
        assertArrayEquals(key, service.keyBytes())
        assertEquals(PumpSession.AttemptStatus.FAILED, service.verificationState()!!.status)
        assertTrue(PumpSession.AvailabilityCause.IDENTITY_MISMATCH in service.availability().causes)
        val restored = service.connectionSession()!!
        assertFalse(restored.candidate)
        assertTrue(service.isCurrentConnection(restored))
    }

    @Test
    fun `stale candidate failure cannot mutate a newer candidate availability`() {
        val (service) = service()
        install(service)
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(2_000))
        val old = service.connectionSession()!!
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(3_000))
        val replacement = service.connectionSession()!!
        val before = service.availability()

        assertFalse(service.failCandidateOrRecord(old.generation, old.attemptId, setOf(PumpSession.AvailabilityCause.IDENTITY_MISMATCH), "late", now = 4_000))
        assertEquals(replacement.generation, service.connectionSession()!!.generation)
        assertEquals(before, service.availability())
    }

    @Test
    fun `legacy MAC key migrates when bonded pump name independently supplies the real serial`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(null, mac, key.hex()))

        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy) { observedMac ->
            serial.takeIf { observedMac == mac }
        }

        assertNull(service.installed())
        assertEquals(serial, service.pending()!!.serial)
        assertEquals(mac, service.pending()!!.mac)
        assertArrayEquals(key, service.connectionSession()!!.key)
        assertEquals("legacy-preferences", service.pending()!!.source["profile"])
        assertEquals(0, legacy.clears)
    }

    @Test
    fun `complete validated legacy identity migrates automatically without inventing key age`() {
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(MemoryStore()), YpsoPumpState(), legacy)

        assertNull(service.installed())
        assertEquals(serial, service.pending()!!.serial)
        assertNull(service.pending()!!.createdAt)
        assertEquals("legacy-preferences", service.pending()!!.source["profile"])
        assertEquals(0, legacy.clears)
    }

    @Test
    fun `repeated unconfigured polls do not rewrite the protected journal`() {
        val (service, store) = service()

        repeat(10) {
            service.recordUnavailable(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), operation = "connect", now = it.toLong())
        }

        assertEquals(0, store.commits)
        assertEquals(setOf(PumpSession.AvailabilityCause.UNCONFIGURED), service.availability().causes)
        assertTrue(service.notificationRequired())
    }

    @Test
    fun `first protected migration does not inherit unconfigured retry delay`() {
        val delayed = PumpSession.Availability(
            causes = setOf(PumpSession.AvailabilityCause.UNCONFIGURED),
            since = 1_000,
            failures = 5,
            retryAt = 301_000
        )
        val store = MemoryStore(PumpSession.State(availability = delayed))
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))

        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy)

        assertEquals(0, service.availability().failures)
        assertNull(service.availability().retryAt)
        assertTrue(service.retryAllowed(2_000))
    }

    @Test
    fun `upgrade ignores a stale retry timestamp when no configured failure was recorded`() {
        val staleAvailability = PumpSession.Availability(
            causes = setOf(
                PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE,
                PumpSession.AvailabilityCause.COUNTER_UNCERTAIN
            ),
            since = 1_000,
            failures = 0,
            retryAt = 301_000
        )
        val record = PumpSession.Record(
            mac, PumpSession.fingerprint(key), "protected-generation", null, null, null,
            serial = serial, keyHex = key.hex(), importedAt = 1_000
        )
        val store = MemoryStore(PumpSession.State(listOf(record), record.generation, staleAvailability))

        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), Legacy())

        assertTrue(service.retryAllowed(2_000))
    }

    @Test
    fun `journal loss remains explicit and cannot be cleared by recording another condition`() {
        val (service, store) = service()
        install(service)
        store.unavailable = true

        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.AUTHENTICATION), operation = "auth", now = 2_000)

        assertTrue(service.availability().causes.contains(PumpSession.AvailabilityCause.AUTHENTICATION))
        assertTrue(service.availability().causes.contains(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        assertTrue(service.notificationRequired())
    }

    @Test
    fun `availability and notification decision survive service restart`() {
        val store = MemoryStore()
        val first = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), Legacy())
        install(first)
        first.recordUnavailable(
            setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED),
            code = 140,
            operation = "status-characteristic",
            firmware = "V05.00.52",
            now = 2_000
        )

        val restarted = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), Legacy())

        assertTrue(restarted.notificationRequired())
        assertEquals(140, restarted.availability().code)
        assertEquals("status-characteristic", restarted.availability().operation)
        assertEquals("V05.00.52", restarted.availability().firmware)
    }

    @Test
    fun `rekey remains sticky preserves evidence and blocks automatic retry`() {
        val (service) = service()
        install(service)
        service.recordUnavailable(
            setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED),
            code = 140,
            operation = "status-characteristic",
            firmware = "V05.00.52",
            now = 2_000
        )

        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "connect", now = 3_000)

        assertEquals(
            setOf(
                PumpSession.AvailabilityCause.TRANSPORT,
                PumpSession.AvailabilityCause.COUNTER_UNCERTAIN,
                PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED
            ),
            service.availability().causes
        )
        assertEquals(140, service.availability().code)
        assertEquals("status-characteristic", service.availability().operation)
        assertEquals("V05.00.52", service.availability().firmware)
        assertNull(service.availability().retryAt)
        assertFalse(service.retryAllowed(Long.MAX_VALUE))
    }

    @Test
    fun `replacement save retains rekey until verified while explicit verification gets one attempt`() {
        val (service) = service()
        install(service)
        service.recordUnavailable(
            setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED),
            code = 140,
            operation = "status-characteristic",
            firmware = "V05.00.52",
            now = 2_000
        )
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(3_000))

        assertTrue(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in service.availability().causes)
        assertEquals(140, service.availability().code)
        assertEquals(0, service.availability().failures)
        assertNull(service.availability().retryAt)
        service.requestVerificationAttempt()
        assertTrue(service.retryAllowed(4_000))
        assertFalse(service.retryAllowed(Long.MAX_VALUE))

        service.markVerified(serial, 5_000)
        assertEquals(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN), service.availability().causes)
    }

    @Test
    fun `fresh install does not inherit prior transport failures or backoff`() {
        val (service) = service()
        install(service)
        repeat(4) {
            service.recordUnavailable(setOf(PumpSession.AvailabilityCause.TRANSPORT), operation = "poll", now = 2_000L + it)
        }
        assertTrue(service.availability().failures > 0)

        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(9_000))

        assertEquals(0, service.availability().failures)
        assertNull(service.availability().retryAt)
        assertFalse(PumpSession.AvailabilityCause.TRANSPORT in service.availability().causes)
        assertTrue(service.retryAllowed(9_001))
    }

    @Test
    fun `re-saving the identical rejected key is refused while rekey is sticky`() {
        val (service) = service()
        install(service)
        service.recordUnavailable(
            setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED),
            code = 140,
            operation = "status-characteristic",
            firmware = "V05.00.52",
            now = 2_000
        )

        val sameKey = assertThrows(YpsoProvisioningService.ManualValidationException::class.java) {
            service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.hex()), Instant.ofEpochMilli(3_000))
        }
        assertEquals(YpsoProvisioningService.ManualField.KEY, sameKey.field)

        val blankKey = assertThrows(YpsoProvisioningService.ManualValidationException::class.java) {
            service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, null), Instant.ofEpochMilli(3_001))
        }
        assertEquals(YpsoProvisioningService.ManualField.KEY, blankKey.field)

        // A genuinely different current key is still accepted as the replacement session.
        service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()), Instant.ofEpochMilli(3_002))
        assertTrue(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in service.availability().causes)
    }

    @Test
    fun `write-counter state alone never demands an operator notification`() {
        val (service) = service()
        install(service)
        service.markVerified(serial, 2_000)

        assertEquals(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN), service.availability().causes)
        assertFalse(service.notificationRequired())
    }

    private fun validDocument() = """
        {
          "schema_version": 1,
          "pump": {"mac": "$mac", "serial": "$serial"},
          "shared_key": "${key.hex()}",
          "created_at": "2026-09-08T00:00:00Z",
          "captured_at": "2026-09-08T00:01:00Z",
          "reboot_counter": null,
          "source": {"profile": "mylife-v1", "package": "com.example", "app_version": "1.0", "identity": "mylife-db-v1"}
        }
    """.trimIndent()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
