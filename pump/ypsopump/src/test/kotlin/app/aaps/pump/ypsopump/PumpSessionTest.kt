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
}
