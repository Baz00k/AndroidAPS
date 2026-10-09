package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.KeyTiming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
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
    private var service = YpsoProvisioningService(PumpSession(store), YpsoPumpState())
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
    private fun restart() { service = YpsoProvisioningService(PumpSession(store), YpsoPumpState()) }

    @Test fun `same key reimport cannot renew source or first import or postpone manual reminder`() {
        install()
        val generation = service.installed()!!.generation
        val expiry = created.plusSeconds(28 * 24 * 3600).toEpochMilli()
        val reminder = imported.toEpochMilli() - 1
        service.setKeyDates(generation, expiry, reminder, imported.toEpochMilli())
        val old = service.owner.committedRecord()!!
        service.installDocument(document(date = imported), imported.plusSeconds(3600))
        verify()
        restart()
        val current = service.owner.committedRecord()!!
        assertEquals(old.createdAt, current.createdAt)
        assertEquals(old.importedAt, current.importedAt)
        assertEquals(old.keyTiming, current.keyTiming)
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
        assertEquals(before.copy(keyTiming = service.installed()!!.keyTiming), service.owner.committedRecord())
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
}
