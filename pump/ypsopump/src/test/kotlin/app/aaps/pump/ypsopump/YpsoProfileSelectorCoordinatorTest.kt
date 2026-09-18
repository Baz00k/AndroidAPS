package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoProfileSelectorCoordinator
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
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

class YpsoProfileSelectorCoordinatorTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    private class MemoryStore : PumpSession.Store {
        var saved = PumpSession.State()
        override fun load(): PumpSession.State = saved
        override fun commit(state: PumpSession.State) {
            saved = state
        }
    }

    @Test
    fun `interrupted selector recovers on reconnect without knowing whether pump consumed counter`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(write = 4_280, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
        })
        repeat(3) { interruption ->
            val session = PumpSession(store)
            val token = session.open("pump", key)
            val gatt = Any()
            val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
            val coordinator = YpsoProfileSelectorCoordinator(session, SessionCrypto(), transport)
            val frames = mutableListOf<ByteArray>()
            val outcomes = mutableListOf<YpsoWriteOutcome>()
            assertTrue(coordinator.write(
                "profile-reconnect-$interruption",
                YpsoProfileSelectorCoordinator.Owner(gatt, "connection-$interruption", token),
                14, "V05.00.52", 8_000, { frames.add(it.copyOf()) }, outcomes::add,
            ), "A lost final callback must not permanently block the next acquisition")
            while (frames.size < 4) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0)
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 133)
            assertTrue(outcomes.last() is YpsoWriteOutcome.PossiblyApplied)
            assertEquals(4_281L + interruption, SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter)
            assertEquals(interruption, session.snapshot()!!.writeEvidence.size)
            assertTrue(session.snapshot()!!.writeEvidence.all { it.resolution == null })
            coordinator.ownerDisconnected(gatt, "lost final callback")
        }
        assertEquals(4_283L, store.saved.records.single().write)
    }

    @Test
    fun `only pump counter error advances exponential recovery`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(write = 4_280, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
        })
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoProfileSelectorCoordinator(session, SessionCrypto(), transport)

        fun run(writeId: String, status: Int): Pair<Long, YpsoWriteOutcome> {
            val gatt = Any()
            val outcomes = mutableListOf<YpsoWriteOutcome>()
            assertTrue(coordinator.write(writeId, YpsoProfileSelectorCoordinator.Owner(gatt, writeId, token), 14, null, 8_000, { true }, outcomes::add))
            repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, status)
            coordinator.ownerDisconnected(gatt, "test teardown")
            return checkNotNull(outcomes.last().counter) to outcomes.last()
        }

        val counterError = run("counter-error", 139)
        assertEquals(4_281L, counterError.first)
        assertTrue(counterError.second is YpsoWriteOutcome.ProvenRejected)
        assertEquals(1, session.snapshot()!!.counterRecoveryExponent)

        val unrelated = run("unrelated-error", 133)
        assertEquals(4_282L, unrelated.first)
        assertTrue(unrelated.second is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(1, session.snapshot()!!.counterRecoveryExponent)

        val recovered = run("counter-error-2", 139)
        assertEquals(4_283L, recovered.first)
        assertEquals(2, session.snapshot()!!.counterRecoveryExponent)
    }

    @Test
    fun `unknown floor blocks before reservation encryption or dispatch`() {
        val store = MemoryStore()
        val session = PumpSession(store)
        session.provisionReadBaseline("pump", key, 21, 100)
        val token = session.open("pump", key)
        val frames = mutableListOf<ByteArray>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = YpsoProfileSelectorCoordinator(
            session,
            SessionCrypto(),
            YpsoSerializedWriteTransport({ _, _ -> }, {}),
        )

        assertFalse(
            coordinator.write(
                "profile-1",
                YpsoProfileSelectorCoordinator.Owner(Any(), "connection", token),
                1,
                "V05.00.52",
                8_000,
                { frames.add(it); true },
                outcomes::add,
            ),
        )
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertTrue(frames.isEmpty())
        assertNull(session.snapshot()!!.reservation)
    }

    @Test
    fun `strict next selector remains blocking until exact readback reconciliation`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(write = 4_153, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
            },
        )
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val gatt = Any()
        val owner = YpsoProfileSelectorCoordinator.Owner(gatt, "connection", token)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val frames = mutableListOf<ByteArray>()
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val crypto = SessionCrypto()
        val coordinator = YpsoProfileSelectorCoordinator(session, crypto, transport)

        assertTrue(coordinator.write("profile-14", owner, 14, "V05.00.52", 8_000, { frames.add(it.copyOf()) }, outcomes::add))
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
        while (outcomes.isEmpty()) transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0)
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(PumpSession.Phase.ACKED, session.snapshot()!!.reservation!!.phase)

        val encrypted = YpsoFraming.parseMultiFrameRead(frames)
        val message = crypto.decrypt(encrypted, key)
        assertArrayEquals(YpsoGlb.encode(14), message.body)
        assertEquals(21, message.reboot)
        assertEquals(4_154, message.counter)

        val body = YpsoGlb.encode(45)
        assertTrue(
            coordinator.reconcileAccepted(
                "profile-14",
                owner,
                YpsoProfileSelectorCoordinator.sha256(body),
                "same-link exact-GLB selector identity read-back matched setting 14",
            ),
        )
        assertTrue(outcomes.last() is YpsoWriteOutcome.Verified)
        assertEquals(PumpSession.Phase.VERIFIED, session.snapshot()!!.reservation!!.phase)
        assertEquals(PumpSession.WriteResolution.ACCEPTED, session.snapshot()!!.writeEvidence.last().resolution)
    }

    @Test
    fun `ambiguous callback ownership cannot reconcile selector identity`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(write = 4_153, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
            },
        )
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val gatt = Any()
        val owner = YpsoProfileSelectorCoordinator.Owner(gatt, "connection", token)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoProfileSelectorCoordinator(session, SessionCrypto(), transport)

        assertTrue(coordinator.write("profile-14", owner, 14, "V05.00.52", 8_000, { true }, {}))
        assertTrue(
            transport.markCallbackOwnershipAmbiguous(
                gatt,
                YpsoWritePolicy.SETTING_ID_UUID,
                0,
                "same-UUID callback could not be assigned",
            ),
        )

        assertFalse(
            coordinator.reconcileAccepted(
                "profile-14",
                owner,
                "ab".repeat(32),
                "same-link exact-GLB selector identity read-back matched setting 14",
            ),
        )
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
        assertNull(session.snapshot()!!.writeEvidence.last().resolution)
    }

    @Test
    fun `profile selector rejects settings outside active and schedule allowlist`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(write = 4_153, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
            },
        )
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = YpsoProfileSelectorCoordinator(
            session,
            SessionCrypto(),
            YpsoSerializedWriteTransport({ _, _ -> }, {}),
        )

        assertFalse(
            coordinator.write(
                "profile-13",
                YpsoProfileSelectorCoordinator.Owner(Any(), "connection", token),
                13,
                "V05.00.52",
                8_000,
                { true },
                outcomes::add,
            ),
        )
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertNull(session.snapshot()!!.reservation)
    }
}
