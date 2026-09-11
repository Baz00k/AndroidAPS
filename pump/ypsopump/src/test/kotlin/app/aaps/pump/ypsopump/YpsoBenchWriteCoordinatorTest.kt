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
        store.saved = store.saved.copy(records = store.saved.records.map { it.copy(write = 42) })
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

    private fun makeReady() {
        val readyOwner = owner.readinessOwner()
        readiness.connected(readyOwner)
        readiness.authenticated(readyOwner)
        readiness.readVerified(readyOwner)
        readiness.requiredSetupVerified(readyOwner)
    }

    private fun write(
        payload: ByteArray,
        dispatch: (ByteArray) -> Boolean = { true },
    ): Boolean =
        coordinator.writeSelector(
            writeId = "selector-1",
            owner = owner,
            category = YpsoRemoteWrite.HISTORY_SELECTOR,
            characteristic = YpsoWritePolicy.EVENT_INDEX_UUID,
            plaintext = payload,
            firmware = "V05.00.52",
            deadlineMs = 8_000,
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
