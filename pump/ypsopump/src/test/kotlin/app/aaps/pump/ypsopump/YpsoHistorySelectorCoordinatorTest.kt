package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoHistorySelectorCoordinator
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoHistorySelectorCoordinatorTest {
    private class Store(var state: PumpSession.State) : PumpSession.Store {
        override fun load() = state
        override fun commit(state: PumpSession.State) { this.state = state }
    }

    @Test
    fun `event selection owns one strict-next durable write`() {
        val key = ByteArray(32) { 3 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 100, write = 4809, serial = "10000001", keyHex = key.toHex(),
            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val token = session.open("pump", key)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), transport)
        val owner = YpsoHistorySelectorCoordinator.Owner(Any(), "connection", token)
        val frames = mutableListOf<ByteArray>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()

        assertTrue(coordinator.select("history-0", owner, 0, null, 5_000, { frames += it; true }, outcomes::add))
        val reservation = requireNotNull(session.snapshot()?.reservation)
        assertEquals(4810, reservation.counter)
        assertEquals("HISTORY_SELECTOR", reservation.purpose)
        assertEquals(YpsoWritePolicy.EVENT_INDEX_UUID.toString(), reservation.characteristic)
        assertEquals(YpsoHistorySelectorCoordinator.sha256(YpsoGlb.encode(0)), reservation.payloadHash)
        assertTrue(frames.isNotEmpty())
    }

    @Test
    fun `unknown write floor blocks before dispatch`() {
        val key = ByteArray(32) { 4 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 100, write = null, serial = "10000001", keyHex = key.toHex(),
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val owner = YpsoHistorySelectorCoordinator.Owner(Any(), "connection", session.open("pump", key))
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), YpsoSerializedWriteTransport({ _, _ -> }, {}))

        assertFalse(coordinator.select("history-0", owner, 0, null, 5_000, { error("dispatch") }, outcomes::add))
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertEquals(null, session.snapshot()?.reservation)
    }

    @Test
    fun `durable lower bound allows only explicit history selector recovery`() {
        val key = ByteArray(32) { 5 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 100, write = 9_035, serial = "10000001", keyHex = key.toHex(),
            writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
            lowerBoundRecoveryReboot = 21,
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val owner = YpsoHistorySelectorCoordinator.Owner(Any(), "connection", session.open("pump", key))
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), YpsoSerializedWriteTransport({ _, _ -> }, {}))

        assertFalse(coordinator.select("ordinary-history", owner, 0, null, 5_000, { error("dispatch") }, outcomes::add))
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertTrue(coordinator.recoverLowerBound("recover-history", owner, 0, null, 5_000, { true }, outcomes::add))
        assertEquals(9_036, session.snapshot()!!.reservation!!.counter)
    }

    @Test
    fun `lower bound recovery becomes established only after exact semantic acceptance`() {
        val key = ByteArray(32) { 6 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 100, write = 9_035, serial = "10000001", keyHex = key.toHex(),
            writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
            lowerBoundRecoveryReboot = 21,
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val token = session.open("pump", key)
        val owner = YpsoHistorySelectorCoordinator.Owner(Any(), "connection", token)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), transport)
        val outcomes = mutableListOf<YpsoWriteOutcome>()

        assertTrue(coordinator.recoverLowerBound("recover-history", owner, 0, null, 5_000, { true }, outcomes::add))
        val reservation = session.snapshot()!!.reservation!!
        repeat(3) { transport.onCharacteristicWrite(owner.gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0) }
        transport.onCharacteristicWrite(owner.gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0)
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, session.snapshot()!!.writeBootstrapState)
        assertTrue(coordinator.reconcileLowerBoundAccepted("recover-history", owner, "ab".repeat(32), "exact selector read-back matched"))
        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, session.snapshot()!!.writeBootstrapState)
        assertEquals(PumpSession.Phase.VERIFIED, session.snapshot()!!.reservation!!.phase)
        assertEquals(reservation.counter, session.snapshot()!!.write)
    }

    @Test
    fun `only pump counter error advances durable lower bound search`() {
        val key = ByteArray(32) { 8 }
        val store = Store(
            PumpSession.State(
                records = listOf(
                    PumpSession.Record(
                        pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
                        read = 100, write = 9_035, serial = "10000001", keyHex = key.toHex(),
                        writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
            lowerBoundRecoveryReboot = 21,
                    ),
                ),
                activeGeneration = "generation",
                availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
            ),
        )
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), transport)

        fun run(id: String, status: Int): Pair<Long, YpsoWriteOutcome> {
            val gatt = Any()
            val frames = mutableListOf<ByteArray>()
            val outcomes = mutableListOf<YpsoWriteOutcome>()
            assertTrue(coordinator.recoverLowerBound(id, YpsoHistorySelectorCoordinator.Owner(gatt, id, token), 0, null, 8_000, { frames += it; true }, outcomes::add))
            repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0) }
            val counter = SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, status)
            coordinator.ownerDisconnected(gatt, "test teardown")
            return counter to outcomes.last()
        }

        val counterError = run("counter-error", 139)
        assertEquals(9_036, counterError.first)
        assertTrue(counterError.second is YpsoWriteOutcome.ProvenRejected)
        assertEquals(1, session.snapshot()!!.counterRecoveryExponent)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in session.availability().causes)

        val ambiguous = run("ambiguous", 133)
        assertEquals(9_037, ambiguous.first)
        assertTrue(ambiguous.second is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(1, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(9_037, session.snapshot()!!.write)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, session.snapshot()!!.writeBootstrapState)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in session.availability().causes)

        val restarted = PumpSession(store)
        val restartedToken = restarted.open("pump", key)
        val restartedTransport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val restartedCoordinator = YpsoHistorySelectorCoordinator(restarted, SessionCrypto(), restartedTransport)
        val restartedGatt = Any()
        val restartedFrames = mutableListOf<ByteArray>()
        assertTrue(
            restartedCoordinator.recoverLowerBound(
                "after-ambiguous-restart",
                YpsoHistorySelectorCoordinator.Owner(restartedGatt, "restart", restartedToken),
                0,
                null,
                8_000,
                { restartedFrames += it; true },
                {},
            ),
        )
        repeat(3) { restartedTransport.onCharacteristicWrite(restartedGatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0) }
        assertEquals(9_038, SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(restartedFrames), key).counter)
        assertEquals(2, restarted.snapshot()!!.writeEvidence.size)
        assertEquals(1, restarted.snapshot()!!.writeEvidence.count { it.resolution == null })
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
