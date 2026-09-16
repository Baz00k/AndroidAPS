package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBenchWriteCoordinator
import app.aaps.pump.ypsopump.ble.YpsoCommandReadiness
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoSemanticEvidence
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoBenchWriteCoordinatorTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val evidenceHash = "cd".repeat(32)
    private val gatt = Any()
    private val store = MemoryStore()
    private val session: PumpSession
    private val token: PumpSession.Token
    private val readiness = YpsoCommandReadiness()
    private val callbacks = mutableListOf<YpsoWriteOutcome>()
    private val frames = mutableListOf<ByteArray>()
    private var deadline: Runnable? = null
    private val transport =
        YpsoSerializedWriteTransport(
            scheduleDeadline = { runnable, _ -> deadline = runnable },
            cancelDeadline = { if (deadline === it) deadline = null },
        )
    private val crypto = SessionCrypto()
    private val coordinator: YpsoBenchWriteCoordinator
    private val owner: YpsoBenchWriteCoordinator.Owner

    init {
        PumpSession(store).provisionReadBaseline("pump", key, 8, 100)
        store.saved =
            store.saved.copy(
                records =
                    store.saved.records.map {
                        it.copy(write = 42, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
                    },
            )
        session = PumpSession(store)
        token = session.open("pump", key)
        owner = YpsoBenchWriteCoordinator.Owner(gatt, "connection", token)
        coordinator = YpsoBenchWriteCoordinator(session, crypto, readiness, transport)
    }

    @Test
    fun `policy and readiness fail before reservation encryption or dispatch`() {
        val commits = store.commits
        assertFalse(write(byteArrayOf(1)))
        val policy = callbacks.single() as YpsoWriteOutcome.NotSent
        assertEquals(YpsoWriteFailure.Layer.POLICY, policy.failure.layer)
        assertNull(policy.counter)
        assertEquals(commits, store.commits)
        assertTrue(frames.isEmpty())

        callbacks.clear()
        assertFalse(write(YpsoGlb.encode(1)))
        val notReady = callbacks.single() as YpsoWriteOutcome.NotSent
        assertEquals(YpsoWriteFailure.Layer.READINESS, notReady.failure.layer)
        assertEquals(42, session.snapshot()!!.write)
        assertNull(session.snapshot()!!.reservation)
    }

    @Test
    fun `current bench dispatcher has no historical alarm recovery mode`() {
        assertFalse(
            YpsoBenchWriteCoordinator.BenchWriteMode.entries.any {
                it.name == "BENCH_ALARM_CURSOR_RECOVERY_SELECTOR"
            },
        )
    }

    @Test
    fun `completed legacy alarm recovery does not block a modern settings selector`() {
        val legacyStore = MemoryStore()
        PumpSession(legacyStore).provisionReadBaseline("pump", key, 21, 2_596)
        val legacyReservation =
            PumpSession.Reservation(
                id = "historical-alarm-cursor",
                counter = 33,
                phase = PumpSession.Phase.VERIFIED,
                operationId = "alarm-cursor-1789567816",
                characteristic = YpsoWritePolicy.ALARM_INDEX_UUID.toString(),
                purpose = YpsoRemoteWrite.HISTORY_SELECTOR.name,
                payloadHash = "ab".repeat(32),
                priorWrite = 32,
                candidate = PumpSession.WriteCandidate.LEGACY_BENCH_ALARM_CURSOR_RECOVERY_SELECTOR,
            )
        val legacyEvidence =
            PumpSession.WriteEvidence(
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
        legacyStore.saved =
            legacyStore.saved.copy(
                records =
                    legacyStore.saved.records.map {
                        it.copy(
                            write = 33,
                            reservation = legacyReservation,
                            writeEvidence = listOf(legacyEvidence),
                            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
                        )
                    },
            )
        val legacySession = PumpSession(legacyStore)
        val legacyToken = legacySession.open("pump", key)
        val legacyOwner = YpsoBenchWriteCoordinator.Owner(gatt, "legacy-connection", legacyToken)
        val legacyReadiness = YpsoCommandReadiness()
        legacyReadiness.connected(legacyOwner.readinessOwner())
        legacyReadiness.authenticated(legacyOwner.readinessOwner())
        legacyReadiness.readVerified(legacyOwner.readinessOwner())
        legacyReadiness.requiredSetupVerified(legacyOwner.readinessOwner())
        val legacyFrames = mutableListOf<ByteArray>()
        val legacyOutcomes = mutableListOf<YpsoWriteOutcome>()
        val legacyCoordinator =
            YpsoBenchWriteCoordinator(
                legacySession,
                crypto,
                legacyReadiness,
                YpsoSerializedWriteTransport({ _, _ -> }, {}),
            )

        assertTrue(
            legacyCoordinator.writeSelector(
                writeId = "setting-1",
                owner = legacyOwner,
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                plaintext = YpsoGlb.encode(1),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { frame -> legacyFrames.add(frame.copyOf()) },
                onOutcome = legacyOutcomes::add,
            ),
        )
        assertTrue(legacyOutcomes.isEmpty())
        assertTrue(legacyFrames.isNotEmpty())
        assertEquals(34, legacySession.snapshot()!!.reservation!!.counter)
        assertTrue(legacySession.snapshot()!!.writeEvidence.contains(legacyEvidence))
    }

    @Test
    fun `reservation encryption fragments ACK and semantic verification stay one transaction`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(17)))
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
        assertEquals(43, session.snapshot()!!.write)
        assertEquals("selector-1", session.snapshot()!!.reservation!!.operationId)
        assertEquals(YpsoWritePolicy.EVENT_INDEX_UUID.toString(), session.snapshot()!!.reservation!!.characteristic)
        assertEquals(YpsoRemoteWrite.HISTORY_SELECTOR.name, session.snapshot()!!.reservation!!.purpose)

        repeat(15) {
            if (callbacks.isEmpty()) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val encrypted = YpsoFraming.parseMultiFrameRead(frames)
        val message = crypto.decrypt(encrypted, key)
        assertArrayEquals(YpsoGlb.encode(17), message.body)
        assertEquals(8, message.reboot)
        assertEquals(43, message.counter)

        assertTrue(callbacks.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(PumpSession.Phase.ACKED, session.snapshot()!!.reservation!!.phase)

        assertTrue(
            coordinator.reconcile(
                "selector-1",
                owner,
                YpsoBenchWriteCoordinator.Reconciliation(
                    YpsoSemanticEvidence.ACCEPTED,
                    PumpSession.WriteResolution.ACCEPTED,
                    evidenceHash,
                    "event value readback matched selector 17",
                ),
            ),
        )
        assertTrue(callbacks.last() is YpsoWriteOutcome.Verified)
        assertEquals(PumpSession.Phase.VERIFIED, session.snapshot()!!.reservation!!.phase)
    }

    @Test
    fun `single bounded forward gap encrypts plus two and restores exact floor when proven not consumed`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(16)))
        while (callbacks.isEmpty()) {
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.ACCEPTED,
                PumpSession.WriteResolution.ACCEPTED,
                evidenceHash,
                "strict-next selector accepted before bounded gap measurement",
            ),
        )
        frames.clear()
        callbacks.clear()
        assertTrue(write(YpsoGlb.encode(17), mode = YpsoBenchWriteCoordinator.BenchWriteMode.FORWARD_GAP))
        while (frames.size < (frames.first()[0].toInt() and 0x0f)) {
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val encrypted = YpsoFraming.parseMultiFrameRead(frames)
        val message = crypto.decrypt(encrypted, key)
        assertEquals(45, message.counter)
        assertEquals(43, session.snapshot()!!.reservation!!.priorWrite)
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 138)

        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.REJECTED,
                PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
                evidenceHash,
                "bounded target evidence proved the +2 candidate was not consumed",
            ),
        )

        assertEquals(43, session.snapshot()!!.write)
        assertNull(session.snapshot()!!.reservation)
        assertTrue(session.snapshot()!!.benchForwardGapAttempted)
    }

    @Test
    fun `duplicate probe encrypts a different event payload with the accepted predecessor counter`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(16), writeId = "accepted-event"))
        while (callbacks.isEmpty()) {
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val acceptedMessage = crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key)
        coordinator.reconcile(
            "accepted-event",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.ACCEPTED,
                PumpSession.WriteResolution.ACCEPTED,
                evidenceHash,
                "accepted predecessor event selector",
            ),
        )

        frames.clear()
        callbacks.clear()
        assertTrue(
            write(
                YpsoGlb.encode(17),
                writeId = "duplicate-event",
                mode = YpsoBenchWriteCoordinator.BenchWriteMode.DUPLICATE_COUNTER,
            ),
        )
        while (callbacks.isEmpty()) {
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val duplicateMessage = crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key)

        assertEquals(acceptedMessage.counter, duplicateMessage.counter)
        assertEquals(43, duplicateMessage.counter)
        assertArrayEquals(YpsoGlb.encode(17), duplicateMessage.body)
        assertEquals(PumpSession.WriteCandidate.BENCH_DUPLICATE_COUNTER_SELECTOR, session.snapshot()!!.reservation!!.candidate)
        assertEquals("accepted-event", session.snapshot()!!.reservation!!.acceptedPredecessor!!.operationId)
        assertEquals(evidenceHash, session.snapshot()!!.reservation!!.acceptedPredecessor!!.evidenceHash)
    }

    @Test
    fun `ambiguity convergence bypasses only its bound unresolved predecessor and encrypts counter plus one`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(16), writeId = "ambiguous-event"))
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        coordinator.ownerDisconnected(gatt, "injected interruption after first frame")
        val unresolved = session.snapshot()!!.reservation!!
        assertEquals(43, unresolved.counter)
        session.recordUnresolvedWriteEvidence(token, unresolved.id, evidenceHash, "reviewed injected interruption remains unknown")

        frames.clear()
        callbacks.clear()
        makeReady()
        assertTrue(
            write(
                YpsoGlb.encode(17),
                writeId = "converge-event",
                mode = YpsoBenchWriteCoordinator.BenchWriteMode.AMBIGUITY_CONVERGENCE,
            ),
        )
        while (callbacks.isEmpty()) {
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val message = crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key)

        assertEquals(44, message.counter)
        assertArrayEquals(YpsoGlb.encode(17), message.body)
        assertEquals(unresolved.id, session.snapshot()!!.reservation!!.unresolvedPredecessor!!.reservationId)
        assertEquals(evidenceHash, session.snapshot()!!.reservation!!.unresolvedPredecessor!!.evidenceHash)
        assertTrue(session.snapshot()!!.benchAmbiguityConvergenceAttempted)
    }

    @Test
    fun `settings ambiguity convergence repeats the setting id at counter plus one`() {
        makeReady()
        assertTrue(
            coordinator.writeSelector(
                writeId = "ambiguous-setting-1",
                owner = owner,
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                plaintext = YpsoGlb.encode(1),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { frame -> frames.add(frame.copyOf()) },
                onOutcome = callbacks::add,
            ),
        )
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 139)
        val unresolved = session.snapshot()!!.reservation!!
        assertEquals(43, unresolved.counter)
        coordinator.ownerDisconnected(gatt, "close after ambiguous final callback")
        session.recordUnresolvedWriteEvidence(token, unresolved.id, evidenceHash, "reviewed settings selector remains unknown")

        frames.clear()
        callbacks.clear()
        makeReady()
        assertTrue(
            coordinator.writeSelector(
                writeId = "converge-setting-1",
                owner = owner,
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                plaintext = YpsoGlb.encode(1),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                mode = YpsoBenchWriteCoordinator.BenchWriteMode.AMBIGUITY_CONVERGENCE,
                dispatch = { frame -> frames.add(frame.copyOf()) },
                onOutcome = callbacks::add,
            ),
        )
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        val message = crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key)

        assertEquals(44, message.counter)
        assertArrayEquals(YpsoGlb.encode(1), message.body)
        assertEquals(unresolved.id, session.snapshot()!!.reservation!!.unresolvedPredecessor!!.reservationId)
        assertEquals(evidenceHash, session.snapshot()!!.reservation!!.unresolvedPredecessor!!.evidenceHash)
    }

    @Test
    fun `settings ambiguity convergence rejects a changed setting id`() {
        makeReady()
        assertTrue(
            coordinator.writeSelector(
                writeId = "ambiguous-setting-1",
                owner = owner,
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                plaintext = YpsoGlb.encode(1),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { frame -> frames.add(frame.copyOf()) },
                onOutcome = callbacks::add,
            ),
        )
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 139)
        val unresolved = session.snapshot()!!.reservation!!
        coordinator.ownerDisconnected(gatt, "close after ambiguous final callback")
        session.recordUnresolvedWriteEvidence(token, unresolved.id, evidenceHash, "reviewed settings selector remains unknown")

        callbacks.clear()
        makeReady()
        assertFalse(
            coordinator.writeSelector(
                writeId = "converge-setting-2",
                owner = owner,
                category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                plaintext = YpsoGlb.encode(2),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                mode = YpsoBenchWriteCoordinator.BenchWriteMode.AMBIGUITY_CONVERGENCE,
                dispatch = { error("changed setting ID must not dispatch") },
                onOutcome = callbacks::add,
            ),
        )
        val failure = callbacks.single() as YpsoWriteOutcome.NotSent
        assertEquals(YpsoWriteFailure.Layer.SESSION, failure.failure.layer)
        assertEquals(unresolved, session.snapshot()!!.reservation)
        assertFalse(session.snapshot()!!.benchAmbiguityConvergenceAttempted)
    }

    @Test
    fun `live reconciliation requires the original GATT connection and generation owner`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(17)))
        repeat(15) {
            if (callbacks.isEmpty()) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val reconciliation =
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.ACCEPTED,
                PumpSession.WriteResolution.ACCEPTED,
                evidenceHash,
                "event value readback matched selector 17",
            )

        assertFailsOwner {
            coordinator.reconcile("selector-1", owner.copy(gatt = Any()), reconciliation)
        }
        assertFailsOwner {
            coordinator.reconcile("selector-1", owner.copy(connectionId = "other"), reconciliation)
        }
        assertEquals(PumpSession.Phase.ACKED, session.snapshot()!!.reservation!!.phase)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `duplicate callback cannot verify or resolve the live durable reservation`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(17)))
        repeat(15) {
            if (callbacks.isEmpty()) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)

        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.ACCEPTED,
                PumpSession.WriteResolution.ACCEPTED,
                evidenceHash,
                "readback matched but callback ownership is ambiguous",
            ),
        )

        assertTrue(callbacks.last() is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(PumpSession.Phase.ACKED, session.snapshot()!!.reservation!!.phase)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `duplicate callback cannot prove rejection or roll back the live durable reservation`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(17)))
        repeat(15) {
            if (callbacks.isEmpty()) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)

        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.REJECTED,
                PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
                evidenceHash,
                "rejection observed but callback ownership is ambiguous",
            ),
        )

        assertTrue(callbacks.last() is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(43, session.snapshot()!!.write)
        assertEquals(PumpSession.Phase.ACKED, session.snapshot()!!.reservation!!.phase)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `first local dispatch refusal rolls back durable reservation`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(1)) { false })

        assertTrue(callbacks.single() is YpsoWriteOutcome.NotSent)
        assertEquals(42, session.snapshot()!!.write)
        assertNull(session.snapshot()!!.reservation)
    }

    @Test
    fun `lost ACK remains durable uncertainty across restart and is never automatically retried`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(1)))
        assertEquals(1, frames.size)
        deadline!!.run()

        assertTrue(callbacks.single() is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(
            PumpSession.Phase.POSSIBLY_SENT,
            store.saved.records
                .single()
                .reservation!!
                .phase,
        )
        val restarted = PumpSession(store)
        val next = restarted.open("pump", key)
        assertThrowsUnresolved { restarted.reserve(next, restarted.begin(next)) }
        assertEquals(1, frames.size)
    }

    @Test
    fun `numeric rejection is retained with layer characteristic firmware and measured consumption`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(1)))
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 138)

        val uncertain = callbacks.single() as YpsoWriteOutcome.PossiblyApplied
        assertEquals(138, uncertain.failure.code)
        assertEquals(YpsoWriteFailure.Layer.GATT_CALLBACK, uncertain.failure.layer)
        assertEquals(YpsoWritePolicy.EVENT_INDEX_UUID, uncertain.failure.characteristic)
        assertEquals("V05.00.52", uncertain.failure.firmware)
        assertTrue(transport.hasUnresolvedWrite())

        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.REJECTED,
                PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED,
                evidenceHash,
                "target trace shows counter consumed on code 138",
            ),
        )
        assertEquals(43, session.snapshot()!!.write)
        assertEquals(PumpSession.Phase.VERIFIED, session.snapshot()!!.reservation!!.phase)
        assertFalse(transport.hasUnresolvedWrite())
        assertTrue(callbacks.last() is YpsoWriteOutcome.ProvenRejected)
    }

    @Test
    fun `unknown recovery probe keeps the same write blocked for later evidence`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(1)))
        deadline!!.run()

        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.UNKNOWN,
                null,
                evidenceHash,
                "bounded status probe could not observe selector state",
            ),
        )
        assertTrue(transport.hasUnresolvedWrite())
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
        assertEquals(
            evidenceHash,
            session
                .snapshot()!!
                .writeEvidence
                .single()
                .evidenceHash,
        )
        assertNull(
            session
                .snapshot()!!
                .writeEvidence
                .single()
                .resolution,
        )

        coordinator.reconcile(
            "selector-1",
            owner,
            YpsoBenchWriteCoordinator.Reconciliation(
                YpsoSemanticEvidence.ACCEPTED,
                PumpSession.WriteResolution.ACCEPTED,
                "ef".repeat(32),
                "later selector readback matched",
            ),
        )
        assertFalse(transport.hasUnresolvedWrite())
        assertEquals(PumpSession.Phase.VERIFIED, session.snapshot()!!.reservation!!.phase)
        assertEquals(2, session.snapshot()!!.writeEvidence.size)
    }

    @Test
    fun `process restart exposes durable write identity and accepts later measured evidence`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(9)))
        deadline!!.run()

        val restarted = PumpSession(store)
        val nextToken = restarted.open("pump", key)
        val nextCoordinator =
            YpsoBenchWriteCoordinator(
                restarted,
                crypto,
                YpsoCommandReadiness(),
                YpsoSerializedWriteTransport({ _, _ -> }, {}),
            )
        val pending = nextCoordinator.pendingWrite()!!
        assertEquals("selector-1", pending.writeId)
        assertEquals(YpsoWritePolicy.EVENT_INDEX_UUID, pending.characteristic)
        assertEquals(YpsoRemoteWrite.HISTORY_SELECTOR, pending.purpose)

        val outcome =
            nextCoordinator.reconcilePersisted(
                YpsoBenchWriteCoordinator.Owner(gatt, "next-connection", nextToken),
                pending.writeId,
                YpsoBenchWriteCoordinator.Reconciliation(
                    YpsoSemanticEvidence.ACCEPTED,
                    PumpSession.WriteResolution.ACCEPTED,
                    evidenceHash,
                    "post-restart readback matched selector 9",
                ),
            )

        assertTrue(outcome is YpsoWriteOutcome.Verified)
        assertEquals(PumpSession.Phase.VERIFIED, restarted.snapshot()!!.reservation!!.phase)
        assertEquals(
            evidenceHash,
            restarted
                .snapshot()!!
                .writeEvidence
                .single()
                .evidenceHash,
        )
    }

    @Test
    fun `offline measured evidence resolves ambiguity after the live owner is released`() {
        makeReady()
        assertTrue(write(YpsoGlb.encode(9)))
        repeat(15) {
            if (callbacks.isEmpty()) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        coordinator.ownerDisconnected(gatt, "close ambiguous live owner")

        val restarted = PumpSession(store)
        val nextToken = restarted.open("pump", key)
        val nextCoordinator =
            YpsoBenchWriteCoordinator(
                restarted,
                crypto,
                YpsoCommandReadiness(),
                YpsoSerializedWriteTransport({ _, _ -> }, {}),
            )
        val outcome =
            nextCoordinator.reconcilePersisted(
                YpsoBenchWriteCoordinator.Owner(Any(), "reviewed-offline-evidence", nextToken),
                "selector-1",
                YpsoBenchWriteCoordinator.Reconciliation(
                    YpsoSemanticEvidence.ACCEPTED,
                    PumpSession.WriteResolution.ACCEPTED,
                    evidenceHash,
                    "reviewed target trace and readback matched selector 9",
                ),
            )

        assertTrue(outcome is YpsoWriteOutcome.Verified)
        assertEquals(PumpSession.Phase.VERIFIED, restarted.snapshot()!!.reservation!!.phase)
        assertEquals(
            evidenceHash,
            restarted
                .snapshot()!!
                .writeEvidence
                .single()
                .evidenceHash,
        )
    }

    @Test
    fun `offline recovery rolls back a persisted pre-dispatch reservation without guessing effect`() {
        val transaction = session.begin(token)
        session.reserve(
            token,
            transaction,
            PumpSession.WriteIntent(
                "reserved-only",
                YpsoWritePolicy.EVENT_INDEX_UUID.toString(),
                YpsoRemoteWrite.HISTORY_SELECTOR.name,
                "ab".repeat(32),
            ),
        )
        session.quiesce()

        val restarted = PumpSession(store)
        val nextToken = restarted.open("pump", key)
        val nextCoordinator =
            YpsoBenchWriteCoordinator(
                restarted,
                crypto,
                YpsoCommandReadiness(),
                YpsoSerializedWriteTransport({ _, _ -> }, {}),
            )
        val outcome =
            nextCoordinator.reconcilePersisted(
                YpsoBenchWriteCoordinator.Owner(Any(), "offline-pre-dispatch-recovery", nextToken),
                "reserved-only",
                YpsoBenchWriteCoordinator.Reconciliation(
                    YpsoSemanticEvidence.REJECTED,
                    PumpSession.WriteResolution.REJECTED_COUNTER_NOT_CONSUMED,
                    evidenceHash,
                    "journal remained RESERVED so dispatch boundary was never committed",
                ),
            )

        assertTrue(outcome is YpsoWriteOutcome.ProvenRejected)
        assertEquals(42, restarted.snapshot()!!.write)
        assertNull(restarted.snapshot()!!.reservation)
        assertEquals(
            evidenceHash,
            restarted
                .snapshot()!!
                .writeEvidence
                .single()
                .evidenceHash,
        )
    }

    @Test
    fun `authenticated observed new epoch permits exactly one counter one bootstrap selector`() {
        session.quiesce()
        store.saved =
            store.saved.copy(
                records =
                    store.saved.records.map {
                        it.copy(
                            reboot = 9,
                            read = 1,
                            write = null,
                            reservation = null,
                            benchNewEpochBootstrapReference =
                                PumpSession.BootstrapReference(
                                    reboot = 8,
                                    read = 100,
                                    characteristic = YpsoWritePolicy.EVENT_INDEX_UUID.toString(),
                                    payloadHash = java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(YpsoGlb.encode(17))
                                        .joinToString("") { "%02x".format(it) },
                                ),
                            writeBootstrapState = PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH,
                            benchNewEpochBootstrapAttempted = false,
                            benchStrictNextAccepted = false,
                            benchForwardGapAttempted = false,
                        )
                    },
            )
        val bootstrapSession = PumpSession(store)
        val bootstrapToken = bootstrapSession.open("pump", key)
        val bootstrapReadiness = YpsoCommandReadiness()
        val bootstrapOwner = YpsoBenchWriteCoordinator.Owner(gatt, "bootstrap-connection", bootstrapToken)
        bootstrapReadiness.connected(bootstrapOwner.readinessOwner())
        bootstrapReadiness.authenticated(bootstrapOwner.readinessOwner())
        bootstrapReadiness.readVerified(bootstrapOwner.readinessOwner())
        bootstrapReadiness.requiredSetupVerified(bootstrapOwner.readinessOwner())
        val bootstrapFrames = mutableListOf<ByteArray>()
        val bootstrapCallbacks = mutableListOf<YpsoWriteOutcome>()
        val bootstrapTransport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val bootstrapCoordinator = YpsoBenchWriteCoordinator(bootstrapSession, crypto, bootstrapReadiness, bootstrapTransport)

        assertTrue(
            bootstrapCoordinator.writeSelector(
                writeId = "bootstrap-selector",
                owner = bootstrapOwner,
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.EVENT_INDEX_UUID,
                plaintext = YpsoGlb.encode(18),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                mode = YpsoBenchWriteCoordinator.BenchWriteMode.NEW_EPOCH_BOOTSTRAP,
                dispatch = { bootstrapFrames += it.copyOf(); true },
                onOutcome = bootstrapCallbacks::add,
            ),
        )
        while (bootstrapCallbacks.isEmpty()) {
            bootstrapTransport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        }
        val message = crypto.decrypt(YpsoFraming.parseMultiFrameRead(bootstrapFrames), key)
        assertEquals(9, message.reboot)
        assertEquals(1L, message.counter)
        assertEquals(PumpSession.WriteCandidate.BENCH_NEW_EPOCH_BOOTSTRAP_SELECTOR, bootstrapSession.snapshot()!!.reservation!!.candidate)
        assertTrue(bootstrapSession.snapshot()!!.benchNewEpochBootstrapAttempted)
    }

    @Test
    fun `new epoch bootstrap without a durable reference fails readiness before reservation`() {
        session.quiesce()
        store.saved =
            store.saved.copy(
                records =
                    store.saved.records.map {
                        it.copy(
                            reboot = 9,
                            read = 1,
                            write = null,
                            reservation = null,
                            benchNewEpochBootstrapReference = null,
                            writeBootstrapState = PumpSession.WriteBootstrapState.OBSERVED_NEW_EPOCH,
                            benchNewEpochBootstrapAttempted = false,
                            benchStrictNextAccepted = false,
                            benchForwardGapAttempted = false,
                        )
                    },
            )
        val bootstrapSession = PumpSession(store)
        val bootstrapToken = bootstrapSession.open("pump", key)
        val bootstrapReadiness = YpsoCommandReadiness()
        val bootstrapOwner = YpsoBenchWriteCoordinator.Owner(gatt, "bootstrap-connection", bootstrapToken)
        bootstrapReadiness.connected(bootstrapOwner.readinessOwner())
        bootstrapReadiness.authenticated(bootstrapOwner.readinessOwner())
        bootstrapReadiness.readVerified(bootstrapOwner.readinessOwner())
        bootstrapReadiness.requiredSetupVerified(bootstrapOwner.readinessOwner())
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val bootstrapCoordinator =
            YpsoBenchWriteCoordinator(
                bootstrapSession,
                crypto,
                bootstrapReadiness,
                YpsoSerializedWriteTransport({ _, _ -> }, {}),
            )

        assertFalse(
            bootstrapCoordinator.writeSelector(
                writeId = "bootstrap-selector",
                owner = bootstrapOwner,
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.EVENT_INDEX_UUID,
                plaintext = YpsoGlb.encode(18),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                mode = YpsoBenchWriteCoordinator.BenchWriteMode.NEW_EPOCH_BOOTSTRAP,
                dispatch = { error("dispatch must not run") },
                onOutcome = outcomes::add,
            ),
        )
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertNull(bootstrapSession.snapshot()!!.reservation)
        assertFalse(bootstrapSession.snapshot()!!.benchNewEpochBootstrapAttempted)
    }

    @Test
    fun `alarm selector requires durable current epoch count minus one`() {
        makeReady()

        assertFalse(
            coordinator.writeSelector(
                writeId = "alarm-missing-count",
                owner = owner,
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.ALARM_INDEX_UUID,
                plaintext = YpsoGlb.encode(199),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { error("dispatch must not run") },
                onOutcome = callbacks::add,
            ),
        )
        assertTrue(callbacks.single() is YpsoWriteOutcome.NotSent)
        assertNull(session.snapshot()!!.reservation)

        callbacks.clear()
        session.recordBenchHistoryCounts(
            token,
            listOf(
                PumpSession.HistoryCountEvidence(
                    PumpSession.HistoryFamily.ALARM,
                    reboot = 8,
                    read = 100,
                    count = 200,
                    characteristic = YpsoWritePolicy.ALARM_COUNT_UUID.toString(),
                    payloadHash = "ab".repeat(32),
                ),
            ),
        )
        assertFalse(
            coordinator.writeSelector(
                writeId = "alarm-wrong-index",
                owner = owner,
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.ALARM_INDEX_UUID,
                plaintext = YpsoGlb.encode(198),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { error("dispatch must not run") },
                onOutcome = callbacks::add,
            ),
        )
        assertTrue(callbacks.single() is YpsoWriteOutcome.NotSent)
        assertNull(session.snapshot()!!.reservation)

        callbacks.clear()
        assertFalse(
            coordinator.writeSelector(
                writeId = "alarm-missing-state",
                owner = owner,
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.ALARM_INDEX_UUID,
                plaintext = YpsoGlb.encode(199),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { error("dispatch must not run") },
                onOutcome = callbacks::add,
            ),
        )
        assertTrue(callbacks.single() is YpsoWriteOutcome.NotSent)
        assertNull(session.snapshot()!!.reservation)

        session.recordBenchHistorySelectorState(
            token,
            PumpSession.HistorySelectorState(
                PumpSession.HistoryFamily.ALARM,
                reboot = 8,
                read = 100,
                index = 150,
                characteristic = "669a0c20-0008-969e-e211-fcbeca3b7bc5",
                payloadHash = "cd".repeat(32),
            ),
        )
        callbacks.clear()
        assertTrue(
            coordinator.writeSelector(
                writeId = "alarm-bound-count",
                owner = owner,
                category = YpsoRemoteWrite.HISTORY_SELECTOR,
                characteristic = YpsoWritePolicy.ALARM_INDEX_UUID,
                plaintext = YpsoGlb.encode(199),
                firmware = "V05.00.52",
                deadlineMs = 8_000,
                dispatch = { true },
                onOutcome = callbacks::add,
            ),
        )
        assertEquals(43, session.snapshot()!!.reservation!!.counter)
        assertEquals(150, session.snapshot()!!.reservation!!.historyBinding!!.selectedBefore.index)
        assertEquals(199, session.snapshot()!!.reservation!!.historyBinding!!.writeIndex)
        assertTrue(session.snapshot()!!.benchHistorySelectorStates.none { it.family == PumpSession.HistoryFamily.ALARM })
    }

    private fun makeReady() {
        val readyOwner = owner.readinessOwner()
        readiness.connected(readyOwner)
        readiness.authenticated(readyOwner)
        readiness.readVerified(readyOwner)
        readiness.requiredSetupVerified(readyOwner)
    }

    private fun write(
        payload: ByteArray,
        mode: YpsoBenchWriteCoordinator.BenchWriteMode = YpsoBenchWriteCoordinator.BenchWriteMode.STRICT_NEXT,
        writeId: String = "selector-1",
        dispatch: (ByteArray) -> Boolean = { true },
    ): Boolean =
        coordinator.writeSelector(
            writeId = writeId,
            owner = owner,
            category = YpsoRemoteWrite.HISTORY_SELECTOR,
            characteristic = YpsoWritePolicy.EVENT_INDEX_UUID,
            plaintext = payload,
            firmware = "V05.00.52",
            deadlineMs = 8_000,
            mode = mode,
            dispatch = { frame ->
                frames += frame.copyOf()
                dispatch(frame)
            },
            onOutcome = callbacks::add,
        )

    private fun assertThrowsUnresolved(block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue(thrown is IllegalStateException && thrown.message == "Unresolved write")
    }

    private fun assertFailsOwner(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private class MemoryStore : PumpSession.Store {
        var saved = PumpSession.State()
        var commits = 0

        override fun load(): PumpSession.State = saved

        override fun commit(state: PumpSession.State) {
            commits++
            saved = state
        }
    }
}
