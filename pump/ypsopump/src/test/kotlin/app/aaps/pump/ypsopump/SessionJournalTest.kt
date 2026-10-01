package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionJournal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Executes journal serialization/order, legacy HMAC and sealed-body behavior; storage is not Android Keystore. */
class SessionJournalTest {
    private class Storage : SessionJournal.Storage {
        var file: String? = null
        val keys = mutableMapOf<String, ByteArray>()
        var fault = ""
        var failSeal = false
        var terminateAt = ""
        private fun boundary(name: String) {
            if (terminateAt == name) throw ThreadDeath()
            check(fault != name) { "Injected crash: $name" }
        }
        override fun read() = file
        override fun anchors() = keys.keys.toList()
        override fun create(alias: String) {
            boundary("before-create")
            keys[alias] = java.security.MessageDigest.getInstance("SHA-256").digest(alias.toByteArray())
            boundary("after-create")
        }
        override fun delete(alias: String) {
            boundary("before-delete")
            keys.remove(alias)
            boundary("after-delete")
        }
        override fun authenticateLegacy(alias: String, body: String): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(checkNotNull(keys[alias]), "HmacSHA256"))
            doFinal(body.toByteArray())
        }
        override fun seal(alias: String, body: String): String {
            check(!failSeal) { "Injected seal failure" }
            return body + "." + authenticateLegacy(alias, body).joinToString("") { "%02x".format(it) }
        }
        override fun open(alias: String, sealed: String): String {
            val body = sealed.substringBeforeLast('.')
            val mac = sealed.substringAfterLast('.').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            check(java.security.MessageDigest.isEqual(authenticateLegacy(alias, body), mac))
            return body
        }
        override fun writeAndSync(value: String) {
            boundary("before-truncate")
            boundary("after-truncate")
            boundary("partial-write")
            boundary("before-sync")
            boundary("after-sync")
            file = value
        }
    }

    private val old = PumpSession.State(listOf(PumpSession.Record("pump", "00".repeat(32), "generation", 8, 100, null)))
    private val next = old.copy(records = old.records.map { it.copy(read = 101) })

    @Test
    fun `process termination before publication preserves exact committed journal with an extra key`() {
        for (boundary in listOf("after-create", "before-truncate", "after-truncate", "partial-write", "before-sync", "after-sync")) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            val original = storage.file
            storage.terminateAt = boundary
            assertThrows(ThreadDeath::class.java) { journal.commit(next) }
            storage.terminateAt = ""
            assertEquals(2, storage.anchors().size)
            assertEquals(old, SessionJournal(storage).load())
            assertEquals(original, storage.file)
            assertEquals(2, storage.anchors().size)
            journal.commit(next)
            assertEquals(next, journal.load())
            assertEquals(1, storage.anchors().size)
        }
    }

    @Test
    fun `process termination after retiring prior anchor exposes exact next revision`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        storage.terminateAt = "after-delete"

        assertThrows(ThreadDeath::class.java) { journal.commit(next) }

        storage.terminateAt = ""
        assertEquals(next, SessionJournal(storage).load())
        assertEquals(1, storage.anchors().size)
    }

    @Test
    fun `unavailable journal replacement retires stale anchors before publishing recovery`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val publishedAlias = org.json.JSONObject(checkNotNull(storage.file)).getString("anchor")
        storage.keys.remove(publishedAlias)
        storage.keys["ypso.session.revision.stale-restored-backup"] = byteArrayOf(1)
        assertThrows(Exception::class.java) { journal.load() }

        journal.replaceUnavailable(next)

        assertEquals(next, SessionJournal(storage).load())
        assertEquals(1, storage.anchors().size)
        assertNotEquals("ypso.session.revision.stale-restored-backup", storage.anchors().single())
    }

    @Test
    fun `healthy journal cannot be replaced through disaster recovery`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)

        assertThrows(IllegalStateException::class.java) { journal.replaceUnavailable(next) }

        assertEquals(old, journal.load())
    }

    @Test
    fun `replacement interruption stays unavailable and can be retried without rollback`() {
        val boundaries = listOf(
            "after-create",
            "before-delete",
            "after-delete",
            "before-truncate",
            "after-truncate",
            "partial-write",
            "before-sync",
            "after-sync",
        )
        for (boundary in boundaries) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            val publishedAlias = org.json.JSONObject(checkNotNull(storage.file)).getString("anchor")
            storage.keys.remove(publishedAlias)
            storage.keys["ypso.session.revision.stale-restored-backup"] = byteArrayOf(1)
            storage.terminateAt = boundary

            assertThrows(ThreadDeath::class.java, { journal.replaceUnavailable(next) }, boundary)

            storage.terminateAt = ""
            assertThrows(Exception::class.java, { SessionJournal(storage).load() }, boundary)
            journal.replaceUnavailable(next)
            assertEquals(next, SessionJournal(storage).load(), boundary)
            assertEquals(1, storage.anchors().size, boundary)
        }
    }

    @Test
    fun `process termination before retiring prior anchor keeps prior revision authoritative`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        storage.terminateAt = "before-delete"

        assertThrows(ThreadDeath::class.java) { journal.commit(next) }

        storage.terminateAt = ""
        assertEquals(old, SessionJournal(storage).load())
        assertEquals(2, storage.anchors().size)
        journal.commit(next)
        assertEquals(next, SessionJournal(storage).load())
        assertEquals(1, storage.anchors().size)
    }

    @Test
    fun `older orphan anchors are retired before authority moves to next revision`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        storage.keys["orphan"] = byteArrayOf(1)
        storage.fault = "before-delete"

        assertThrows(IllegalStateException::class.java) { journal.commit(next) }

        storage.fault = ""
        assertEquals(old, SessionJournal(storage).load())
    }

    @Test
    fun `roundtrip and restoring stale file rejects deleted anchor`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val backup = storage.file
        journal.commit(next)
        assertEquals(next, journal.load())
        storage.file = backup
        assertThrows(IllegalStateException::class.java) { journal.load() }
    }

    @Test
    fun `version seventeen roundtrip preserves lower bound recovery epoch`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val recovering = PumpSession.State(
            records = listOf(
                PumpSession.Record(
                    pump = "pump",
                    keyId = "00".repeat(32),
                    generation = "generation",
                    reboot = null,
                    read = null,
                    write = 9_035,
                    writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
                    lowerBoundRecoveryReboot = 21,
                ),
            ),
            activeGeneration = "generation",
            availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
        )

        journal.commit(recovering)

        assertEquals(recovering, journal.load())
        val envelope = org.json.JSONObject(checkNotNull(storage.file))
        val body = storage.open(envelope.getString("anchor"), envelope.getString("sealed"))
        assertEquals(17, org.json.JSONObject(body).getInt("version"))
    }

    @Test
    fun `version nine roundtrip preserves exact durable write evidence`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val evidence = PumpSession.WriteEvidence(
            operationId = "selector-9",
            reservationId = "reservation-43",
            counter = 43,
            characteristic = "characteristic",
            purpose = "HISTORY_SELECTOR",
            payloadHash = "ab".repeat(32),
            priorWrite = 42,
            candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
            resolution = PumpSession.WriteResolution.ACCEPTED,
            evidenceHash = "cd".repeat(32),
            detail = "reviewed target trace and readback matched selector 9"
        )
        val unresolvedEvidence = evidence.copy(
            resolution = null,
            evidenceHash = "ef".repeat(32),
            detail = "bounded probe could not classify counter consumption"
        )
        val state = old.copy(records = old.records.map { it.copy(writeEvidence = listOf(evidence, unresolvedEvidence)) })

        journal.commit(state)

        assertEquals(state, journal.load())
        val sealed = org.json.JSONObject(checkNotNull(storage.file)).getString("sealed")
        val body = storage.open(storage.anchors().single(), sealed)
        assertEquals(17, org.json.JSONObject(body).getInt("version"))
    }

    @Test
    fun `version fifteen preserves nested unresolved selector bindings`() {
        val first = PumpSession.UnresolvedWriteBinding(
            reboot = 21, reservationId = "first", phase = PumpSession.Phase.POSSIBLY_SENT,
            operationId = "setting-first", counter = 34,
            characteristic = "669a0c20-0008-969e-e211-fcbeb3147bc5", purpose = "SETTINGS_SELECTOR",
            payloadHash = "ab".repeat(32), priorWrite = 33,
            candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR, evidenceHash = "cd".repeat(32),
        )
        val second = first.copy(reservationId = "second", operationId = "setting-second", counter = 35,
            priorWrite = 34, candidate = PumpSession.WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
            unresolvedPredecessor = first)
        val recovery = PumpSession.Reservation(
            id = "recovery", counter = 36, phase = PumpSession.Phase.POSSIBLY_SENT,
            operationId = "recover", characteristic = "669a0c20-0008-969e-e211-fcbecc3b7bc5",
            purpose = "HISTORY_SELECTOR", payloadHash = "ef".repeat(32), priorWrite = 35,
            candidate = PumpSession.WriteCandidate.BENCH_SETTINGS_COUNTER_RECOVERY_SELECTOR,
            unresolvedPredecessor = second,
        )
        fun evidence(binding: PumpSession.UnresolvedWriteBinding) = PumpSession.WriteEvidence(
            operationId = binding.operationId, reservationId = binding.reservationId, counter = binding.counter,
            characteristic = binding.characteristic, purpose = binding.purpose, payloadHash = binding.payloadHash,
            priorWrite = binding.priorWrite, candidate = binding.candidate, resolution = null,
            evidenceHash = binding.evidenceHash, detail = "reviewed unknown settings attempt",
            unresolvedPredecessor = binding.unresolvedPredecessor,
        )
        val state = old.copy(records = old.records.map { it.copy(
            reboot = 21, write = 36, reservation = recovery,
            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
            benchAmbiguityConvergenceAttempted = true, writeEvidence = listOf(evidence(first), evidence(second)),
        ) })
        val journal = SessionJournal(Storage())
        journal.commit(state)
        assertEquals(state, journal.load())
        assertEquals(second.reservation(), journal.load().records.single().reservation!!.unresolvedPredecessor!!.reservation())
    }

    @Test
    fun `completed historical alarm cursor recovery remains readable but cannot become live work`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "historical-alarm-cursor",
                counter = 33,
                phase = PumpSession.Phase.VERIFIED,
                operationId = "alarm-cursor-recovery",
                characteristic = "669a0c20-0008-969e-e211-fcbec93b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 32,
                candidate = PumpSession.WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = checkNotNull(reservation.operationId),
                reservationId = reservation.id,
                counter = reservation.counter,
                characteristic = checkNotNull(reservation.characteristic),
                purpose = checkNotNull(reservation.purpose),
                payloadHash = checkNotNull(reservation.payloadHash),
                priorWrite = checkNotNull(reservation.priorWrite),
                candidate = reservation.candidate,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "cd".repeat(32),
                detail = "completed historical alarm cursor recovery",
            )
        val state =
            old.copy(
                records = old.records.map {
                    it.copy(
                        reboot = 8,
                        read = 100,
                        write = 33,
                        reservation = reservation,
                        writeEvidence = listOf(evidence),
                        benchHistoryCounts =
                            listOf(
                                PumpSession.HistoryCountEvidence(
                                    family = PumpSession.HistoryFamily.ALARM,
                                    reboot = 8,
                                    read = 100,
                                    count = 200,
                                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                                    payloadHash = "ef".repeat(32),
                                ),
                            ),
                        writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                    )
                },
            )

        journal.commit(state)

        assertEquals(state, journal.load())
        val legacyBody = committedBody(storage)
        val legacyRecord = legacyBody.getJSONArray("records").getJSONObject(0)
        legacyRecord.getJSONObject("reservation").put("candidate", "BENCH_ALARM_CURSOR_RECOVERY_SELECTOR")
        legacyRecord.getJSONArray("writeEvidence").getJSONObject(0).put("candidate", "BENCH_ALARM_CURSOR_RECOVERY_SELECTOR")
        replaceBody(storage, legacyBody)
        assertEquals(state, journal.load())
        journal.commit(state)
        assertEquals(
            "BENCH_ALARM_CURSOR_RECOVERY_SELECTOR",
            committedBody(storage).getJSONArray("records").getJSONObject(0).getJSONObject("reservation").getString("candidate"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                state.copy(
                    records = state.records.map {
                        it.copy(reservation = reservation.copy(phase = PumpSession.Phase.ACKED))
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                state.copy(
                    records = state.records.map {
                        it.copy(writeEvidence = listOf(evidence.copy(resolution = null)))
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                state.copy(
                    records = state.records.map {
                        it.copy(writeEvidence = emptyList())
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                state.copy(
                    records = state.records.map {
                        it.copy(writeEvidence = listOf(evidence.copy(reservationId = "another-reservation")))
                    },
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                state.copy(
                    records = state.records.map {
                        it.copy(
                            writeEvidence =
                                listOf(
                                    evidence,
                                    evidence.copy(
                                        reservationId = "orphaned-reservation",
                                        evidenceHash = "12".repeat(32),
                                    ),
                                ),
                        )
                    },
                ),
            )
        }
    }

    @Test
    fun `superseded historical alarm recovery roundtrips only in its retired audit slot`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val legacy =
            PumpSession.Reservation(
                id = "historical-alarm-cursor",
                counter = 33,
                phase = PumpSession.Phase.VERIFIED,
                operationId = "alarm-cursor-recovery",
                characteristic = "669a0c20-0008-969e-e211-fcbec93b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 32,
                candidate = PumpSession.WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = checkNotNull(legacy.operationId),
                reservationId = legacy.id,
                counter = legacy.counter,
                characteristic = checkNotNull(legacy.characteristic),
                purpose = checkNotNull(legacy.purpose),
                payloadHash = checkNotNull(legacy.payloadHash),
                priorWrite = checkNotNull(legacy.priorWrite),
                candidate = legacy.candidate,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "cd".repeat(32),
                detail = "completed historical alarm cursor recovery",
            )
        val current =
            PumpSession.Reservation(
                id = "setting-1",
                counter = 34,
                phase = PumpSession.Phase.RESERVED,
                operationId = "setting-1",
                characteristic = "669a0c20-0008-969e-e211-fcbeb3147bc5",
                purpose = "SETTINGS_SELECTOR",
                payloadHash = "ef".repeat(32),
                priorWrite = 33,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
            )
        val state =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            reboot = 21,
                            read = 2_596,
                            write = 34,
                            reservation = current,
                            retiredLegacyBenchAlarmCursorRecovery = legacy,
                            writeEvidence = listOf(evidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                        )
                    },
            )

        journal.commit(state)

        assertEquals(state, journal.load())
        val record = committedBody(storage).getJSONArray("records").getJSONObject(0)
        assertEquals("setting-1", record.getJSONObject("reservation").getString("id"))
        assertEquals(
            "BENCH_ALARM_CURSOR_RECOVERY_SELECTOR",
            record.getJSONObject("retiredLegacyBenchAlarmCursorRecovery").getString("candidate"),
        )
    }

    @Test
    fun `version eight roundtrip preserves the exact pre-gap write floor and epoch gates`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "reservation-44",
                counter = 44,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "selector-gap",
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR,
            )
        val state =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 44,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            reservation = reservation,
                            benchStrictNextAccepted = true,
                            benchForwardGapAttempted = true
                        )
                    }
            )

        journal.commit(state)

        assertEquals(state, journal.load())
        assertEquals(42, journal.load().records.single().reservation!!.priorWrite)
        assertTrue(journal.load().records.single().benchStrictNextAccepted)
        assertTrue(journal.load().records.single().benchForwardGapAttempted)
    }

    @Test
    fun `current roundtrip preserves duplicate probe predecessor binding and marker`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val accepted =
            PumpSession.WriteEvidence(
                operationId = "accepted-event",
                reservationId = "accepted-reservation",
                counter = 43,
                characteristic = "669a0c20-0008-969e-e211-fcbecc3b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "cd".repeat(32),
                detail = "accepted event predecessor",
            )
        val predecessor =
            PumpSession.AcceptedWriteBinding(
                reboot = 8,
                operationId = accepted.operationId,
                reservationId = accepted.reservationId,
                counter = accepted.counter,
                characteristic = accepted.characteristic,
                purpose = accepted.purpose,
                payloadHash = accepted.payloadHash,
                priorWrite = accepted.priorWrite,
                candidate = accepted.candidate,
                evidenceHash = accepted.evidenceHash,
            )
        val duplicate =
            PumpSession.Reservation(
                id = "duplicate-reservation",
                counter = 43,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "duplicate-event",
                characteristic = accepted.characteristic,
                purpose = accepted.purpose,
                payloadHash = "ef".repeat(32),
                priorWrite = 43,
                candidate = PumpSession.WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR,
                acceptedPredecessor = predecessor,
            )
        val unresolved =
            PumpSession.WriteEvidence(
                operationId = "duplicate-event",
                reservationId = duplicate.id,
                counter = duplicate.counter,
                characteristic = checkNotNull(duplicate.characteristic),
                purpose = checkNotNull(duplicate.purpose),
                payloadHash = checkNotNull(duplicate.payloadHash),
                priorWrite = checkNotNull(duplicate.priorWrite),
                candidate = duplicate.candidate,
                resolution = null,
                evidenceHash = "12".repeat(32),
                detail = "duplicate probe unresolved",
                acceptedPredecessor = predecessor,
            )
        val state =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 43,
                            reservation = duplicate,
                            writeEvidence = listOf(accepted, unresolved),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            benchStrictNextAccepted = true,
                            benchDuplicateCounterAttempted = true,
                        )
                    },
            )

        journal.commit(state)

        assertEquals(state, journal.load())
        assertEquals(predecessor, journal.load().records.single().reservation!!.acceptedPredecessor)
        assertTrue(journal.load().records.single().benchDuplicateCounterAttempted)
    }

    @Test
    fun `version twelve rejects missing duplicate predecessor binding field`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val envelope = org.json.JSONObject(checkNotNull(storage.file))
        val alias = storage.anchors().single()
        val body = org.json.JSONObject(storage.open(alias, envelope.getString("sealed"))).put("version", 12)
        val record = body.getJSONArray("records").getJSONObject(0)
        record.remove("benchAmbiguityConvergenceAttempted")
        record.remove("benchDuplicateCounterPredecessor")
        record.getJSONArray("writeEvidence").let { evidence ->
            (0 until evidence.length()).forEach { evidence.getJSONObject(it).remove("unresolvedPredecessor") }
        }
        record.put(
            "reservation",
            org.json.JSONObject()
                .put("id", "duplicate")
                .put("counter", 43)
                .put("phase", "POSSIBLY_SENT")
                .put("operationId", "duplicate-event")
                .put("characteristic", "669a0c20-0008-969e-e211-fcbecc3b7bc5")
                .put("purpose", "HISTORY_SELECTOR")
                .put("payloadHash", "ab".repeat(32))
                .put("priorWrite", 43)
                .put("candidate", "BENCH_DUPLICATE_COUNTER_SELECTOR")
                .put("historyBinding", org.json.JSONObject.NULL),
        )
        storage.file = org.json.JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body.toString())).toString()

        assertThrows(IllegalArgumentException::class.java) { journal.load() }
    }

    @Test
    fun `version twelve rejects write evidence missing accepted predecessor field`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "accepted-event",
                reservationId = "accepted-reservation",
                counter = 43,
                characteristic = "669a0c20-0008-969e-e211-fcbecc3b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "cd".repeat(32),
                detail = "accepted event predecessor",
            )
        journal.commit(old.copy(records = old.records.map { it.copy(writeEvidence = listOf(evidence)) }))
        val body = committedBody(storage).put("version", 12)
        val record = body.getJSONArray("records").getJSONObject(0)
        record.remove("benchAmbiguityConvergenceAttempted")
        record.remove("benchDuplicateCounterPredecessor")
        val evidenceJson = record.getJSONArray("writeEvidence").getJSONObject(0)
        evidenceJson.remove("unresolvedPredecessor")
        evidenceJson
            .remove("acceptedPredecessor")
        replaceBody(storage, body)

        assertThrows(IllegalArgumentException::class.java) { journal.load() }
    }

    @Test
    fun `version thirteen rejects duplicate attempt marker without accepted predecessor`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val established =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 42,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                        )
                    },
            )
        journal.commit(established)
        val body = committedBody(storage)
        body.getJSONArray("records").getJSONObject(0)
            .put("benchDuplicateCounterAttempted", true)
            .put("benchStrictNextAccepted", false)
        replaceBody(storage, body)

        assertThrows(IllegalArgumentException::class.java) { journal.load() }
    }

    @Test
    fun `current roundtrip preserves explicit new epoch bootstrap state and attempt marker`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val state =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            reboot = 9,
                            read = 1,
                            write = null,
                            benchNewEpochBootstrapReference =
                                PumpSession.BootstrapReference(
                                    reboot = 8,
                                    read = 100,
                                    characteristic = "characteristic",
                                    payloadHash = "ab".repeat(32),
                                ),
                            writeBootstrapState = PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH,
                            benchNewEpochBootstrapAttempted = true,
                        )
                    },
            )

        journal.commit(state)

        assertEquals(state, journal.load())
        val loaded = journal.load().records.single()
        assertEquals(PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH, loaded.writeBootstrapState)
        assertTrue(loaded.benchNewEpochBootstrapAttempted)
        assertEquals(8, loaded.benchNewEpochBootstrapReference!!.reboot)
    }

    @Test
    fun `version eleven roundtrip preserves current epoch history count state and binding`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val state = boundHistoryState()
        val record = state.records.single()

        journal.commit(state)

        assertEquals(state, journal.load())
        val loaded = journal.load().records.single()
        assertEquals(record.benchHistoryCounts, loaded.benchHistoryCounts)
        assertTrue(loaded.benchHistorySelectorStates.isEmpty())
        assertEquals(checkNotNull(record.reservation).historyBinding, loaded.reservation!!.historyBinding)
        assertEquals(record.writeEvidence.single().historyBinding, loaded.writeEvidence.single().historyBinding)
    }

    @Test
    fun `journal validation rejects epoch-inconsistent and mismatched history bindings`() {
        val baseline = boundHistoryState()
        PumpSession.validate(baseline)
        val record = baseline.records.single()
        val reservation = checkNotNull(record.reservation)
        val binding = checkNotNull(reservation.historyBinding)

        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                baseline.copy(
                    records =
                        listOf(
                            record.copy(
                                reservation =
                                    reservation.copy(
                                        historyBinding =
                                            binding.copy(
                                                selectedBefore = binding.selectedBefore.copy(reboot = binding.selectedBefore.reboot + 1),
                                            ),
                                    ),
                            ),
                        ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(
                baseline.copy(
                    records =
                        listOf(
                            record.copy(
                                writeEvidence =
                                    listOf(
                                        record.writeEvidence.single().copy(
                                            historyBinding = binding.copy(writeIndex = binding.writeIndex + 1),
                                        ),
                                    ),
                            ),
                        ),
                ),
            )
        }
    }

    @Test
    fun `version eleven rejects malformed history arrays instead of silently emptying them`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        journal.commit(old)
        val body = committedBody(storage)
        body.getJSONArray("records").getJSONObject(0).put("benchHistoryCounts", "not-an-array")
        replaceBody(storage, body)

        assertThrows(IllegalArgumentException::class.java) { journal.load() }
    }

    @Test
    fun `version ten migration starts with no history count authority`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val preserved =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 42,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            benchNewEpochBootstrapReference =
                                PumpSession.BootstrapReference(8, 100, "event-index", "ab".repeat(32)),
                        )
                    },
            )
        journal.commit(preserved)
        val body = committedBody(storage).put("version", 10)
        body.getJSONArray("records").getJSONObject(0).remove("benchHistoryCounts")
        body.getJSONArray("records").getJSONObject(0).remove("benchHistorySelectorStates")
        replaceBody(storage, body)

        val loaded = journal.load().records.single()
        assertTrue(loaded.benchHistoryCounts.isEmpty())
        assertTrue(loaded.benchHistorySelectorStates.isEmpty())
        assertEquals(42, loaded.write)
        assertEquals(preserved.records.single().benchNewEpochBootstrapReference, loaded.benchNewEpochBootstrapReference)
    }

    @Test
    fun `version eleven migration starts with duplicate probe unused`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val preserved =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 42,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            benchStrictNextAccepted = true,
                        )
                    },
            )
        journal.commit(preserved)
        val body = committedBody(storage).put("version", 11)
        body.getJSONArray("records").getJSONObject(0).remove("benchDuplicateCounterAttempted")
        replaceBody(storage, body)

        val loaded = journal.load().records.single()
        assertEquals(42, loaded.write)
        assertTrue(loaded.benchStrictNextAccepted)
        assertFalse(loaded.benchDuplicateCounterAttempted)
    }

    @Test
    fun `version twelve migration preserves unresolved write and starts convergence unused`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "unresolved-two",
                counter = 2,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "ambiguous-event",
                characteristic = "669a0c20-0008-969e-e211-fcbecc3b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 1,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "ambiguous-event",
                reservationId = reservation.id,
                counter = 2,
                characteristic = checkNotNull(reservation.characteristic),
                purpose = checkNotNull(reservation.purpose),
                payloadHash = checkNotNull(reservation.payloadHash),
                priorWrite = 1,
                candidate = reservation.candidate,
                resolution = null,
                evidenceHash = "cd".repeat(32),
                detail = "counter two effect remains unknown",
            )
        val preserved =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 2,
                            reservation = reservation,
                            writeEvidence = listOf(evidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                        )
                    },
            )
        journal.commit(preserved)
        val body = committedBody(storage).put("version", 12)
        body.getJSONArray("records").getJSONObject(0).apply {
            remove("benchAmbiguityConvergenceAttempted")
            remove("benchDuplicateCounterPredecessor")
            getJSONObject("reservation").remove("unresolvedPredecessor")
            getJSONArray("writeEvidence").getJSONObject(0).remove("unresolvedPredecessor")
        }
        replaceBody(storage, body)

        val loaded = journal.load().records.single()
        assertEquals(reservation, loaded.reservation)
        assertEquals(listOf(evidence), loaded.writeEvidence)
        assertFalse(loaded.benchAmbiguityConvergenceAttempted)
        assertNull(loaded.benchDuplicateCounterPredecessor)
    }

    @Test
    fun `current roundtrip preserves convergence and nested duplicate predecessor bindings`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val unresolvedReservation =
            PumpSession.Reservation(
                id = "unresolved-two",
                counter = 2,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "ambiguous-event",
                characteristic = "669a0c20-0008-969e-e211-fcbecc3b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 1,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
            )
        val unresolvedEvidence =
            PumpSession.WriteEvidence(
                operationId = checkNotNull(unresolvedReservation.operationId),
                reservationId = unresolvedReservation.id,
                counter = unresolvedReservation.counter,
                characteristic = checkNotNull(unresolvedReservation.characteristic),
                purpose = checkNotNull(unresolvedReservation.purpose),
                payloadHash = checkNotNull(unresolvedReservation.payloadHash),
                priorWrite = checkNotNull(unresolvedReservation.priorWrite),
                candidate = unresolvedReservation.candidate,
                resolution = null,
                evidenceHash = "cd".repeat(32),
                detail = "counter two effect remains unknown",
            )
        val unresolvedBinding = PumpSession.UnresolvedWriteBinding.from(8, unresolvedReservation, unresolvedEvidence)
        val convergenceEvidence =
            PumpSession.WriteEvidence(
                operationId = "converge-event",
                reservationId = "converge-three",
                counter = 3,
                characteristic = checkNotNull(unresolvedReservation.characteristic),
                purpose = checkNotNull(unresolvedReservation.purpose),
                payloadHash = "ef".repeat(32),
                priorWrite = 2,
                candidate = PumpSession.WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "12".repeat(32),
                detail = "counter three accepted",
                unresolvedPredecessor = unresolvedBinding,
            )
        val acceptedBinding = PumpSession.AcceptedWriteBinding.from(8, convergenceEvidence)
        val duplicate =
            PumpSession.Reservation(
                id = "duplicate-three",
                counter = 3,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "duplicate-event",
                characteristic = convergenceEvidence.characteristic,
                purpose = convergenceEvidence.purpose,
                payloadHash = "34".repeat(32),
                priorWrite = 3,
                candidate = PumpSession.WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR,
                acceptedPredecessor = acceptedBinding,
            )
        val duplicateEvidence =
            PumpSession.WriteEvidence(
                operationId = checkNotNull(duplicate.operationId),
                reservationId = duplicate.id,
                counter = duplicate.counter,
                characteristic = checkNotNull(duplicate.characteristic),
                purpose = checkNotNull(duplicate.purpose),
                payloadHash = checkNotNull(duplicate.payloadHash),
                priorWrite = checkNotNull(duplicate.priorWrite),
                candidate = duplicate.candidate,
                resolution = null,
                evidenceHash = "56".repeat(32),
                detail = "duplicate counter remains unresolved",
                acceptedPredecessor = acceptedBinding,
            )
        val state =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 3,
                            reservation = duplicate,
                            writeEvidence = listOf(unresolvedEvidence, convergenceEvidence, duplicateEvidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            benchAmbiguityConvergenceAttempted = true,
                            benchDuplicateCounterAttempted = true,
                            benchDuplicateCounterPredecessor = acceptedBinding,
                        )
                    },
            )

        journal.commit(state)

        assertEquals(state, journal.load())
        assertEquals(unresolvedBinding, journal.load().records.single().reservation!!.acceptedPredecessor!!.unresolvedPredecessor)
    }

    @Test
    fun `version thirteen rejects convergence binding with altered unresolved evidence hash`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "unresolved-two",
                counter = 2,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "ambiguous-event",
                characteristic = "669a0c20-0008-969e-e211-fcbecc3b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 1,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = checkNotNull(reservation.operationId),
                reservationId = reservation.id,
                counter = reservation.counter,
                characteristic = checkNotNull(reservation.characteristic),
                purpose = checkNotNull(reservation.purpose),
                payloadHash = checkNotNull(reservation.payloadHash),
                priorWrite = checkNotNull(reservation.priorWrite),
                candidate = reservation.candidate,
                resolution = null,
                evidenceHash = "cd".repeat(32),
                detail = "counter two effect remains unknown",
            )
        val binding = PumpSession.UnresolvedWriteBinding.from(8, reservation, evidence)
        val convergence =
            PumpSession.Reservation(
                id = "converge-three",
                counter = 3,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "converge-event",
                characteristic = checkNotNull(reservation.characteristic),
                purpose = checkNotNull(reservation.purpose),
                payloadHash = "ef".repeat(32),
                priorWrite = 2,
                candidate = PumpSession.WriteCandidate.BENCH_AMBIGUITY_CONVERGENCE_SELECTOR,
                unresolvedPredecessor = binding,
            )
        journal.commit(
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 3,
                            reservation = convergence,
                            writeEvidence = listOf(evidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            benchAmbiguityConvergenceAttempted = true,
                        )
                    },
            ),
        )
        val body = committedBody(storage)
        body.getJSONArray("records").getJSONObject(0).getJSONObject("reservation")
            .getJSONObject("unresolvedPredecessor").put("evidenceHash", "12".repeat(32))
        replaceBody(storage, body)

        assertThrows(IllegalArgumentException::class.java) { journal.load() }
    }

    @Test
    fun `version nine migration preserves established floor and starts with no bootstrap reference`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "reservation-4",
                counter = 4,
                phase = PumpSession.Phase.ACKED,
                operationId = "selector-gap",
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 2,
                candidate = PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "selector-gap",
                reservationId = reservation.id,
                counter = 4,
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 2,
                candidate = PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR,
                resolution = null,
                evidenceHash = "cd".repeat(32),
                detail = "reviewed but unresolved",
            )
        journal.commit(
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 4,
                            reservation = reservation,
                            writeEvidence = listOf(evidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            benchStrictNextAccepted = true,
                            benchForwardGapAttempted = true,
                        )
                    },
            ),
        )
        val body = committedBody(storage).put("version", 9)
        body.getJSONArray("records").getJSONObject(0).apply {
            remove("writeBootstrapState")
            remove("benchNewEpochBootstrapAttempted")
            remove("benchNewEpochBootstrapReference")
        }
        replaceBody(storage, body)

        val loaded = journal.load().records.single()

        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, loaded.writeBootstrapState)
        assertFalse(loaded.benchNewEpochBootstrapAttempted)
        assertNull(loaded.benchNewEpochBootstrapReference)
        assertEquals(reservation, loaded.reservation)
        assertEquals(listOf(evidence), loaded.writeEvidence)
    }

    @Test
    fun `version eight rejects missing reservation and epoch gate fields`() {
        val baseState =
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 101,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            reservation =
                                PumpSession.Reservation(
                                    id = "reservation-101",
                                    counter = 101,
                                    phase = PumpSession.Phase.RESERVED,
                                    operationId = "selector",
                                    characteristic = "characteristic",
                                    purpose = "HISTORY_SELECTOR",
                                    payloadHash = "ab".repeat(32),
                                    priorWrite = 100,
                                    candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                                ),
                        )
                    },
            )
        for (field in listOf("benchStrictNextAccepted", "benchForwardGapAttempted")) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(baseState)
            val body = committedBody(storage)
            body.getJSONArray("records").getJSONObject(0).remove(field)
            replaceBody(storage, body)
            assertThrows(IllegalArgumentException::class.java) { journal.load() }
        }
        for (field in listOf("priorWrite", "candidate")) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(baseState)
            val body = committedBody(storage)
            body.getJSONArray("records").getJSONObject(0).getJSONObject("reservation").remove(field)
            replaceBody(storage, body)
            assertThrows(IllegalArgumentException::class.java) { journal.load() }
        }
    }

    @Test
    fun `version nine rejects missing evidence binding fields`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "selector-9",
                reservationId = "reservation-43",
                counter = 43,
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "cd".repeat(32),
                detail = "reviewed target evidence",
            )
        journal.commit(old.copy(records = old.records.map { it.copy(writeEvidence = listOf(evidence)) }))
        val body = committedBody(storage)
        body.getJSONArray("records").getJSONObject(0).getJSONArray("writeEvidence").getJSONObject(0).remove("purpose")
        replaceBody(storage, body)

        assertThrows(IllegalArgumentException::class.java) { journal.load() }
    }

    @Test
    fun `version eight evidence recovers binding only from its retained reservation`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "reservation-43",
                counter = 43,
                phase = PumpSession.Phase.VERIFIED,
                operationId = "selector-9",
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "selector-9",
                reservationId = reservation.id,
                counter = reservation.counter,
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                resolution = PumpSession.WriteResolution.ACCEPTED,
                evidenceHash = "cd".repeat(32),
                detail = "reviewed target evidence",
            )
        journal.commit(
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 43,
                            reservation = reservation,
                            writeEvidence = listOf(evidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                        )
                    },
            ),
        )
        val body = committedBody(storage).put("version", 8)
        val evidenceJson = body.getJSONArray("records").getJSONObject(0).getJSONArray("writeEvidence").getJSONObject(0)
        listOf("characteristic", "purpose", "payloadHash", "priorWrite", "candidate").forEach(evidenceJson::remove)
        replaceBody(storage, body)

        assertEquals(evidence, journal.load().records.single().writeEvidence.single())

        body.getJSONArray("records").getJSONObject(0).put("reservation", org.json.JSONObject.NULL)
        replaceBody(storage, body)
        assertThrows(IllegalStateException::class.java) { journal.load() }
    }

    @Test
    fun `version six gap reservation migrates to durable attempted epoch state`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val reservation =
            PumpSession.Reservation(
                id = "reservation-44",
                counter = 44,
                phase = PumpSession.Phase.POSSIBLY_SENT,
                operationId = "selector-gap",
                characteristic = "characteristic",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR,
            )
        journal.commit(
            old.copy(
                records =
                    old.records.map {
                        it.copy(
                            write = 44,
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                            reservation = reservation,
                            benchStrictNextAccepted = true,
                            benchForwardGapAttempted = true,
                        )
                    },
            ),
        )
        val body = committedBody(storage).put("version", 6)
        body.getJSONArray("records").getJSONObject(0).apply {
            remove("benchStrictNextAccepted")
            remove("benchForwardGapAttempted")
            getJSONObject("reservation").remove("candidate")
        }
        replaceBody(storage, body)

        val loaded = journal.load().records.single()

        assertEquals(PumpSession.WriteCandidate.BENCH_FORWARD_GAP_SELECTOR, loaded.reservation!!.candidate)
        assertTrue(loaded.benchStrictNextAccepted)
        assertTrue(loaded.benchForwardGapAttempted)
    }

    @Test
    fun `legacy version one journal selects its sole generation without inventing protected credentials`() {
        val storage = Storage()
        val alias = "ypso.session.revision.legacy"
        storage.create(alias)
        val body = """{"version":1,"records":[{"pump":"pump","key":"${"00".repeat(32)}","generation":"generation","reboot":8,"read":100,"write":null,"reservation":null}]}"""
        storage.file = org.json.JSONObject()
            .put("anchor", alias)
            .put("body", body)
            .put("mac", storage.authenticateLegacy(alias, body).joinToString("") { "%02x".format(it) })
            .toString()

        val loaded = SessionJournal(storage).load()

        assertEquals("generation", loaded.activeGeneration)
        assertNull(loaded.records.single().keyHex)
        assertEquals(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE), loaded.availability.causes)
    }

    @Test
    fun `version two upgrades with no invented candidate and final candidate attempts survive restart`() {
        val storage = Storage()
        val alias = "ypso.session.revision.v2"
        storage.create(alias)
        val keyHex = "01".repeat(32)
        val keyId = PumpSession.fingerprint(ByteArray(32) { 1 })
        val body = """{"version":2,"records":[{"pump":"pump","key":"$keyId","generation":"generation","reboot":8,"read":100,"write":null,"reservation":null,"serial":"serial","keyHex":"$keyHex","createdAt":null,"importedAt":1,"source":{},"verifiedAt":2,"verifiedSerial":"serial"}],"activeGeneration":"generation","availability":{"causes":[],"since":2,"code":null,"operation":null,"firmware":null,"failures":0,"retryAt":null}}"""
        storage.file = org.json.JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body)).toString()
        val journal = SessionJournal(storage)

        val upgraded = journal.load()
        assertNull(upgraded.candidateGeneration)
        assertNull(upgraded.lastAttempt)

        val candidateHex = "02".repeat(32)
        val candidate = PumpSession.Record("pump", keyId, "candidate", null, null, null, serial = "serial", keyHex = keyHex)
        val pending = upgraded.copy(
            records = upgraded.records + candidate,
            candidateGeneration = candidate.generation,
            candidateReplacesGeneration = upgraded.activeGeneration,
            candidateAttemptId = "attempt",
            candidateAvailability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        )
        journal.commit(pending)
        assertEquals(pending, SessionJournal(storage).load())

        val cancelled = pending.copy(
            records = pending.records - candidate,
            candidateGeneration = null,
            candidateReplacesGeneration = null,
            candidateAttemptId = null,
            candidateAvailability = null,
            lastAttempt = PumpSession.AttemptResult("attempt", PumpSession.AttemptStatus.CANCELLED)
        )
        journal.commit(cancelled)
        assertEquals(cancelled, SessionJournal(storage).load())
    }

    @Test
    fun `journal validation rejects negative candidate failures and mismatched replacement identity`() {
        val keyHex = "01".repeat(32)
        val keyId = PumpSession.fingerprint(ByteArray(32) { 1 })
        val otherKeyId = PumpSession.fingerprint(ByteArray(32) { 2 })
        val active = PumpSession.Record("pump", keyId, "active", 8, 100, null, serial = "serial", keyHex = keyHex)
        val candidate = PumpSession.Record("pump", keyId, "candidate", null, null, null, serial = "serial", keyHex = keyHex)
        val state = PumpSession.State(
            records = listOf(active, candidate),
            activeGeneration = "active",
            candidateGeneration = "candidate",
            candidateReplacesGeneration = "active",
            candidateAttemptId = "attempt",
            candidateAvailability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE))
        )
        PumpSession.validate(state)

        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(state.copy(candidateAvailability = state.candidateAvailability!!.copy(failures = -1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(state.copy(candidateGeneration = null, candidateAttemptId = null, candidateAvailability = null))
        }
        val foreign = candidate.copy(keyId = otherKeyId)
        assertThrows(IllegalArgumentException::class.java) {
            PumpSession.validate(state.copy(records = listOf(active, foreign)))
        }
    }

    @Test
    fun `failed seal removes its uncommitted anchor and preserves the prior revision`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val prior = old
        journal.commit(prior)
        storage.failSeal = true

        assertThrows(Exception::class.java) { journal.commit(next) }

        storage.failSeal = false
        assertEquals(prior, journal.load())
        assertEquals(1, storage.anchors().size)
    }

    @Test
    fun `every journal crash boundary restores new state or becomes unavailable`() {
        val boundaries = listOf("before-create", "after-create", "before-delete", "after-delete", "before-truncate", "after-truncate", "partial-write", "before-sync", "after-sync")
        for (boundary in boundaries) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            storage.fault = boundary
            assertThrows(IllegalStateException::class.java) { journal.commit(next) }
            storage.fault = ""
            val loaded = runCatching { SessionJournal(storage).load() }.getOrNull()
            when (boundary) {
                "before-create", "after-create", "before-truncate", "after-truncate", "partial-write", "before-sync", "after-sync" ->
                    assertEquals(old, loaded) // Atomic publication has not occurred.
                "before-delete" -> assertEquals(old, loaded) // Transition still resolves to prior.
                "after-delete" -> assertEquals(next, loaded) // Exact prior anchor was retired.
            }
        }
    }

    @Test
    fun `file loss key loss corruption and restored backup on another installation reject`() {
        for (fault in 0..3) {
            val storage = Storage()
            val journal = SessionJournal(storage)
            journal.commit(old)
            when (fault) {
                0 -> storage.file = null
                1 -> storage.keys.clear()
                2 -> storage.file = storage.file!!.replace("100", "999")
                3 -> storage.keys.values.first()[0] = 42
            }
            assertTrue(runCatching { journal.load() }.isFailure, "fault=$fault")
        }
    }

    @Test
    fun `invalid state invariants reject before creating an anchor`() {
        val storage = Storage()
        val journal = SessionJournal(storage)
        val record = old.records.single()
        val invalid = listOf(
            old.copy(records = listOf(record, record)),
            old.copy(records = listOf(record.copy(read = -1))),
            old.copy(records = listOf(record.copy(reboot = -1))),
            old.copy(records = listOf(record.copy(write = 42, reservation = PumpSession.Reservation("id", 43, PumpSession.Phase.ACKED))))
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { journal.commit(it) } }
        assertTrue(storage.keys.isEmpty())
        assertNull(storage.file)
    }

    private fun boundHistoryState(): PumpSession.State {
        val counts =
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 200,
                    characteristic = "669a0c20-0008-969e-e211-fcbec83b7bc5",
                    payloadHash = "ab".repeat(32),
                ),
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.SYSTEM,
                    reboot = 8,
                    read = 100,
                    count = 600,
                    characteristic = "86a5a431-d442-2c8d-304b-19ee355571fc",
                    payloadHash = "cd".repeat(32),
                ),
            )
        val selectorStates =
            listOf(
                PumpSession.HistorySelectorState(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    index = 150,
                    characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
                    payloadHash = "ee".repeat(32),
                ),
            )
        val binding = PumpSession.HistoryWriteBinding(counts.first(), selectorStates.first(), writeIndex = 199)
        val reservation =
            PumpSession.Reservation(
                id = "reservation-43",
                counter = 43,
                phase = PumpSession.Phase.ACKED,
                operationId = "selector-alarm",
                characteristic = "669a0c20-0008-969e-e211-fcbec93b7bc5",
                purpose = "HISTORY_SELECTOR",
                payloadHash = "ab".repeat(32),
                priorWrite = 42,
                candidate = PumpSession.WriteCandidate.BENCH_STRICT_NEXT_SELECTOR,
                historyBinding = binding,
            )
        val evidence =
            PumpSession.WriteEvidence(
                operationId = "selector-alarm",
                reservationId = reservation.id,
                counter = 43,
                characteristic = reservation.characteristic!!,
                purpose = reservation.purpose!!,
                payloadHash = reservation.payloadHash!!,
                priorWrite = 42,
                candidate = reservation.candidate,
                resolution = null,
                evidenceHash = "cd".repeat(32),
                detail = "reviewed alarm selector evidence with durable count binding",
                historyBinding = binding,
            )
        return old.copy(
            records =
                old.records.map {
                    it.copy(
                        write = 43,
                        writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                        reservation = reservation,
                        writeEvidence = listOf(evidence),
                        benchHistoryCounts = counts,
                        // The active reservation consumed the family's pre-row observation.
                        benchHistorySelectorStates = emptyList(),
                    )
                },
        )
    }

    private fun committedBody(storage: Storage): org.json.JSONObject {
        val envelope = org.json.JSONObject(checkNotNull(storage.file))
        return org.json.JSONObject(storage.open(envelope.getString("anchor"), envelope.getString("sealed")))
    }

    private fun replaceBody(
        storage: Storage,
        body: org.json.JSONObject,
    ) {
        val alias = org.json.JSONObject(checkNotNull(storage.file)).getString("anchor")
        storage.file = org.json.JSONObject().put("anchor", alias).put("sealed", storage.seal(alias, body.toString())).toString()
    }
}
