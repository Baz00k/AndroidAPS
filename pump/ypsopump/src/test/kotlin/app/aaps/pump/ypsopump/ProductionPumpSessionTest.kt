package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProductionPumpSessionTest {
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
        override fun replaceUnavailable(state: PumpSession.State) {
            check(fault == 0) { "Recovery store unavailable" }
            saved = state
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
    fun `observed next reboot commits independent floor and invalidates old connection`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val old = owner.open(pump, key)

        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(old, owner.begin(old), SessionCrypto.Message(byteArrayOf(0), 9, 1), true)
        }

        assertEquals(9, store.saved.records.single().reboot)
        assertEquals(1L, store.saved.records.single().read)
        assertNull(store.saved.records.single().write)
        assertEquals(PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH, store.saved.records.single().writeBootstrapState)
        assertThrows(IllegalStateException::class.java) { owner.begin(old) }
        val restarted = PumpSession(store)
        val next = restarted.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(restarted, next, 1, 9) }
        accept(restarted, next, 2, 9)
    }

    @Test
    fun `observed reboot commit failures never publish or partially update`() {
        for (fault in 1..2) {
            val store = MemoryStore()
            val owner = initialized(store)
            val token = owner.open(pump, key)
            val transaction = owner.begin(token)
            store.fault = fault

            assertThrows(SecurityException::class.java) {
                owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(), 9, 7), true)
            }

            assertNull(owner.snapshot())
            assertEquals(if (fault == 1) 8 else 9, store.saved.records.single().reboot)
            assertEquals(if (fault == 1) 100L else 7L, store.saved.records.single().read)
        }
    }

    @Test
    fun `observed reboot rejects jumps zero counter and outstanding reservations`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        for ((reboot, read) in listOf(7 to 101L, 10 to 1L, 9 to 0L)) {
            assertThrows(SecurityException::class.java) {
                owner.accept(token, transaction, SessionCrypto.Message(byteArrayOf(), reboot, read), true)
            }
        }
        owner.finish(token, transaction)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.established(write = 42).copy(
                    reservation = PumpSession.Reservation("pending", 42, PumpSession.Phase.POSSIBLY_SENT, priorWrite = 41),
                )
            },
        )
        val restored = PumpSession(store)
        val next = restored.open(pump, key)
        assertThrows(IllegalStateException::class.java) {
            restored.accept(next, restored.begin(next), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        assertEquals(8, store.saved.records.single().reboot)
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
    fun `explicit journal loss recovery installs only a selector recovery lower bound`() {
        val store = MemoryStore()
        initialized(store)
        store.fault = 3
        val unavailable = PumpSession(store)
        store.fault = 0

        unavailable.recoverLostJournalLowerBound(
            PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
            lowerBound = 9_035,
            recoveryReboot = 21,
            evidenceHash = "ab".repeat(32),
        )

        val recovered = PumpSession(store).committedRecord()!!
        assertNull(recovered.reboot)
        assertNull(recovered.read)
        assertEquals(9_035, recovered.write)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, recovered.writeBootstrapState)
        assertEquals("ab".repeat(32), recovered.source["journal_loss_recovery_evidence_sha256"])
        val restarted = PumpSession(store)
        val token = restarted.open(pump, key)
        val transaction = restarted.begin(token)
        restarted.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 21, 1))
        restarted.finish(token, transaction)
        restarted.markVerified("10000001", 3)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in restarted.availability().causes)
    }

    @Test
    fun `read only journal recovery authenticates reads and reconciles writes from zero`() {
        val store = MemoryStore()
        initialized(store)
        store.fault = 3
        val unavailable = PumpSession(store)
        store.fault = 0

        unavailable.recoverLostJournalReadOnly(
            PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
            documentHash = "cd".repeat(32),
        )

        val restarted = PumpSession(store)
        val token = restarted.open(pump, key)
        accept(restarted, token, 1, reboot = 21)
        restarted.markVerified("10000001", 3)
        val record = restarted.committedRecord()!!
        assertEquals(21, record.reboot)
        assertEquals(1, record.read)
        assertNull(record.write)
        assertEquals(PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH, record.writeBootstrapState)
        assertEquals("cd".repeat(32), record.source["journal_loss_read_only_document_sha256"])

        val transaction = restarted.begin(token)
        val reservation = restarted.reserve(
            token,
            transaction,
            PumpSession.WriteIntent("reconciled", "characteristic", "THERAPY_COMMAND", "ab".repeat(32)),
        )
        assertEquals(1L, reservation.counter)
        assertEquals(0L, reservation.priorWrite)
        assertEquals(PumpSession.WriteCandidate.STANDARD, reservation.candidate)
        restarted.finish(token, transaction)
    }

    @Test
    fun `matching identity only record can enter selector lower bound recovery without adopting ownership`() {
        val store = MemoryStore()
        initialized(store)
        store.fault = 3
        val unavailable = PumpSession(store)
        store.fault = 0
        unavailable.recoverLostJournalReadOnly(
            PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
            documentHash = "cd".repeat(32),
        )
        val readable = PumpSession(store)
        val token = readable.open(pump, key)
        accept(readable, token, 73051, reboot = 21)
        readable.markVerified("10000001", 3)
        readable.quiesce()
        val imported = readable.committedRecord()!!.copy(
            generation = "handoff-generation",
            read = 2_998,
            write = 4_280,
            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
            lowerBoundRecoveryReboot = null,
        )

        readable.recoverIdentityOnlyLowerBound(
            imported,
            importedAt = 4,
            source = mapOf("ownership_handoff_sha256" to "ab".repeat(32)),
        )

        val recovered = readable.committedRecord()!!
        assertEquals(21, recovered.reboot)
        assertEquals(73051, recovered.read)
        assertEquals(4_280, recovered.write)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, recovered.writeBootstrapState)
        assertEquals(21, recovered.lowerBoundRecoveryReboot)
        assertEquals("ab".repeat(32), recovered.source["ownership_handoff_sha256"])
        assertNull(recovered.reservation)
        assertTrue(recovered.writeEvidence.isEmpty())
    }

    @Test
    fun `identity only lower bound recovery rejects another pump key epoch or exact ownership state`() {
        fun recovered(): Pair<PumpSession, PumpSession.Record> {
            val store = MemoryStore()
            initialized(store)
            store.fault = 3
            val unavailable = PumpSession(store)
            store.fault = 0
            unavailable.recoverLostJournalReadOnly(
                PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
                documentHash = "cd".repeat(32),
            )
            val owner = PumpSession(store)
            val token = owner.open(pump, key)
            accept(owner, token, 100, reboot = 21)
            owner.markVerified("10000001", 3)
            owner.quiesce()
            return owner to owner.committedRecord()!!.copy(
                generation = "handoff-generation",
                read = 90,
                write = 4_280,
                writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
            )
        }

        recovered().let { (owner, imported) ->
            assertThrows(IllegalStateException::class.java) {
                owner.recoverIdentityOnlyLowerBound(imported.copy(pump = "EC:2A:F0:00:00:02"), 4, emptyMap())
            }
        }
        recovered().let { (owner, imported) ->
            assertThrows(IllegalStateException::class.java) {
                owner.recoverIdentityOnlyLowerBound(imported.copy(keyId = "00".repeat(32)), 4, emptyMap())
            }
        }
        recovered().let { (owner, imported) ->
            assertThrows(IllegalStateException::class.java) {
                owner.recoverIdentityOnlyLowerBound(imported.copy(reboot = 22), 4, emptyMap())
            }
        }
        recovered().let { (owner, imported) ->
            owner.recoverIdentityOnlyLowerBound(imported, 4, emptyMap())
            assertThrows(IllegalStateException::class.java) {
                owner.recoverIdentityOnlyLowerBound(imported, 5, emptyMap())
            }
        }
    }

    @Test
    fun `legacy identity only record without provenance marker can enter selector recovery`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(
                    reboot = 21,
                    read = 73_051,
                    write = null,
                    serial = "10000001",
                    verifiedAt = 3,
                    verifiedSerial = "10000001",
                    source = emptyMap(),
                    writeBootstrapState = PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH,
                )
            },
        )
        val owner = PumpSession(store)
        val imported = owner.committedRecord()!!.copy(
            generation = "handoff-generation",
            read = 2_998,
            write = 4_280,
            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
        )

        owner.recoverIdentityOnlyLowerBound(imported, 4, emptyMap())

        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, owner.committedRecord()!!.writeBootstrapState)
        assertEquals(4_280, owner.committedRecord()!!.write)
        assertEquals(73_051, owner.committedRecord()!!.read)
    }

    @Test
    fun `healthy journal cannot enter read only disaster recovery`() {
        val store = MemoryStore()
        val owner = initialized(store)

        assertThrows(IllegalStateException::class.java) {
            owner.recoverLostJournalReadOnly(
                PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
                documentHash = "cd".repeat(32),
            )
        }
    }

    @Test
    fun `healthy journal cannot enter lower bound disaster recovery`() {
        val store = MemoryStore()
        val owner = initialized(store)

        assertThrows(IllegalStateException::class.java) {
            owner.recoverLostJournalLowerBound(
                PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
                lowerBound = 9_035,
                recoveryReboot = 21,
                evidenceHash = "ab".repeat(32),
            )
        }
    }

    @Test
    fun `journal recovery rejects an authenticated read from another reboot epoch`() {
        val store = MemoryStore()
        initialized(store)
        store.fault = 3
        val unavailable = PumpSession(store)
        store.fault = 0
        unavailable.recoverLostJournalLowerBound(
            PumpSession.Provisioning(pump, "10000001", key, 1, 2, emptyMap()),
            lowerBound = 9_035,
            recoveryReboot = 21,
            evidenceHash = "ab".repeat(32),
        )
        val restarted = PumpSession(store)
        val token = restarted.open(pump, key)
        val transaction = restarted.begin(token)

        assertThrows(IllegalArgumentException::class.java) {
            restarted.accept(token, transaction, SessionCrypto.Message(byteArrayOf(1), 22, 1))
        }

        assertNull(restarted.committedRecord()!!.reboot)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, restarted.committedRecord()!!.writeBootstrapState)
        assertEquals(21, restarted.committedRecord()!!.lowerBoundRecoveryReboot)
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
            store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
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
            // ACKED is only an in-memory refinement of the durable POSSIBLY_SENT boundary.
            if (fault == 0 || phase == PumpSession.Phase.ACKED) operation()
            else assertThrows(SecurityException::class.java) { operation() }
            store.fault = 0
            val restored = PumpSession(store)
            val next = restored.open(pump, key)
            val id = restored.begin(next)
            val saved = store.saved.records.single()
            if (phase == PumpSession.Phase.ACKED) assertEquals(PumpSession.Phase.POSSIBLY_SENT, saved.reservation?.phase)
            if (saved.reservation != null && saved.reservation.phase != PumpSession.Phase.VERIFIED)
                assertThrows(IllegalStateException::class.java) { restored.reserve(next, id) }
            if (saved.reservation != null) assertEquals(43L, saved.write)
        }
    }

    @Test
    fun `unknown floor reconciles from zero while overflow still fails closed`() {
        val store = MemoryStore()
        var owner = initialized(store)
        var token = owner.open(pump, key)
        val firstTransaction = owner.begin(token)
        val first = owner.reserve(token, firstTransaction)
        assertEquals(1L, first.counter)
        assertEquals(0L, first.priorWrite)
        owner.finish(token, firstTransaction)

        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.established(write = Long.MAX_VALUE).copy(read = Long.MAX_VALUE, reservation = null)
            },
        )
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(owner, token, Long.MAX_VALUE) }
        assertThrows(IllegalStateException::class.java) { owner.reserve(token, owner.begin(token)) }
        assertNull(store.saved.records.single().reservation)
    }

    @Test
    fun `interrupted write recovery retains high water and unknown effect across commit crashes`() {
        for (phase in listOf(PumpSession.Phase.RESERVED, PumpSession.Phase.POSSIBLY_SENT, PumpSession.Phase.ACKED)) {
            for (fault in 0..2) {
                val store = MemoryStore()
                initialized(store)
                store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 4280) })
                val owner = PumpSession(store)
                val token = owner.open(pump, key)
                val transaction = owner.begin(token)
                owner.reserve(token, transaction, PumpSession.WriteIntent("interrupted", "characteristic", "THERAPY_COMMAND", "ab".repeat(32)))
                if (phase != PumpSession.Phase.RESERVED) owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
                if (phase == PumpSession.Phase.ACKED) owner.advance(token, transaction, PumpSession.Phase.ACKED)
                assertThrows(IllegalStateException::class.java) { owner.recoverInterruptedWrite(token) }
                owner.finish(token, transaction)
                store.fault = fault
                if (fault == 0) owner.recoverInterruptedWrite(token)
                else assertThrows(SecurityException::class.java) { owner.recoverInterruptedWrite(token) }
                store.fault = 0
                val restarted = PumpSession(store)
                val next = restarted.open(pump, key)
                restarted.recoverInterruptedWrite(next)
                val retained = restarted.snapshot()!!
                assertEquals(4281, retained.write)
                assertNull(retained.reservation)
                assertEquals("interrupted", retained.writeEvidence.single().operationId)
                assertNull(retained.writeEvidence.single().resolution)
                assertEquals(4282, restarted.reserve(next, restarted.begin(next)).counter)
            }
        }
    }

    @Test
    fun `confirmed counter errors exponentially increase candidates and acceptance resets recovery`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 4280) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val candidates = mutableListOf<Long>()
        repeat(4) { attempt ->
            val transaction = owner.begin(token)
            val reservation = owner.reserve(token, transaction, PumpSession.WriteIntent("counter-$attempt", "characteristic", "SETTINGS_SELECTOR", "ab".repeat(32)))
            candidates += reservation.counter
            owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
            owner.finish(token, transaction)
            owner.rejectCounterTooLow(token, reservation.id, "cd".repeat(32), "pump APPERR_COUNTER_ERROR 139")
        }
        assertEquals(listOf(4281L, 4282L, 4284L, 4288L), candidates)
        assertEquals(4, owner.snapshot()!!.counterRecoveryExponent)
        val acceptedTransaction = owner.begin(token)
        val accepted = owner.reserve(token, acceptedTransaction, PumpSession.WriteIntent("accepted", "characteristic", "SETTINGS_SELECTOR", "ef".repeat(32)))
        assertEquals(4296L, accepted.counter)
        owner.advance(token, acceptedTransaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.advance(token, acceptedTransaction, PumpSession.Phase.ACKED)
        owner.finish(token, acceptedTransaction)
        owner.resolveWrite(token, accepted.id, PumpSession.WriteResolution.ACCEPTED, "01".repeat(32), "semantic read-back accepted")
        assertEquals(0, owner.snapshot()!!.counterRecoveryExponent)
    }

    @Test
    fun `unknown floor starts at zero and establishes ownership on acceptance`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        val candidates = mutableListOf<Long>()
        repeat(4) { attempt ->
            val transaction = owner.begin(token)
            val reservation = owner.reserve(
                token,
                transaction,
                PumpSession.WriteIntent("unknown-$attempt", "characteristic", "SETTINGS_SELECTOR", "ab".repeat(32)),
            )
            candidates += reservation.counter
            owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
            owner.finish(token, transaction)
            owner.rejectCounterTooLow(token, reservation.id, "cd".repeat(32), "pump APPERR_COUNTER_ERROR 139")
        }
        assertEquals(listOf(1L, 2L, 4L, 8L), candidates)
        assertEquals(4, owner.snapshot()!!.counterRecoveryExponent)
        assertEquals(PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH, owner.snapshot()!!.writeBootstrapState)

        val acceptedTransaction = owner.begin(token)
        val accepted = owner.reserve(
            token,
            acceptedTransaction,
            PumpSession.WriteIntent("accepted", "characteristic", "SETTINGS_SELECTOR", "ef".repeat(32)),
        )
        assertEquals(16L, accepted.counter)
        assertEquals(8L, accepted.priorWrite)
        owner.advance(token, acceptedTransaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.advance(token, acceptedTransaction, PumpSession.Phase.ACKED)
        owner.finish(token, acceptedTransaction)
        owner.resolveWrite(token, accepted.id, PumpSession.WriteResolution.ACCEPTED, "01".repeat(32), "semantic read-back accepted")

        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, owner.snapshot()!!.writeBootstrapState)
        assertEquals(16L, owner.snapshot()!!.write)
        assertEquals(0, owner.snapshot()!!.counterRecoveryExponent)
    }

    @Test
    fun `counter rejections persist and keep advancing at the exponent cap`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)
        val candidates = mutableListOf<Long>()
        repeat(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT + 2) { attempt ->
            val transaction = owner.begin(token)
            val reservation = owner.reserve(
                token,
                transaction,
                PumpSession.WriteIntent("capped-$attempt", "characteristic", "SETTINGS_SELECTOR", "ab".repeat(32)),
            )
            candidates += reservation.counter
            owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
            owner.finish(token, transaction)
            owner.rejectCounterTooLow(token, reservation.id, "cd".repeat(32), "pump APPERR_COUNTER_ERROR 139")
        }
        val record = owner.snapshot()!!
        assertEquals(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT, record.counterRecoveryExponent)
        assertEquals(candidates.sorted(), candidates)
        assertEquals(candidates.distinct().size, candidates.size)
        assertEquals(
            PumpSession.counterRecoveryIncrement(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT),
            candidates[21] - candidates[20],
        )
        assertEquals(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT + 2, record.writeEvidence.size)
    }

    @Test
    fun `proven local not sent restores counter and permits a new reservation`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
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
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
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
            store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
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
            store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
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
            assertEquals("characteristic", saved.writeEvidence.single().characteristic)
            assertEquals("HISTORY_SELECTOR", saved.writeEvidence.single().purpose)
            assertEquals("ab".repeat(32), saved.writeEvidence.single().payloadHash)
            assertEquals(42, saved.writeEvidence.single().priorWrite)
            assertEquals(PumpSession.WriteCandidate.STANDARD, saved.writeEvidence.single().candidate)
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
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
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

    @Test
    fun `completed legacy alarm recovery retires without blocking strict next reservation`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline(pump, key, 21, 2_596)
        val legacyReservation = PumpSession.Reservation(
            id = "historical-alarm-cursor",
            counter = 33,
            phase = PumpSession.Phase.VERIFIED,
            operationId = "alarm-cursor-1789567816",
            characteristic = "669a0c20-0008-969e-e211-fcbec93b7bc5",
            purpose = "HISTORY_SELECTOR",
            payloadHash = "ab".repeat(32),
            priorWrite = 32,
            candidate = PumpSession.WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR,
        )
        val legacyEvidence = PumpSession.WriteEvidence(
            operationId = checkNotNull(legacyReservation.operationId),
            reservationId = legacyReservation.id,
            counter = legacyReservation.counter,
            characteristic = checkNotNull(legacyReservation.characteristic),
            purpose = checkNotNull(legacyReservation.purpose),
            payloadHash = checkNotNull(legacyReservation.payloadHash),
            priorWrite = checkNotNull(legacyReservation.priorWrite),
            candidate = legacyReservation.candidate,
            resolution = PumpSession.WriteResolution.ACCEPTED,
            evidenceHash = "cd".repeat(32),
            detail = "completed historical alarm cursor recovery",
        )
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.established(write = 33).copy(reservation = legacyReservation, writeEvidence = listOf(legacyEvidence))
            },
        )
        val owner = PumpSession(store)
        val token = owner.open(pump, key)

        val reserved = owner.reserve(token, owner.begin(token))

        assertEquals(34, reserved.counter)
        assertEquals(legacyReservation, owner.snapshot()!!.retiredLegacyBenchAlarmCursorRecovery)
        assertTrue(owner.snapshot()!!.writeEvidence.contains(legacyEvidence))
    }

    private fun PumpSession.Record.established(write: Long): PumpSession.Record =
        copy(write = write, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
}
