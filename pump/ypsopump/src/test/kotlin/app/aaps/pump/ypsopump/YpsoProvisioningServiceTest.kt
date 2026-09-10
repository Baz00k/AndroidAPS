package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.operatorCauses
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoProvisioningServiceTest {
    private val serial = "10175983"
    private val mac = "EC:2A:F0:02:AF:6F"
    private val otherSerial = "10175984"
    private val otherMac = "EC:2A:F0:02:AF:70"
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
        override fun clear() { clears++; credentials = YpsoProvisioningService.LegacyCredentials(null, null, null) }
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
        assertEquals(100L, after.read)
        assertEquals(before.reboot, after.reboot)
        assertArrayEquals(key, service.keyBytes())
        assertEquals(after.generation, store.saved.activeGeneration)
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

        val switchedKey = ByteArray(32) { (it + 65).toByte() }
        assertEquals(
            PumpSession.Installation.SWITCHED_PUMP,
            service.installManual(YpsoProvisioningService.ManualDraft(otherSerial, otherMac, switchedKey.hex()), Instant.ofEpochMilli(3_000))
        )
        assertEquals(3, store.saved.records.size)
        assertEquals(otherMac, service.installed()!!.mac)
        assertEquals(setOf(mac, otherMac), store.saved.records.map { it.pump }.toSet())
    }

    @Test
    fun `manual rotation never inherits the prior keys creation time`() {
        val (service) = service()
        val document = service.reviewDocument(ByteArrayInputStream(validDocument().toByteArray()), Instant.parse("2026-09-09T00:00:00Z"))
        service.installDocument(document, Instant.parse("2026-09-09T00:01:00Z"))
        assertEquals(Instant.parse("2026-09-08T00:00:00Z"), service.installed()!!.createdAt)

        service.installManual(
            YpsoProvisioningService.ManualDraft(serial, mac, rotatedKey.hex()),
            Instant.parse("2026-09-09T00:02:00Z")
        )

        assertNull(service.installed()!!.createdAt)
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
        assertThrows(SecurityException::class.java) { service.markVerified(otherSerial, 3_000) }
        assertTrue(service.availability().causes.contains(PumpSession.AvailabilityCause.IDENTITY_MISMATCH))
        assertNull(service.installed()!!.verifiedAt)

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
        assertEquals("legacy-generation", installed.generation)
        assertEquals(77L, installed.read)
        assertEquals(serial, installed.serial)
        assertArrayEquals(key, service.keyBytes())
        assertTrue(service.isConfigured())
        assertEquals(1, legacy.clears)
    }

    @Test
    fun `legacy MAC key migrates when bonded pump name independently supplies the real serial`() {
        val store = MemoryStore()
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(null, mac, key.hex()))

        val service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), legacy) { observedMac ->
            serial.takeIf { observedMac == mac }
        }

        assertEquals(serial, service.installed()!!.serial)
        assertEquals(mac, service.installed()!!.mac)
        assertArrayEquals(key, service.keyBytes())
        assertEquals("legacy-preferences", service.installed()!!.source["profile"])
        assertEquals(1, legacy.clears)
    }

    @Test
    fun `complete validated legacy identity migrates automatically without inventing key age`() {
        val legacy = Legacy(YpsoProvisioningService.LegacyCredentials(serial, mac, key.hex()))
        val service = YpsoProvisioningService(PumpSession(MemoryStore()), YpsoPumpState(), legacy)

        assertEquals(serial, service.installed()!!.serial)
        assertNull(service.installed()!!.createdAt)
        assertEquals("legacy-preferences", service.installed()!!.source["profile"])
        assertEquals(1, legacy.clears)
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
        assertEquals(emptySet<PumpSession.AvailabilityCause>(), service.availability().causes.operatorCauses())
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
