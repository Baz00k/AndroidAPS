package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PumpSessionTest {
    private val key = ByteArray(32) { it.toByte() }
    private val pump = "12:34:56:78:9A:BC"

    private class MemoryStore : PumpSession.Store {
        var saved = PumpSession.State()
        var fault = 0
        override fun load(): PumpSession.State {
            check(fault != 3) { "Lost/restored journal" }
            return saved
        }
        override fun commit(state: PumpSession.State) {
            check(fault != 1) { "Crash before persistence" }
            saved = state
            check(fault != 2) { "Crash after persistence before return" }
        }
    }

    private fun initialized(store: MemoryStore) = PumpSession(store).apply { provisionReadBaseline(pump, key, 8, 100) }
    private fun accept(owner: PumpSession, token: PumpSession.Token, counter: Long, reboot: Int = 8) {
        val transaction = owner.begin(token)
        try { owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(0), reboot, counter)) }
        finally { owner.finish(token, transaction) }
    }

    @Test
    fun `observed reboot commits independent floor and invalidates old connection`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
        val owner = PumpSession(store)
        val old = owner.open(pump, key)
        val id = owner.begin(old)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(old, id, SessionCrypto.Message(byteArrayOf(0), 9, 1), true)
        }
        assertEquals(9, store.saved.records.single().reboot)
        assertEquals(1L, store.saved.records.single().read)
        assertNull(store.saved.records.single().write)
        assertThrows(IllegalStateException::class.java) { owner.begin(old) }
        val restored = PumpSession(store)
        val token = restored.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(restored, token, 1, 9) }
        assertThrows(SecurityException::class.java) { accept(restored, token, 101, 8) }
        accept(restored, token, 2, 9)
    }

    @Test
    fun `observed reboot failures never publish or partially update`() {
        for (fault in 1..2) {
            val store = MemoryStore()
            val owner = initialized(store)
            val token = owner.open(pump, key)
            val id = owner.begin(token)
            store.fault = fault
            assertThrows(SecurityException::class.java) {
                owner.accept(token, id, SessionCrypto.Message(byteArrayOf(), 9, 7), true)
            }
            assertNull(owner.snapshot())
            assertEquals(if (fault == 1) 8 else 9, store.saved.records.single().reboot)
            assertEquals(if (fault == 1) 100L else 7L, store.saved.records.single().read)
        }
    }

    @Test
    fun `reboot policy rejects jumps zero counter and outstanding reservations`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        val id = owner.begin(token)
        for ((reboot, read) in listOf(7 to 101L, 10 to 1L, 9 to 0L)) {
            assertThrows(SecurityException::class.java) {
                owner.accept(token, id, SessionCrypto.Message(byteArrayOf(), reboot, read), true)
            }
        }
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(write = 42, reservation = PumpSession.Reservation("pending", 42, PumpSession.Phase.POSSIBLY_SENT))
        })
        val restored = PumpSession(store)
        val next = restored.open(pump, key)
        assertThrows(IllegalStateException::class.java) {
            restored.accept(next, restored.begin(next), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        assertEquals(8, store.saved.records.single().reboot)
    }

    @Test
    fun `reconnect and process restart retain replay floor`() {
        val store = MemoryStore()
        var owner = initialized(store)
        var token = owner.open(pump, key)
        accept(owner, token, 101)
        owner.quiesce()
        token = owner.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(owner, token, 101) }
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(owner, token, 100) }
        accept(owner, token, 102)
        assertEquals(102L, store.saved.records.single().read)
    }

    @Test
    fun `larger lower and invalid generations do not partially mutate either counter`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        val before = store.saved
        for (reboot in listOf(7, 9, Int.MAX_VALUE)) {
            assertThrows(SecurityException::class.java) { accept(owner, token, 101, reboot) }
            assertEquals(before, store.saved)
        }
        assertThrows(IllegalArgumentException::class.java) { accept(owner, token, 101, -1) }
        assertThrows(IllegalArgumentException::class.java) { accept(owner, token, -1) }
        assertEquals(before, store.saved)
    }

    @Test
    fun `same key import preserves floor and wrong pump cannot reuse key`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        accept(owner, token, 101)
        assertThrows(IllegalStateException::class.java) { owner.provisionReadBaseline(pump, key, 8, 100) }
        owner.provisionReadBaseline(pump, key, 8, 101)
        assertEquals(token.generation, owner.open(pump, key).generation)
        assertThrows(SecurityException::class.java) { owner.open("other", key) }
        assertThrows(IllegalStateException::class.java) { owner.provisionReadBaseline("other", key, 8, 102) }
        assertThrows(IllegalStateException::class.java) { owner.begin(token) }
    }

    @Test
    fun `bench write baseline is one-time epoch-bound and cannot erase uncertainty`() {
        val store = MemoryStore()
        val owner = initialized(store)

        owner.provisionBenchWriteBaseline(pump, key, 8, 42)
        assertEquals(42L, store.saved.records.single().write)
        owner.provisionBenchWriteBaseline(pump, key, 8, 42)
        assertThrows(IllegalStateException::class.java) { owner.provisionBenchWriteBaseline(pump, key, 8, 41) }
        assertThrows(IllegalStateException::class.java) { owner.provisionBenchWriteBaseline(pump, key, 9, 42) }

        val active = PumpSession(store)
        val token = active.open(pump, key)
        val transaction = active.begin(token)
        active.reserve(token, transaction)
        active.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        active.finish(token, transaction)
        assertThrows(IllegalStateException::class.java) { active.provisionBenchWriteBaseline(pump, key, 8, 43) }
    }

    @Test
    fun `new key isolates generation and quiesces outstanding transactions`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val old = owner.open(pump, key)
        val transaction = owner.begin(old)
        assertThrows(IllegalStateException::class.java) { owner.begin(old) }
        val nextKey = ByteArray(32) { 42 }
        owner.provisionReadBaseline(pump, nextKey, 0, 0)
        val next = owner.open(pump, nextKey)
        assertNotEquals(old.generation, next.generation)
        assertThrows(IllegalStateException::class.java) { owner.accept(old, transaction, SessionCrypto.Message(byteArrayOf(), 8, 102)) }
        accept(owner, next, 1, 0)
        assertEquals(100L, store.saved.records.first().read)
    }

    @Test
    fun `missing lost or restored state cannot select a seed maximum`() {
        val store = MemoryStore()
        assertThrows(SecurityException::class.java) { PumpSession(store).open(pump, key) }
        initialized(store)
        store.fault = 3
        val unavailable = PumpSession(store)
        assertThrows(SecurityException::class.java) { unavailable.open(pump, key) }
        assertThrows(SecurityException::class.java) { unavailable.provisionReadBaseline(pump, key, 8, Long.MAX_VALUE) }
    }

    @Test
    fun `read commit crash suppresses publication and poisons current owner`() {
        for (fault in 1..2) {
            val store = MemoryStore()
            val owner = initialized(store)
            val token = owner.open(pump, key)
            store.fault = fault
            assertThrows(SecurityException::class.java) { accept(owner, token, 101) }
            assertNull(owner.snapshot())
            assertThrows(IllegalStateException::class.java) { owner.begin(token) }
            store.fault = 0
            val restarted = PumpSession(store)
            val next = restarted.open(pump, key)
            if (fault == 2) assertThrows(SecurityException::class.java) { accept(restarted, next, 101) }
            else accept(restarted, next, 101) // No plaintext was returned before the failed commit.
        }
    }

    @Test
    fun `authenticated schema invalid body still consumes replay floor`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        accept(owner, token, 101) // Body byte 00 is deliberately not a valid system status.
        assertThrows(SecurityException::class.java) { accept(owner, token, 101) }
    }

    @Test
    fun `write reservation phases survive every commit boundary without automatic retry`() {
        for (phase in PumpSession.Phase.entries) for (fault in 0..2) {
            val store = MemoryStore()
            initialized(store)
            // Injected bench contract only: production provisioning always leaves write=null.
            store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
            val owner = PumpSession(store)
            val token = owner.open(pump, key)
            val transaction = owner.begin(token)
            if (phase != PumpSession.Phase.RESERVED) {
                owner.reserve(token, transaction)
                for (prior in PumpSession.Phase.entries.drop(1).take(phase.ordinal - 1)) owner.advance(token, transaction, prior)
            }
            store.fault = fault
            val operation = {
                if (phase == PumpSession.Phase.RESERVED) owner.reserve(token, transaction)
                else owner.advance(token, transaction, phase)
            }
            if (fault == 0) operation() else assertThrows(SecurityException::class.java) { operation() }
            store.fault = 0
            val restored = PumpSession(store)
            val next = restored.open(pump, key)
            val id = restored.begin(next)
            val saved = store.saved.records.single()
            if (saved.reservation != null && saved.reservation.phase != PumpSession.Phase.VERIFIED)
                assertThrows(IllegalStateException::class.java) { restored.reserve(next, id) }
            if (saved.reservation != null) assertEquals(43L, saved.write)
        }
    }

    @Test
    fun `overflow and unvalidated write recovery fail without reservation`() {
        val store = MemoryStore()
        var owner = initialized(store)
        var token = owner.open(pump, key)
        assertThrows(SecurityException::class.java) { owner.reserve(token, owner.begin(token)) }
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = Long.MAX_VALUE, read = Long.MAX_VALUE) })
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(owner, token, Long.MAX_VALUE) }
        assertThrows(IllegalStateException::class.java) { owner.reserve(token, owner.begin(token)) }
        assertNull(store.saved.records.single().reservation)
    }

    @Test
    fun `proven local not sent restores counter and permits a new reservation`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        assertEquals(43, owner.reserve(token, transaction).counter)
        owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)

        owner.markNotSent(token, transaction)

        assertEquals(42, owner.snapshot()!!.write)
        assertNull(owner.snapshot()!!.reservation)
        owner.finish(token, transaction)
        val next = owner.begin(token)
        assertEquals(43, owner.reserve(token, next).counter)
    }

    @Test
    fun `restart can roll back only a durable pre-dispatch reservation`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val intent = PumpSession.WriteIntent("operation", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))
        owner.reserve(token, transaction, intent)
        owner.quiesce()

        val restarted = PumpSession(store)
        val next = restarted.open(pump, key)
        restarted.recoverReservedNotSent(next, "operation", "cd".repeat(32), "journal remained at RESERVED")

        assertEquals(42, restarted.snapshot()!!.write)
        assertNull(restarted.snapshot()!!.reservation)
        assertEquals("cd".repeat(32), restarted.snapshot()!!.writeEvidence.single().evidenceHash)
    }

    @Test
    fun `acknowledged or verified writes cannot be rolled back as not sent`() {
        for (phase in listOf(PumpSession.Phase.ACKED, PumpSession.Phase.VERIFIED)) {
            val store = MemoryStore()
            initialized(store)
            store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
            val owner = PumpSession(store)
            val token = owner.open(pump, key)
            val transaction = owner.begin(token)
            owner.reserve(token, transaction)
            owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
            owner.advance(token, transaction, PumpSession.Phase.ACKED)
            if (phase == PumpSession.Phase.VERIFIED) owner.advance(token, transaction, PumpSession.Phase.VERIFIED)

            assertThrows(IllegalStateException::class.java) { owner.markNotSent(token, transaction) }
            assertEquals(43, owner.snapshot()!!.write)
            assertEquals(phase, owner.snapshot()!!.reservation!!.phase)
        }
    }

    @Test
    fun `semantic reconciliation must explicitly state counter consumption`() {
        for (resolution in PumpSession.WriteResolution.entries) {
            val store = MemoryStore()
            initialized(store)
            store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
            val owner = PumpSession(store)
            val token = owner.open(pump, key)
            val transaction = owner.begin(token)
            val reservation = owner.reserve(
                token,
                transaction,
                PumpSession.WriteIntent("operation-$resolution", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))
            )
            owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
            if (resolution == PumpSession.WriteResolution.ACCEPTED)
                owner.advance(token, transaction, PumpSession.Phase.ACKED)
            owner.finish(token, transaction)

            owner.resolveWrite(token, reservation.id, resolution, "cd".repeat(32), "measured target evidence")

            val saved = owner.snapshot()!!
            assertEquals("cd".repeat(32), saved.writeEvidence.single().evidenceHash)
            assertEquals(resolution, saved.writeEvidence.single().resolution)
            if (resolution == PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED) {
                assertEquals(42, saved.write)
                assertNull(saved.reservation)
            } else {
                assertEquals(43, saved.write)
                assertEquals(PumpSession.Phase.VERIFIED, saved.reservation!!.phase)
            }
        }
    }

    @Test
    fun `write reservation durably identifies characteristic purpose and plaintext hash`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val intent = PumpSession.WriteIntent("operation", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))

        val reserved = owner.reserve(token, transaction, intent)

        assertEquals("operation", reserved.operationId)
        assertEquals("characteristic", reserved.characteristic)
        assertEquals("HISTORY_SELECTOR", reserved.purpose)
        assertEquals("ab".repeat(32), reserved.payloadHash)
        assertEquals(reserved, store.saved.records.single().reservation)
    }
}
