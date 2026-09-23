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
    fun `pump counter error automatically redispatches with the next exponential candidate`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(write = 4_280, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
        })
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val gatt = Any()
        val owner = YpsoProfileSelectorCoordinator.Owner(gatt, "connection", token)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoProfileSelectorCoordinator(session, SessionCrypto(), transport)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val frames = mutableListOf<ByteArray>()

        assertTrue(coordinator.write("counter-error", owner, 14, null, 8_000, { frames += it.copyOf(); true }, outcomes::add))
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        assertEquals(4_281L, SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter)
        frames.clear()
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 139)

        // The rejection is durable, nothing is reported to the caller, and the same logical write
        // is redispatched above the retained search position without caller involvement.
        assertEquals(1, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(4_282L, session.snapshot()!!.reservation!!.counter)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
        assertTrue(outcomes.isEmpty())
        repeat(4) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(4_282L, SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter)
        assertTrue(
            coordinator.reconcileAccepted(
                "counter-error",
                owner,
                "ab".repeat(32),
                "same-link exact-GLB selector identity read-back matched setting 14",
            ),
        )
        assertTrue(outcomes.last() is YpsoWriteOutcome.Verified)
        assertEquals(0, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(4_282L, session.snapshot()!!.write)
    }

    @Test
    fun `unrelated status neither advances the search nor retries`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(write = 4_280, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
        })
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val gatt = Any()
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoProfileSelectorCoordinator(session, SessionCrypto(), transport)
        val outcomes = mutableListOf<YpsoWriteOutcome>()

        assertTrue(coordinator.write("unrelated-error", YpsoProfileSelectorCoordinator.Owner(gatt, "unrelated-error", token), 14, null, 8_000, { true }, outcomes::add))
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 133)

        val outcome = outcomes.single()
        assertTrue(outcome is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(4_281L, outcome.counter)
        assertEquals(0, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
    }

    @Test
    fun `unknown floor reconciles the first selector from zero`() {
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

        assertTrue(
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
        val reservation = requireNotNull(session.snapshot()!!.reservation)
        assertEquals(1L, reservation.counter)
        assertEquals(0L, reservation.priorWrite)
        assertEquals(PumpSession.WriteCandidate.STANDARD, reservation.candidate)
        assertTrue(frames.isNotEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `lower bound recovery state permits profile selectors above the seeded floor`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, 21, 100)
        store.saved = store.saved.copy(records = store.saved.records.map {
            it.copy(
                write = 9_035,
                writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
                lowerBoundRecoveryReboot = 21,
            )
        })
        val session = PumpSession(store)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = YpsoProfileSelectorCoordinator(session, SessionCrypto(), YpsoSerializedWriteTransport({ _, _ -> }, {}))

        assertTrue(
            coordinator.write(
                "profile-recovery",
                YpsoProfileSelectorCoordinator.Owner(Any(), "connection", session.open("pump", key)),
                1,
                null,
                8_000,
                { true },
                outcomes::add,
            ),
        )
        assertEquals(9_036L, session.snapshot()!!.reservation!!.counter)
        assertEquals(PumpSession.WriteCandidate.STANDARD, session.snapshot()!!.reservation!!.candidate)
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
