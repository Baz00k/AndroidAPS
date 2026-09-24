package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionEvidenceRetentionTest {

    private val key = ByteArray(32) { it.toByte() }
    private val selector = "669a0c20-0008-969e-e211-fcbecc3b7bc5"
    private val settings = "669a0c20-0008-969e-e211-fcbeb3147bc5"

    private class Store(var saved: PumpSession.State) : PumpSession.Store {
        var fail = false
        override fun load() = saved

        override fun commit(state: PumpSession.State) {
            check(!fail) { "Injected commit failure" }
            saved = state
        }
    }

    private fun evidence(i: Int) = PumpSession.WriteEvidence(
        "history-$i", "reservation-$i", 43L + i, selector, "HISTORY_SELECTOR", "ab".repeat(32), 42L + i,
        PumpSession.WriteCandidate.STANDARD, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "verified selector read-back",
    )

    private fun store(evidence: List<PumpSession.WriteEvidence>) = Store(
        PumpSession.State(
            records = listOf(
                PumpSession.Record(
                    "pump", PumpSession.fingerprint(key), "generation", 8, 100, 20_000,
                    writeEvidence = evidence, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                ),
            ),
            activeGeneration = "generation",
        ),
    )

    @Test
    fun `loading a long running session bounds obsolete successful selector evidence without lowering floors`() {
        val history = (0 until 5000).map(::evidence)
        val store = store(history)
        val session = PumpSession(store)
        val token = session.open("pump", key)
        assertEquals(history.takeLast(32), session.snapshot()!!.writeEvidence)
        assertEquals(20_000L, session.snapshot()!!.write)
        assertEquals(100L, session.snapshot()!!.read)
        // Loading is not a write and never moves the durable commit point.
        assertEquals(history, store.saved.records.single().writeEvidence)
        val tx = session.begin(token)
        val reservation = session.reserve(token, tx)
        assertEquals(20_001L, reservation.counter)
        assertEquals(history.takeLast(32), store.saved.records.single().writeEvidence)
        assertEquals(store.saved.records.single(), session.snapshot())
    }

    @Test
    fun `every completed selector keeps memory and persisted evidence bounded across restart`() {
        val store = store(emptyList())
        var session = PumpSession(store)
        var token = session.open("pump", key)
        repeat(100) { i ->
            if (i == 50) {
                session = PumpSession(store)
                token = session.open("pump", key)
            }
            val tx = session.begin(token)
            session.reserve(token, tx, PumpSession.WriteIntent("history-$i", selector, "HISTORY_SELECTOR", "ab".repeat(32)))
            session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
            session.advance(token, tx, PumpSession.Phase.ACKED)
            session.finish(token, tx)
            session.resolveWrite(token, tx, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "matched selector")
            val record = session.snapshot()!!
            assertTrue(record.writeEvidence.size <= 32, "Evidence grows with completed history reads at row $i")
            assertEquals(record, store.saved.records.single())
            assertEquals(20_001L + i, record.write)
            assertEquals(tx, record.writeEvidence.last().reservationId)
            PumpSession.validate(store.saved)
        }
    }

    @Test
    fun `therapy uncertain rejected and qualified evidence are not discarded with selector audit`() {
        val protected = listOf(
            evidence(0).copy(purpose = "BOLUS"),
            evidence(1).copy(purpose = "TBR"),
            evidence(2).copy(resolution = null),
            evidence(3).copy(resolution = PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED),
            evidence(4).copy(candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR),
            evidence(5).copy(characteristic = "another-characteristic"),
        )
        val history = (10 until 1010).map(::evidence)
        val store = store(protected + history)
        val session = PumpSession(store)
        session.open("pump", key)
        assertEquals(protected + history.takeLast(32), session.snapshot()!!.writeEvidence)
        session.setAvailability(PumpSession.Availability())
        assertEquals(protected + history.takeLast(32), store.saved.records.single().writeEvidence)
    }

    @Test
    fun `settings and history share a bound but an old current proof remains pinned`() {
        val proof = evidence(0)
        val history = (1..1000).map {
            val e = evidence(it)
            if (it % 2 == 0) e.copy(characteristic = settings, purpose = "SETTINGS_SELECTOR") else e
        }
        val store = store(listOf(proof) + history)
        val record = store.saved.records.single()
        store.saved = store.saved.copy(records = listOf(record.copy(
            write = proof.counter,
            reservation = PumpSession.Reservation(proof.reservationId, proof.counter, PumpSession.Phase.VERIFIED,
                proof.operationId, proof.characteristic, proof.purpose, proof.payloadHash, proof.priorWrite),
        )))
        val session = PumpSession(store)
        session.open("pump", key)
        assertEquals(listOf(proof) + history.takeLast(32), session.snapshot()!!.writeEvidence)
        assertEquals(proof.counter, session.snapshot()!!.write)
    }

    @Test
    fun `malformed old evidence must be rejected before compaction can hide it`() {
        val history = (0 until 1000).map(::evidence)
        val store = store(listOf(evidence(-1).copy(payloadHash = "invalid")) + history)
        assertThrows(SecurityException::class.java) { PumpSession(store).open("pump", key) }
    }

    @Test
    fun `a failed compacted commit still poisons the owner and retains durable pending state`() {
        val store = store((0 until 1000).map(::evidence))
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val tx = session.begin(token)
        session.reserve(token, tx, PumpSession.WriteIntent("next-selector", selector, "HISTORY_SELECTOR", "ab".repeat(32)))
        session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
        val durable = store.saved
        store.fail = true
        assertThrows(SecurityException::class.java) { session.advance(token, tx, PumpSession.Phase.ACKED) }
        assertNull(session.snapshot())
        assertEquals(durable, store.saved)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, durable.records.single().reservation!!.phase)
        store.fail = false
        val restarted = PumpSession(store)
        val reopened = restarted.open("pump", key)
        restarted.recoverInterruptedWrite(reopened)
        assertTrue(restarted.reserve(reopened, restarted.begin(reopened)).counter > durable.records.single().write!!)
    }
}
