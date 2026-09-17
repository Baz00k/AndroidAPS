package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.provisioning.YpsoOwnershipHandoff
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoOwnershipHandoffTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val pump = "EC:2A:F0:00:00:01"
    private val serial = "10000001"

    private class MemoryStore(initial: PumpSession.State = PumpSession.State()) : PumpSession.Store {
        var saved = initial
        override fun load() = saved
        override fun commit(state: PumpSession.State) { saved = state }
    }

    private fun qualifiedRecord(): PumpSession.Record {
        val store = MemoryStore()
        val owner = PumpSession(store)
        owner.provisionReadBaseline(pump, key, reboot = 21, read = 2_999)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(
                    write = 4_279,
                    writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                    serial = serial,
                    verifiedAt = 10,
                    verifiedSerial = serial,
                )
            },
        )
        val session = PumpSession(store)
        val token = session.open(pump, key)
        val transaction = session.begin(token)
        val reservation = session.reserve(
            token,
            transaction,
            PumpSession.WriteIntent("profile-setting-1", "setting-id", "SETTINGS_SELECTOR", "ab".repeat(32)),
        )
        session.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        session.advance(token, transaction, PumpSession.Phase.ACKED)
        session.finish(token, transaction)
        session.resolveWrite(token, reservation.id, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "same-link exact setting identity")
        return session.snapshot()!!
    }

    private fun source() = YpsoOwnershipHandoff.SourceArtifacts(
        packageName = "app.aaps.ypso.writebench",
        apkSha256 = "01".repeat(32),
        signerSha256 = "02".repeat(32),
        journalSha256 = "03".repeat(32),
        evidenceSha256 = "04".repeat(32),
        historySha256 = "05".repeat(32),
        profileSha256 = "06".repeat(32),
    )

    @Test
    fun `handoff round trip preserves the complete qualified accounting record without key material`() {
        val record = qualifiedRecord()
        val encoded = YpsoOwnershipHandoff.encode(record, key, 20, "ef".repeat(32), source())

        val reviewed = YpsoOwnershipHandoff.parse(encoded, key)

        assertEquals(record.copy(keyHex = null), reviewed.record)
        assertNull(reviewed.record.keyHex)
        assertEquals("ef".repeat(32), reviewed.reviewedEvidenceSha256)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(encoded).hex(), reviewed.documentSha256)
    }

    @Test
    fun `handoff rejects tampering wrong key and unverified write ownership`() {
        val record = qualifiedRecord()
        val encoded = YpsoOwnershipHandoff.encode(record, key, 20, "ef".repeat(32), source())
        val tampered = encoded.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }

        assertThrows(Exception::class.java) { YpsoOwnershipHandoff.parse(tampered, key) }
        assertThrows(IllegalArgumentException::class.java) { YpsoOwnershipHandoff.parse(encoded, ByteArray(32) { 9 }) }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoOwnershipHandoff.encode(record.copy(reservation = record.reservation!!.copy(phase = PumpSession.Phase.ACKED)), key, 20, "ef".repeat(32), source())
        }
        assertThrows(IllegalArgumentException::class.java) {
            YpsoOwnershipHandoff.encode(record.copy(serial = ""), key, 20, "ef".repeat(32), source())
        }
    }

    @Test
    fun `adoption keeps local generation and key while transferring the full epoch and evidence`() {
        val imported = qualifiedRecord()
        val store = MemoryStore()
        val owner = PumpSession(store)
        owner.provisionReadBaseline(pump, key, reboot = 21, read = 100)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(serial = serial, verifiedAt = 10, verifiedSerial = serial, write = null, reservation = null)
            },
            availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
        )
        val local = PumpSession(store)
        val generation = local.committedRecord()!!.generation
        val keyHex = local.committedRecord()!!.keyHex

        local.adoptOwnershipHandoff(imported, 30, mapOf("ownership_handoff_sha256" to "aa".repeat(32)))

        val adopted = local.committedRecord()!!
        assertEquals(generation, adopted.generation)
        assertEquals(keyHex, adopted.keyHex)
        assertEquals(21, adopted.reboot)
        assertEquals(imported.read, adopted.read)
        assertEquals(4_280, adopted.write)
        assertEquals(imported.reservation, adopted.reservation)
        assertEquals(imported.writeEvidence, adopted.writeEvidence)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN !in local.availability().causes)
    }

    @Test
    fun `adoption rejects another identity epoch or local write floor`() {
        val imported = qualifiedRecord()
        fun local(write: Long? = null, reboot: Int = 21): PumpSession {
            val store = MemoryStore()
            PumpSession(store).provisionReadBaseline(pump, key, reboot, 100)
            store.saved = store.saved.copy(
                records = store.saved.records.map {
                    it.copy(serial = serial, verifiedAt = 10, verifiedSerial = serial, write = write,
                        writeBootstrapState = if (write == null) PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH else PumpSession.WriteBootstrapState.ESTABLISHED)
                },
            )
            return PumpSession(store)
        }

        assertThrows(IllegalStateException::class.java) { local(reboot = 22).adoptOwnershipHandoff(imported, 30, emptyMap()) }
        assertThrows(IllegalStateException::class.java) { local(write = 50).adoptOwnershipHandoff(imported, 30, emptyMap()) }
        assertThrows(IllegalStateException::class.java) {
            local().adoptOwnershipHandoff(imported.copy(serial = "other"), 30, emptyMap())
        }
    }

    @Test
    fun `newer reviewed epoch replaces stale counters without numerically merging them`() {
        val imported = qualifiedRecord()
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline(pump, key, reboot = 18, read = 9)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(
                    serial = serial,
                    verifiedAt = 10,
                    verifiedSerial = serial,
                    writeBootstrapState = PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH,
                )
            },
        )
        val local = PumpSession(store)

        local.adoptOwnershipHandoff(imported, 30, emptyMap())

        val adopted = local.committedRecord()!!
        assertEquals(21, adopted.reboot)
        assertEquals(2_999, adopted.read)
        assertEquals(4_280, adopted.write)
        assertEquals(imported.reservation, adopted.reservation)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
