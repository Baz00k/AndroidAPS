package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionEvidenceRetentionTest {

    private val key = ByteArray(32) { it.toByte() }
    private val selector = "669a0c20-0008-969e-e211-fcbecc3b7bc5"
    private val settings = "669a0c20-0008-969e-e211-fcbeb3147bc5"
    private val bolus = "669a0c20-0008-969e-e211-fcbee18b7bc5"
    private val tbr = "669a0c20-0008-969e-e211-fcbee38b7bc5"

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

    private fun therapyEvidence(i: Int) = evidence(i).copy(
        characteristic = if (i % 2 == 0) tbr else bolus,
        purpose = "THERAPY_COMMAND",
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
    fun `completed profile and history selectors share the bound across restart without transport ACK`() {
        val store = store(emptyList())
        var session = PumpSession(store)
        var token = session.open("pump", key)
        repeat(100) { i ->
            if (i == 50) {
                session = PumpSession(store)
                token = session.open("pump", key)
            }
            val tx = session.begin(token)
            val profile = i % 2 == 0
            session.reserve(token, tx, PumpSession.WriteIntent("selector-$i", if (profile) settings else selector,
                if (profile) "SETTINGS_SELECTOR" else "HISTORY_SELECTOR", "ab".repeat(32)))
            session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
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
    fun `every kind of proof is bounded so a long paired pump keeps a small journal`() {
        val kinds = listOf<(Int) -> PumpSession.WriteEvidence>(
            { evidence(it).copy(resolution = null) },
            { evidence(it).copy(resolution = PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED) },
            { therapyEvidence(it).copy(resolution = PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED) },
            { evidence(it).copy(candidate = PumpSession.WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR) },
            { evidence(it).copy(characteristic = "another-characteristic") },
            { therapyEvidence(it).copy(purpose = "BOLUS") },
            ::therapyEvidence,
            ::evidence,
        )
        val history = (0 until 5000).map { kinds[it % kinds.size](it) }
        val store = store(history)
        val session = PumpSession(store)
        session.open("pump", key)
        session.setAvailability(PumpSession.Availability())
        val retained = store.saved.records.single().writeEvidence
        assertTrue(retained.size <= 4 * 32, "Retained ${retained.size} proofs")
        assertEquals(history.last(), retained.last())
        assertEquals(20_000L, store.saved.records.single().write)
    }

    @Test
    fun `an unresolved proof stays until a later consumed counter supersedes it`() {
        val open = evidence(0).copy(resolution = null)
        val rejected = (1..1000).map { evidence(it).copy(resolution = PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED) }
        val store = store(listOf(open) + rejected)
        PumpSession(store).also { it.open("pump", key) }.setAvailability(PumpSession.Availability())
        assertEquals(listOf(open) + rejected.takeLast(32), store.saved.records.single().writeEvidence)

        val accepted = (1001..1032).map(::evidence)
        store.saved = store(listOf(open) + rejected + accepted).saved
        PumpSession(store).also { it.open("pump", key) }.setAvailability(PumpSession.Availability())
        assertEquals(accepted, store.saved.records.single().writeEvidence.filter { it.resolution == PumpSession.WriteResolution.ACCEPTED })
        assertTrue(store.saved.records.single().writeEvidence.none { it.resolution == null })
    }

    @Test
    fun `open unresolved proofs are bounded too`() {
        val open = (0 until 1000).map { evidence(it).copy(resolution = null) }
        val store = store(open)
        PumpSession(store).also { it.open("pump", key) }.setAvailability(PumpSession.Availability())
        assertEquals(open.takeLast(32), store.saved.records.single().writeEvidence)
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
    fun `completed therapy writes have a separate bounded tail without changing counter ownership`() {
        val therapy = (0 until 5000).map(::therapyEvidence)
        val selectors = (5000 until 5100).map(::evidence)
        val store = store(therapy + selectors)
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val retained = therapy.takeLast(32) + selectors.takeLast(32)
        assertEquals(retained, session.snapshot()!!.writeEvidence)
        assertEquals(20_000L, session.snapshot()!!.write)
        assertEquals(100L, session.snapshot()!!.read)
        assertEquals(therapy + selectors, store.saved.records.single().writeEvidence, "Loading must not publish a revision")

        val tx = session.begin(token)
        assertEquals(20_001L, session.reserve(token, tx).counter)
        assertEquals(retained, store.saved.records.single().writeEvidence)
        session.finish(token, tx)
        assertEquals(retained, PumpSession(store).also { it.open("pump", key) }.snapshot()!!.writeEvidence)
    }

    @Test
    fun `repeated observations of one unresolved write stay bounded and still allow the reboot transition`() {
        val store = store((0 until 100).map(::therapyEvidence))
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val tx = session.begin(token)
        session.reserve(token, tx, PumpSession.WriteIntent("pending-bolus", bolus, "THERAPY_COMMAND", "ab".repeat(32)))
        session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
        session.finish(token, tx)
        repeat(100) { i ->
            session.recordUnresolvedWriteEvidence(token, tx, "%064x".format(i), "status did not yet establish delivery")
        }
        val current = store.saved.records.single().writeEvidence.filter { it.reservationId == tx }
        assertEquals((68 until 100).map { "%064x".format(it) }, current.map { it.evidenceHash })
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, store.saved.records.single().reservation!!.phase)

        val restarted = PumpSession(store)
        val reopened = restarted.open("pump", key)
        assertThrows(IllegalStateException::class.java) { restarted.reserve(reopened, restarted.begin(reopened)) }
        restarted.quiesce()
        val again = restarted.open("pump", key)
        val read = restarted.begin(again)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            restarted.accept(again, read, SessionCrypto.Message(byteArrayOf(1), 9, 1), allowObservedReboot = true)
        }
        assertEquals(9, store.saved.records.single().reboot)
    }

    @Test
    fun `an old current therapy proof remains pinned behind a full therapy tail`() {
        val currentProof = therapyEvidence(0)
        val history = (10 until 1010).map(::therapyEvidence)
        val store = store(listOf(currentProof) + history)
        val record = store.saved.records.single()
        store.saved = store.saved.copy(records = listOf(record.copy(
            write = currentProof.counter,
            reservation = PumpSession.Reservation(currentProof.reservationId, currentProof.counter, PumpSession.Phase.VERIFIED,
                currentProof.operationId, currentProof.characteristic, currentProof.purpose, currentProof.payloadHash, currentProof.priorWrite),
        )))
        val session = PumpSession(store)
        session.open("pump", key)
        assertEquals(listOf(currentProof) + history.takeLast(32), session.snapshot()!!.writeEvidence)
        session.setAvailability(PumpSession.Availability())
        assertEquals(listOf(currentProof) + history.takeLast(32), store.saved.records.single().writeEvidence)
    }

    @Test
    fun `therapy tail remains bounded through reservations resolutions and restart`() {
        val store = store(emptyList())
        var session = PumpSession(store)
        var token = session.open("pump", key)
        repeat(100) { i ->
            if (i == 50) {
                session = PumpSession(store)
                token = session.open("pump", key)
            }
            val tx = session.begin(token)
            session.reserve(token, tx, PumpSession.WriteIntent("therapy-$i", if (i % 2 == 0) tbr else bolus,
                "THERAPY_COMMAND", "ab".repeat(32)))
            session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
            session.advance(token, tx, PumpSession.Phase.ACKED)
            session.finish(token, tx)
            session.resolveWrite(token, tx, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "command-specific status proved effect")
            val record = session.snapshot()!!
            assertTrue(record.writeEvidence.size <= 32, "Completed therapy proofs must not grow for the session lifetime")
            assertEquals(20_001L + i, record.write)
            assertEquals(tx, record.writeEvidence.last().reservationId)
            assertEquals(PumpSession.Phase.VERIFIED, record.reservation!!.phase)
            assertEquals(record, store.saved.records.single())
        }
    }

    @Test
    fun `malformed historical therapy evidence is rejected before retention hides it`() {
        val store = store(listOf(therapyEvidence(0).copy(payloadHash = "invalid")) + (1 until 1000).map(::therapyEvidence))
        assertThrows(SecurityException::class.java) { PumpSession(store).open("pump", key) }
    }

    @Test
    fun `an unresolved therapy proof ages out once its acceptance and later proofs supersede it`() {
        val store = store(emptyList())
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val first = session.begin(token)
        session.reserve(token, first, PumpSession.WriteIntent("first-bolus", bolus, "THERAPY_COMMAND", "ab".repeat(32)))
        session.advance(token, first, PumpSession.Phase.POSSIBLY_SENT)
        session.advance(token, first, PumpSession.Phase.ACKED)
        session.finish(token, first)
        session.recordUnresolvedWriteEvidence(token, first, "ef".repeat(32), "status did not yet establish delivery")
        val unresolved = session.snapshot()!!.writeEvidence.single()
        session.resolveWrite(token, first, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "later status proved command effect")
        assertEquals(2, session.snapshot()!!.writeEvidence.size)

        repeat(40) { i ->
            val tx = session.begin(token)
            session.reserve(token, tx, PumpSession.WriteIntent("later-tbr-$i", tbr, "THERAPY_COMMAND", "ab".repeat(32)))
            session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
            session.finish(token, tx)
            session.resolveWrite(token, tx, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "same-link status proved TBR effect")
        }

        val retained = session.snapshot()!!.writeEvidence
        assertEquals(32, retained.size)
        assertTrue(retained.none { it.reservationId == first })
        assertTrue(unresolved !in retained)
        val restarted = PumpSession(store)
        restarted.open("pump", key)
        assertEquals(retained, restarted.snapshot()!!.writeEvidence)
        assertEquals(20_041L, restarted.snapshot()!!.write)
    }

    @Test
    fun `a failed compacted therapy resolution retains the durable acknowledged write on restart`() {
        val store = store((0 until 1000).map(::therapyEvidence))
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val tx = session.begin(token)
        session.reserve(token, tx, PumpSession.WriteIntent("pending-bolus", bolus, "THERAPY_COMMAND", "ab".repeat(32)))
        session.advance(token, tx, PumpSession.Phase.POSSIBLY_SENT)
        session.advance(token, tx, PumpSession.Phase.ACKED)
        session.finish(token, tx)
        val durable = store.saved
        store.fail = true

        assertThrows(SecurityException::class.java) {
            session.resolveWrite(token, tx, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "measured delivery effect")
        }
        assertNull(session.snapshot())
        assertEquals(durable, store.saved)
        store.fail = false
        val restarted = PumpSession(store)
        val reopened = restarted.open("pump", key)
        assertEquals(PumpSession.Phase.ACKED, restarted.snapshot()!!.reservation!!.phase)
        assertThrows(IllegalStateException::class.java) { restarted.reserve(reopened, restarted.begin(reopened)) }
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
