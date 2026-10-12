package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.KeyTiming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.pump.ypsopump.provisioning.KeyTimingRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyTimingProvisioningTest {
    private class Store : PumpSession.Store {
        var state = PumpSession.State()
        override fun load() = state
        override fun commit(state: PumpSession.State) { this.state = state }
    }
    private val store = Store()
    private val timingStore = KeyTimingRepository.MemoryStore()
    private var service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = KeyTimingRepository(timingStore))
    private val serial = "10000001"
    private val mac = "EC:2A:F0:00:00:01"
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val created = Instant.parse("2026-09-10T12:00:00Z")
    private val imported = created.plusSeconds(10 * 24 * 3600)
    private fun document(material: ByteArray = key, date: Instant = created) = YpsoSessionDocument(
        serial, mac, material.copyOf(), date, imported, null, emptyMap())
    private fun verify(material: ByteArray = key) {
        val token = service.owner.open(mac, material)
        val transaction = service.owner.begin(token)
        service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 1,
            (service.owner.snapshot()?.read ?: 0) + 1))
        service.owner.finish(token, transaction)
        service.markVerified(serial, imported.toEpochMilli())
    }
    private fun install() { service.installDocument(document(), imported); verify() }
    private fun restart() {
        val repository = KeyTimingRepository(timingStore)
        repository.get("$mac/${service.owner.committedRecord()!!.keyId}")
        service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = repository)
        service.elapsedRealtime = { 0 }
    }

    @Test fun `same key reimport cannot renew source or first import or postpone manual reminder`() {
        install()
        val generation = service.installed()!!.generation
        val expiry = created.plusSeconds(28 * 24 * 3600).toEpochMilli()
        val reminder = imported.toEpochMilli() - 1
        service.setKeyDates(generation, expiry, reminder, imported.toEpochMilli())
        val timing = service.installed()!!.keyTiming
        val old = service.owner.committedRecord()!!
        service.installDocument(document(date = imported), imported.plusSeconds(3600))
        verify()
        restart()
        val current = service.owner.committedRecord()!!
        assertEquals(old.createdAt, current.createdAt)
        assertEquals(old.importedAt, current.importedAt)
        assertEquals(timing, service.installed()!!.keyTiming)
        assertTrue(service.observeKeyTiming(imported.minusSeconds(3600).toEpochMilli())!!.reminderDue)
    }

    @Test fun `manual same key save retains estimate and manual dates across restart`() {
        val draft = YpsoProvisioningService.ManualDraft(serial, mac, key.joinToString("") { "%02x".format(it) })
        service.installManual(draft, imported)
        verify()
        val first = service.installed()!!
        assertNull(first.createdAt)
        assertEquals(KeyTiming.Origin.IMPORT_ESTIMATE, service.observeKeyTiming(imported.toEpochMilli())!!.origin)
        service.setKeyDates(first.generation, imported.toEpochMilli() + 1000, imported.toEpochMilli() + 500, imported.toEpochMilli())
        val timing = service.installed()!!.keyTiming
        service.installManual(draft, imported.plusSeconds(86_400))
        verify()
        restart()
        assertEquals(imported, service.installed()!!.importedAt)
        assertEquals(timing, service.installed()!!.keyTiming)
    }

    @Test fun `failed replacement leaves dates and due reminder but successful replacement has its own timing`() {
        install()
        val active = service.installed()!!
        service.setKeyDates(active.generation, imported.toEpochMilli() + 1000, imported.toEpochMilli() - 1, imported.toEpochMilli())
        val timing = service.installed()!!.keyTiming
        val replacement = ByteArray(32) { 99 }
        service.installDocument(document(replacement, imported), imported)
        assertEquals(timing, service.installed()!!.keyTiming)
        val candidate = service.connectionSession()!!
        assertTrue(service.rejectCandidate(candidate.generation, candidate.attemptId))
        restart()
        assertEquals(timing, service.installed()!!.keyTiming)
        assertTrue(service.observeKeyTiming(imported.toEpochMilli())!!.reminderDue)
        service.installDocument(document(replacement, imported), imported)
        verify(replacement)
        restart()
        assertEquals(KeyTiming(), service.installed()!!.keyTiming)
        assertEquals(imported, service.installed()!!.createdAt)
    }

    @Test fun `date edits do not clear rejection enable retry alter counters or discard uncertain writes`() {
        install()
        val token = service.owner.open(mac, key)
        val transaction = service.owner.begin(token)
        service.owner.reserve(token, transaction)
        service.owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        service.owner.finish(token, transaction)
        service.recordUnavailable(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED, PumpSession.AvailabilityCause.KEY_REJECTED), code = 140)
        val before = service.owner.committedRecord()!!
        val availability = service.availability()
        service.setKeyDates(before.generation, imported.toEpochMilli() + 9999, imported.toEpochMilli() + 8888, imported.toEpochMilli())
        assertEquals(before, service.owner.committedRecord())
        assertEquals(availability, service.availability())
        assertFalse(service.retryAllowed())
        assertThrows(YpsoProvisioningService.ReplacementKeyRequiredException::class.java) {
            service.installDocument(document(), imported)
        }
        restart()
        assertFalse(service.retryAllowed())
        assertEquals(before.reservation, service.owner.committedRecord()!!.reservation)
    }

    @Test fun `date callback for replaced generation cannot edit newly verified key`() {
        install()
        val original = service.installed()!!.generation
        val replacement = ByteArray(32) { 99 }
        service.installDocument(document(replacement, imported), imported)
        verify(replacement)
        assertThrows(IllegalStateException::class.java) { service.setKeyDates(original, 1000, 2000) }
        assertEquals(KeyTiming(), service.installed()!!.keyTiming)
    }

    @Test fun `monotonic elapsed time keeps reminder advancing through backwards wall clock and restart`() {
        install()
        var elapsed = 1000L
        service.elapsedRealtime = { elapsed }
        val baseline = imported.toEpochMilli()
        service.setKeyDates(service.installed()!!.generation, baseline + 300_000, baseline + 120_000, baseline)
        assertFalse(service.observeKeyTiming(baseline)!!.reminderDue)
        elapsed += 120_000
        assertTrue(service.observeKeyTiming(baseline - 86_400_000)!!.reminderDue)
        restart()
        service.elapsedRealtime = { 0 }
        assertTrue(service.observeKeyTiming(baseline - 86_400_000)!!.reminderDue)
        assertEquals(180_000L, service.observeKeyTiming(baseline - 86_400_000)!!.remainingMs)
    }

    @Test fun `editing timing during an owned transaction survives its next authenticated counter update`() {
        install()
        val token = service.owner.open(mac, key)
        val transaction = service.owner.begin(token)
        val before = service.owner.snapshot()!!
        service.setKeyDates(before.generation, imported.toEpochMilli() + 1000, imported.toEpochMilli() + 500, imported.toEpochMilli())
        val timing = service.installed()!!.keyTiming
        service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 1, before.read!! + 1))
        service.owner.finish(token, transaction)
        restart()
        assertEquals(timing, service.installed()!!.keyTiming)
        assertEquals(before.read + 1, service.owner.committedRecord()!!.read)
    }

    @Test fun `reminder-only edit preserves expiry age through rollback and restart`() {
        install()
        service.elapsedRealtime = { 0 }
        val day20 = created.plusSeconds(20 * 86400).toEpochMilli()
        val day10 = imported.toEpochMilli()
        assertEquals(8 * KeyTiming.DAY_MS, service.observeKeyTiming(day20)!!.remainingMs)
        service.setKeyDates(service.installed()!!.generation, null, day10 + KeyTiming.DAY_MS, day10)
        assertEquals(8 * KeyTiming.DAY_MS, service.observeKeyTiming(day10)!!.remainingMs)
        restart()
        service.elapsedRealtime = { 0 }
        assertEquals(8 * KeyTiming.DAY_MS, service.observeKeyTiming(day10)!!.remainingMs)
    }

    @Test fun `field-specific reminder edit loads persisted expiry instead of overwriting from stale cache`() {
        install()
        val generation = service.installed()!!.generation
        val expiry = imported.toEpochMilli() + 1_000_000
        service.setKeyDates(generation, expiry, null, imported.toEpochMilli())
        service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = KeyTimingRepository(timingStore))
        assertFalse(service.installed()!!.keyDatesLoaded)
        assertNull(service.installed()!!.keyTiming.expiryOverride)
        service.setKeyReminder(generation, imported.toEpochMilli() + 500, imported.toEpochMilli())
        assertTrue(service.installed()!!.keyDatesLoaded)
        assertEquals(expiry, service.installed()!!.keyTiming.expiryOverride)
        restart()
        assertEquals(expiry, service.installed()!!.keyTiming.expiryOverride)
    }

    @Test fun `failed advisory load and writes preserve live session uncertain reservation and availability`() {
        install()
        val failingStore = object : KeyTimingRepository.Store {
            var failLoad = false
            override fun load(): Map<String, KeyTiming> { check(!failLoad); return emptyMap() }
            override fun save(values: Map<String, KeyTiming>) { error("Advisory disk unavailable") }
        }
        for (loadFailure in listOf(false, true)) {
            failingStore.failLoad = loadFailure
            service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = KeyTimingRepository(failingStore))
            val token = service.owner.open(mac, key)
            val transaction = service.owner.begin(token)
            if (service.owner.snapshot()!!.reservation == null) {
                service.owner.reserve(token, transaction)
                service.owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
            }
            val before = service.owner.committedRecord()!!
            val availability = service.availability()
            assertThrows(IllegalStateException::class.java) { service.observeKeyTiming(imported.toEpochMilli()) }
            assertThrows(IllegalStateException::class.java) { service.setKeyDates(before.generation, 12345, 6789, imported.toEpochMilli()) }
            assertEquals(before, service.owner.committedRecord())
            assertEquals(before, store.state.records.single { it.generation == store.state.activeGeneration })
            assertEquals(availability, service.availability())
            service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 1, before.read!! + 1))
            service.owner.finish(token, transaction)
            assertEquals(before.reservation, service.owner.committedRecord()!!.reservation)
        }
    }

    @Test fun `blocked reminder storage cannot block therapy owner or provisioning monitor`() {
        install()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slowStore = object : KeyTimingRepository.Store {
            override fun load() = emptyMap<String, KeyTiming>()
            override fun save(values: Map<String, KeyTiming>) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = KeyTimingRepository(slowStore))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val observation = executor.submit { service.observeKeyTiming(imported.toEpochMilli()) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            executor.submit {
                assertTrue(service.isConfigured())
                assertNotNull(service.installed())
                val token = service.owner.open(mac, key)
                val transaction = service.owner.begin(token)
                val before = service.owner.snapshot()!!
                service.owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 1, before.read!! + 1))
                service.owner.finish(token, transaction)
            }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            observation.get(2, java.util.concurrent.TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `same-key promotion during advisory save retains dates without touching successor key`() {
        install()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val memory = KeyTimingRepository.MemoryStore()
        val slow = object : KeyTimingRepository.Store {
            override fun load() = memory.load()
            override fun save(values: Map<String, KeyTiming>) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); memory.save(values) }
        }
        service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = KeyTimingRepository(slow))
        val generation = service.installed()!!.generation
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val edit = executor.submit { service.setKeyDates(generation, imported.toEpochMilli() + 1000, imported.toEpochMilli() + 500, imported.toEpochMilli()) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            service.installDocument(document(), imported)
            verify()
            release.countDown()
            edit.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(imported.toEpochMilli() + 1000, service.installed()!!.keyTiming.expiryOverride)
            val replacement = ByteArray(32) { 99 }
            service.installDocument(document(replacement, imported), imported)
            verify(replacement)
            assertEquals(KeyTiming(), service.installed()!!.keyTiming)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `observation completed after different-key promotion cannot publish predecessor expiry`() {
        install()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = object : KeyTimingRepository.Store {
            override fun load() = emptyMap<String, KeyTiming>()
            override fun save(values: Map<String, KeyTiming>) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        service = YpsoProvisioningService(PumpSession(store), YpsoPumpState(), keyDates = KeyTimingRepository(slow))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val observation = executor.submit<KeyTiming.Status?> { service.observeKeyTiming(created.plusSeconds(29 * 86400).toEpochMilli()) }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val replacement = ByteArray(32) { 99 }
            service.installDocument(document(replacement, imported), imported)
            verify(replacement)
            release.countDown()
            assertNull(observation.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(KeyTiming(), service.installed()!!.keyTiming)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
