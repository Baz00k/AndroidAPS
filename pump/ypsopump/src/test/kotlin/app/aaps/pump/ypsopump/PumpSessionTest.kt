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
        store.saved =
            store.saved.copy(
                records =
                    store.saved.records.map {
                        it.established(write = 42).copy(
                            benchHistoryCounts =
                                listOf(
                                    PumpSession.HistoryCountEvidence(
                                        PumpSession.HistoryFamily.ALARM,
                                        reboot = 8,
                                        read = 100,
                                        count = 200,
                                        characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                                        payloadHash = "ab".repeat(32),
                                    ),
                                ),
                        )
                    },
            )
        val owner = PumpSession(store)
        val old = owner.open(pump, key)
        val id = owner.begin(old)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(old, id, SessionCrypto.Message(byteArrayOf(0), 9, 1), true)
        }
        assertEquals(9, store.saved.records.single().reboot)
        assertEquals(1L, store.saved.records.single().read)
        assertNull(store.saved.records.single().write)
        assertEquals(PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH, store.saved.records.single().writeBootstrapState)
        assertTrue(store.saved.records.single().benchHistoryCounts.isEmpty())
        assertThrows(IllegalStateException::class.java) { owner.begin(old) }
        val restored = PumpSession(store)
        val token = restored.open(pump, key)
        assertThrows(SecurityException::class.java) { accept(restored, token, 1, 9) }
        assertThrows(SecurityException::class.java) { accept(restored, token, 101, 8) }
        accept(restored, token, 2, 9)
    }

    @Test
    fun `history counts are epoch bound and zero removes family authority`() {
        val store = MemoryStore()
        val owner = initialized(store)
        val token = owner.open(pump, key)

        owner.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 200,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "ab".repeat(32),
                ),
            ),
        )
        assertEquals(200, owner.snapshot()!!.benchHistoryCounts.single().count)
        owner.recordBenchHistorySelectorState(
            token,
            PumpSession.HistorySelectorState(
                PumpSession.HistoryFamily.ALARM,
                reboot = 8,
                read = 100,
                index = 150,
                characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
                payloadHash = "ee".repeat(32),
            ),
        )
        assertEquals(150, owner.snapshot()!!.benchHistorySelectorStates.single().index)

        owner.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 0,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "cd".repeat(32),
                ),
            ),
        )
        assertTrue(owner.snapshot()!!.benchHistoryCounts.isEmpty())
        assertTrue(owner.snapshot()!!.benchHistorySelectorStates.isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            owner.recordBenchHistoryCounts(
                token,
                listOf(
                    PumpSession.HistoryCountEvidence(
                        PumpSession.HistoryFamily.SYSTEM,
                        reboot = 7,
                        read = 100,
                        count = 600,
                        characteristic = "86a5a431-d442-2c8d-304b-19ee355571fc",
                        payloadHash = "ef".repeat(32),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner.recordBenchHistoryCounts(
                token,
                listOf(
                    PumpSession.HistoryCountEvidence(
                        PumpSession.HistoryFamily.SYSTEM,
                        reboot = 8,
                        read = 100,
                        count = 600,
                        characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                        payloadHash = "ef".repeat(32),
                    ),
                ),
            )
        }

        owner.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 200,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "ab".repeat(32),
                ),
            ),
        )
        owner.provisionReadBaseline(pump, key, 8, 101)
        assertTrue(store.saved.records.single().benchHistoryCounts.isEmpty())
    }

    @Test
    fun `alarm and system selector reservation requires current count minus one`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val alarmIntent =
            PumpSession.WriteIntent(
                "alarm",
                "669a0c20-0008-969e-e211-fcbec93b7bc5",
                "HISTORY_SELECTOR",
                "ab".repeat(32),
            )
        val systemIntent =
            PumpSession.WriteIntent(
                "system",
                "381ddce9-e934-b4ae-e345-eb87283db426",
                "HISTORY_SELECTOR",
                "ef".repeat(32),
            )

        var transaction = owner.begin(token)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(token, transaction, alarmIntent, forwardGap = 0)
        }
        owner.finish(token, transaction)

        transaction = owner.begin(token)
        assertThrows(SecurityException::class.java) {
            owner.reserveBenchCandidate(
                token,
                transaction,
                alarmIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        }
        owner.finish(token, transaction)
        owner.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 200,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "cd".repeat(32),
                ),
            ),
        )

        transaction = owner.begin(token)
        assertThrows(SecurityException::class.java) {
            owner.reserveBenchCandidate(
                token,
                transaction,
                alarmIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        }
        owner.finish(token, transaction)
        owner.recordBenchHistorySelectorState(
            token,
            PumpSession.HistorySelectorState(
                PumpSession.HistoryFamily.ALARM,
                reboot = 8,
                read = 100,
                index = 150,
                characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
                payloadHash = "ee".repeat(32),
            ),
        )

        transaction = owner.begin(token)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(
                token,
                transaction,
                alarmIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 198,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        }
        owner.finish(token, transaction)
        transaction = owner.begin(token)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(
                token,
                transaction,
                alarmIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "wrong-alarm-count",
            )
        }
        owner.finish(token, transaction)
        owner.recordBenchHistorySelectorState(
            token,
            PumpSession.HistorySelectorState(
                PumpSession.HistoryFamily.ALARM,
                reboot = 8,
                read = 100,
                index = 199,
                characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
                payloadHash = "ff".repeat(32),
            ),
        )

        transaction = owner.begin(token)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(
                token,
                transaction,
                alarmIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        }
        owner.finish(token, transaction)
        owner.recordBenchHistorySelectorState(
            token,
            PumpSession.HistorySelectorState(
                PumpSession.HistoryFamily.ALARM,
                reboot = 8,
                read = 100,
                index = 150,
                characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
                payloadHash = "ee".repeat(32),
            ),
        )

        transaction = owner.begin(token)
        val reservation =
            owner.reserveBenchCandidate(
                token,
                transaction,
                alarmIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        assertEquals(43, reservation.counter)
        val binding = checkNotNull(reservation.historyBinding)
        assertEquals(200, binding.count.count)
        assertEquals(150, binding.selectedBefore.index)
        assertEquals(199, binding.writeIndex)
        assertTrue(owner.snapshot()!!.benchHistorySelectorStates.none { it.family == PumpSession.HistoryFamily.ALARM })
        owner.markNotSent(token, transaction)
        owner.finish(token, transaction)

        transaction = owner.begin(token)
        assertThrows(SecurityException::class.java) {
            owner.reserveBenchCandidate(
                token,
                transaction,
                systemIntent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.SYSTEM,
                historyIndex = 599,
                historyCountCharacteristic = "86a5a431-d442-2c8d-304b-19ee355571fc",
            )
        }
        owner.finish(token, transaction)
    }

    @Test
    fun `verified history row permits a fresh pre-row observation for the next row`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        owner.recordBenchHistoryCounts(token, listOf(alarmCount(200, "ab".repeat(32))))
        owner.recordBenchHistorySelectorState(token, alarmState(150, "ee".repeat(32)))
        val intent =
            PumpSession.WriteIntent(
                "alarm",
                "669a0c20-0008-969e-e211-fcbec93b7bc5",
                "HISTORY_SELECTOR",
                "ab".repeat(32),
            )
        var transaction = owner.begin(token)
        val first =
            owner.reserveBenchCandidate(
                token,
                transaction,
                intent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.advance(token, transaction, PumpSession.Phase.ACKED)
        owner.finish(token, transaction)
        owner.resolveWrite(token, first.id, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "changed value accepted")
        assertEquals(PumpSession.Phase.VERIFIED, owner.snapshot()!!.reservation!!.phase)

        owner.recordBenchHistorySelectorState(token, alarmState(199, "ef".repeat(32)))
        assertEquals(199, owner.snapshot()!!.benchHistorySelectorStates.single().index)

        owner.recordBenchHistorySelectorState(token, alarmState(151, "ff".repeat(32)))
        transaction = owner.begin(token)
        val second =
            owner.reserveBenchCandidate(
                token,
                transaction,
                intent,
                forwardGap = 0,
                historyFamily = PumpSession.HistoryFamily.ALARM,
                historyIndex = 199,
                historyCountCharacteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            )
        assertEquals(151, second.historyBinding!!.selectedBefore.index)
        assertTrue(owner.snapshot()!!.benchHistorySelectorStates.isEmpty())
    }

    @Test
    fun `new epoch bootstrap rejects non-event family destinations`() {
        val store = MemoryStore()
        initialized(store)
        var owner = PumpSession(store)
        var token = owner.open(pump, key)
        owner.recordBenchNewEpochBootstrapReference(token, "669a0c20-0008-969e-e211-fcbecc3b7bc5", "ab".repeat(32))
        owner.quiesce()
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        owner = PumpSession(store)
        token = owner.open(pump, key)
        val transaction = owner.begin(token)

        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchNewEpochBootstrapCandidate(
                token,
                transaction,
                PumpSession.WriteIntent(
                    "bootstrap-alarm",
                    "669a0c20-0008-969e-e211-fcbec93b7bc5",
                    "HISTORY_SELECTOR",
                    "bc".repeat(32),
                ),
            )
        }
    }

    @Test
    fun `same key candidate retirement keeps explicitly cleared history count authority`() {
        val store = MemoryStore()
        initialized(store)
        var owner = PumpSession(store)
        var token = owner.open(pump, key)
        owner.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 200,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "ab".repeat(32),
                ),
            ),
        )
        owner.quiesce()

        owner = PumpSession(store)
        owner.install(
            PumpSession.Provisioning(
                pump = pump,
                serial = "10054912",
                sharedKey = key,
                createdAt = null,
                importedAt = 1_000,
                source = mapOf("profile" to "test"),
            ),
        )
        token = owner.open(pump, key)
        owner.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 0,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "cd".repeat(32),
                ),
            ),
        )
        assertTrue(owner.snapshot()!!.benchHistoryCounts.isEmpty())
        owner.quiesce()

        owner = PumpSession(store)
        owner.install(
            PumpSession.Provisioning(
                pump = pump,
                serial = "10054912",
                sharedKey = key,
                createdAt = null,
                importedAt = 2_000,
                source = mapOf("profile" to "test"),
            ),
        )

        val retired = store.saved.records.single { it.generation == store.saved.activeGeneration }
        assertTrue(retired.benchHistoryCounts.isEmpty())
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
            it.copy(
                write = 42,
                writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                reservation = PumpSession.Reservation("pending", 42, PumpSession.Phase.POSSIBLY_SENT, priorWrite = 41),
            )
        })
        val restored = PumpSession(store)
        val next = restored.open(pump, key)
        assertThrows(IllegalStateException::class.java) {
            restored.accept(next, restored.begin(next), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        assertEquals(8, store.saved.records.single().reboot)
    }

    @Test
    fun `observed reboot clears a verified audit reservation and makes the new write floor uncertain`() {
        val store = MemoryStore()
        initialized(store)
        store.saved =
            store.saved.copy(
                records =
                    store.saved.records.map {
                        it.copy(
                            write = 42,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            reservation =
                                PumpSession.Reservation(
                                    "verified",
                                    42,
                                    PumpSession.Phase.VERIFIED,
                                    "operation",
                                    "characteristic",
                                    "HISTORY_SELECTOR",
                                    "ab".repeat(32),
                                    priorWrite = 41,
                                ),
                            benchStrictNextAccepted = true,
                            benchForwardGapAttempted = true,
                        )
                    },
            )
        val owner = PumpSession(store)
        val token = owner.open(pump, key)

        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }

        val adopted = store.saved.records.single()
        assertEquals(9, adopted.reboot)
        assertEquals(1, adopted.read)
        assertNull(adopted.write)
        assertNull(adopted.reservation)
        assertFalse(adopted.benchStrictNextAccepted)
        assertFalse(adopted.benchForwardGapAttempted)
        assertEquals(PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH, adopted.writeBootstrapState)
        assertFalse(adopted.benchNewEpochBootstrapAttempted)
    }

    @Test
    fun `reviewed unresolved old epoch evidence permits exact next reboot without resolving old counter`() {
        val store = MemoryStore()
        initialized(store)
        val reservation =
            PumpSession.Reservation(
                "gap-reservation",
                4,
                PumpSession.Phase.ACKED,
                "gap-operation",
                "characteristic",
                "HISTORY_SELECTOR",
                "ab".repeat(32),
                priorWrite = 2,
                candidate = PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "gap-operation",
                reservationId = reservation.id,
                counter = 4,
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 2,
                candidate = PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR,
                resolution = null,
                evidenceHash = "cd".repeat(32),
                detail = "same-value readback could not classify the one-time gap candidate",
            )
        store.saved =
            store.saved.copy(
                records =
                    store.saved.records.map {
                        it.copy(
                            write = 4,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            reservation = reservation,
                            writeEvidence = listOf(evidence),
                            benchStrictNextAccepted = true,
                            benchForwardGapAttempted = true,
                        )
                    },
            )
        val owner = PumpSession(store)
        val token = owner.open(pump, key)

        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }

        val adopted = store.saved.records.single()
        assertEquals(9, adopted.reboot)
        assertEquals(1L, adopted.read)
        assertNull(adopted.write)
        assertNull(adopted.reservation)
        assertEquals(listOf(evidence), adopted.writeEvidence)
        assertEquals(PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH, adopted.writeBootstrapState)
        assertFalse(adopted.benchNewEpochBootstrapAttempted)
        assertFalse(adopted.benchStrictNextAccepted)
        assertFalse(adopted.benchForwardGapAttempted)
    }

    @Test
    fun `new epoch bootstrap is counter one attempted once and only becomes established on consumed evidence`() {
        val store = MemoryStore()
        initialized(store)
        var owner = PumpSession(store)
        var token = owner.open(pump, key)
        owner.recordBenchNewEpochBootstrapReference(token, "669a0c20-0008-969e-e211-fcbecc3b7bc5", "ab".repeat(32))
        owner.quiesce()
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        owner = PumpSession(store)
        token = owner.open(pump, key)
        val intent = PumpSession.WriteIntent("bootstrap", "669a0c20-0008-969e-e211-fcbecc3b7bc5", "HISTORY_SELECTOR", "bc".repeat(32))
        val transaction = owner.begin(token)

        val reservation = owner.reserveBenchNewEpochBootstrapCandidate(token, transaction, intent)

        assertEquals(1L, reservation.counter)
        assertEquals(0L, reservation.priorWrite)
        assertEquals(PumpSession.WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR, reservation.candidate)
        assertTrue(owner.snapshot()!!.benchNewEpochBootstrapAttempted)
        owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.advance(token, transaction, PumpSession.Phase.ACKED)
        owner.finish(token, transaction)
        owner.resolveWrite(token, reservation.id, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "fresh selector readback matched")

        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, owner.snapshot()!!.writeBootstrapState)
        assertEquals(1L, owner.snapshot()!!.write)
        assertEquals(PumpSession.Phase.VERIFIED, owner.snapshot()!!.reservation!!.phase)
    }

    @Test
    fun `new epoch bootstrap cannot run mid epoch or retry after proven not sent`() {
        val store = MemoryStore()
        var owner = initialized(store)
        var token = owner.open(pump, key)
        val intent = PumpSession.WriteIntent("bootstrap", "669a0c20-0008-969e-e211-fcbecc3b7bc5", "HISTORY_SELECTOR", "bc".repeat(32))
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchNewEpochBootstrapCandidate(token, owner.begin(token), intent)
        }
        owner.quiesce()
        owner = PumpSession(store)
        token = owner.open(pump, key)
        owner.recordBenchNewEpochBootstrapReference(token, "669a0c20-0008-969e-e211-fcbecc3b7bc5", "ab".repeat(32))
        owner.quiesce()
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        owner = PumpSession(store)
        token = owner.open(pump, key)
        val transaction = owner.begin(token)
        owner.reserveBenchNewEpochBootstrapCandidate(token, transaction, intent)
        owner.markNotSent(token, transaction)
        owner.finish(token, transaction)
        assertNull(owner.snapshot()!!.write)
        assertEquals(PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH, owner.snapshot()!!.writeBootstrapState)

        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchNewEpochBootstrapCandidate(token, owner.begin(token), intent.copy(operationId = "retry"))
        }
    }

    @Test
    fun `new epoch bootstrap requires pre-reboot reference and rejects retained selector value`() {
        val store = MemoryStore()
        initialized(store)
        var owner = PumpSession(store)
        var token = owner.open(pump, key)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        owner = PumpSession(store)
        token = owner.open(pump, key)
        val intent = PumpSession.WriteIntent("bootstrap", "669a0c20-0008-969e-e211-fcbecc3b7bc5", "HISTORY_SELECTOR", "ab".repeat(32))
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchNewEpochBootstrapCandidate(token, owner.begin(token), intent)
        }

        val secondStore = MemoryStore()
        initialized(secondStore)
        owner = PumpSession(secondStore)
        token = owner.open(pump, key)
        owner.recordBenchNewEpochBootstrapReference(token, "669a0c20-0008-969e-e211-fcbecc3b7bc5", "ab".repeat(32))
        owner.quiesce()
        owner = PumpSession(secondStore)
        token = owner.open(pump, key)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        owner = PumpSession(secondStore)
        token = owner.open(pump, key)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchNewEpochBootstrapCandidate(token, owner.begin(token), intent)
        }
    }

    @Test
    fun `established bootstrap permits a fresh current epoch reference for the next reboot`() {
        val store = MemoryStore()
        initialized(store)
        var owner = PumpSession(store)
        var token = owner.open(pump, key)
        owner.recordBenchNewEpochBootstrapReference(token, "669a0c20-0008-969e-e211-fcbecc3b7bc5", "ab".repeat(32))
        owner.quiesce()
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(PumpSession.RebootAdoptedException::class.java) {
            owner.accept(token, owner.begin(token), SessionCrypto.Message(byteArrayOf(), 9, 1), true)
        }
        owner = PumpSession(store)
        token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val reservation =
            owner.reserveBenchNewEpochBootstrapCandidate(
                token,
                transaction,
                PumpSession.WriteIntent("bootstrap", "669a0c20-0008-969e-e211-fcbecc3b7bc5", "HISTORY_SELECTOR", "bc".repeat(32)),
            )
        owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.advance(token, transaction, PumpSession.Phase.ACKED)
        owner.finish(token, transaction)
        owner.resolveWrite(token, reservation.id, PumpSession.WriteResolution.ACCEPTED, "cd".repeat(32), "accepted")

        owner.recordBenchNewEpochBootstrapReference(token, "669a0c20-0008-969e-e211-fcbecc3b7bc5", "bc".repeat(32))

        assertEquals(9, owner.snapshot()!!.benchNewEpochBootstrapReference!!.reboot)
        assertEquals("bc".repeat(32), owner.snapshot()!!.benchNewEpochBootstrapReference!!.payloadHash)
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
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = Long.MAX_VALUE).copy(read = Long.MAX_VALUE) })
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
    fun `bounded forward gap retains exact prior floor when proven not consumed`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(benchStrictNextAccepted = true) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val intent = PumpSession.WriteIntent("gap", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))

        val reservation = owner.reserveBenchCandidate(token, transaction, intent, forwardGap = 1)

        assertEquals(44, reservation.counter)
        assertEquals(42, reservation.priorWrite)
        owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.finish(token, transaction)
        owner.resolveWrite(
            token,
            reservation.id,
            PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
            "cd".repeat(32),
            "bounded +2 candidate was rejected without counter consumption",
        )
        assertEquals(42, owner.snapshot()!!.write)
        assertNull(owner.snapshot()!!.reservation)
        assertTrue(owner.snapshot()!!.benchForwardGapAttempted)
    }

    @Test
    fun `bench candidate rejects offsets beyond the single forward gap`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val intent = PumpSession.WriteIntent("gap", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))

        assertThrows(IllegalArgumentException::class.java) {
            owner.reserveBenchCandidate(token, transaction, intent, forwardGap = 2)
        }
        assertEquals(42, owner.snapshot()!!.write)
        assertNull(owner.snapshot()!!.reservation)
    }

    @Test
    fun `forward gap requires accepted strict next and is attempted once per epoch across restart`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
        var owner = PumpSession(store)
        var token = owner.open(pump, key)
        val intent = PumpSession.WriteIntent("gap", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))

        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(token, owner.begin(token), intent, forwardGap = 1)
        }
        owner.quiesce()
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(benchStrictNextAccepted = true) })
        owner = PumpSession(store)
        token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val reservation = owner.reserveBenchCandidate(token, transaction, intent, forwardGap = 1)
        owner.markNotSent(token, transaction)
        owner.finish(token, transaction)
        assertEquals(42, owner.snapshot()!!.write)
        assertTrue(owner.snapshot()!!.benchForwardGapAttempted)

        owner.quiesce()
        owner = PumpSession(store)
        token = owner.open(pump, key)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(token, owner.begin(token), intent.copy(operationId = "gap-again"), forwardGap = 1)
        }
        assertEquals(reservation.counter, 44)
        assertEquals(42, owner.snapshot()!!.write)
    }

    @Test
    fun `accepted standard reservation cannot unlock the bench forward gap`() {
        val store = MemoryStore()
        initialized(store)
        store.saved = store.saved.copy(records = store.saved.records.map { it.established(write = 42) })
        val owner = PumpSession(store)
        val token = owner.open(pump, key)
        val transaction = owner.begin(token)
        val intent = PumpSession.WriteIntent("standard", "characteristic", "HISTORY_SELECTOR", "ab".repeat(32))
        val standard = owner.reserve(token, transaction, intent)
        owner.advance(token, transaction, PumpSession.Phase.POSSIBLY_SENT)
        owner.finish(token, transaction)
        owner.resolveWrite(
            token,
            standard.id,
            PumpSession.WriteResolution.ACCEPTED,
            "cd".repeat(32),
            "accepted non-bench reservation",
        )

        assertFalse(owner.snapshot()!!.benchStrictNextAccepted)
        assertThrows(IllegalStateException::class.java) {
            owner.reserveBenchCandidate(
                token,
                owner.begin(token),
                intent.copy(operationId = "gap"),
                forwardGap = 1,
            )
        }
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
    private fun PumpSession.Record.established(write: Long): PumpSession.Record =
        copy(write = write, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)

    private fun alarmCount(count: Int, payloadHash: String) =
        PumpSession.HistoryCountEvidence(
            PumpSession.HistoryFamily.ALARM,
            reboot = 8,
            read = 100,
            count = count,
            characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
            payloadHash = payloadHash,
        )

    private fun alarmState(index: Int, payloadHash: String) =
        PumpSession.HistorySelectorState(
            PumpSession.HistoryFamily.ALARM,
            reboot = 8,
            read = 100,
            index = index,
            characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
            payloadHash = payloadHash,
        )
}
