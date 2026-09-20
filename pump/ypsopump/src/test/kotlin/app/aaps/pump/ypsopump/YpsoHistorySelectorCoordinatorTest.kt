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
    fun `unknown write floor reconciles history selection from zero`() {
        val key = ByteArray(32) { 4 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 100, write = null, serial = "10000001", keyHex = key.toHex(),
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val owner = YpsoHistorySelectorCoordinator.Owner(Any(), "connection", session.open("pump", key))
        val frames = mutableListOf<ByteArray>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), YpsoSerializedWriteTransport({ _, _ -> }, {}))

        assertTrue(coordinator.select("history-0", owner, 0, null, 5_000, { frames += it; true }, outcomes::add))
        val reservation = requireNotNull(session.snapshot()?.reservation)
        assertEquals(1L, reservation.counter)
        assertEquals(0L, reservation.priorWrite)
        assertTrue(frames.isNotEmpty())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `durable lower bound permits ordinary selection above the seeded floor`() {
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

        assertTrue(coordinator.select("ordinary-history", owner, 0, null, 5_000, { true }, outcomes::add))
        assertEquals(9_036L, session.snapshot()!!.reservation!!.counter)
        assertEquals(PumpSession.WriteCandidate.STANDARD, session.snapshot()!!.reservation!!.candidate)
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
    fun `pump counter error redispatches the recovery probe one exponential step higher`() {
        val key = ByteArray(32) { 8 }
        val store = lowerBoundStore(key)
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), transport)
        val gatt = Any()
        val frames = mutableListOf<ByteArray>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val owner = YpsoHistorySelectorCoordinator.Owner(gatt, "recovery", token)
        val crypto = SessionCrypto()

        assertTrue(coordinator.recoverLowerBound("counter-error", owner, 0, null, 8_000, { frames += it.copyOf(); true }, outcomes::add))
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0) }
        assertEquals(9_036L, crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter)
        frames.clear()
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 139)

        // The rejection is durable, nothing is reported to the caller, and the same logical selector
        // write is redispatched one exponential candidate above the retained position.
        assertTrue(outcomes.isEmpty())
        assertEquals(1, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(9_037L, session.snapshot()!!.reservation!!.counter)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, session.snapshot()!!.reservation!!.phase)
        repeat(4) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0) }
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(9_037L, crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter)
        assertTrue(coordinator.reconcileLowerBoundAccepted("counter-error", owner, "ab".repeat(32), "exact selector read-back matched"))
        assertTrue(outcomes.last() is YpsoWriteOutcome.Verified)
        assertEquals(0, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, session.snapshot()!!.writeBootstrapState)
        assertEquals(9_037L, session.snapshot()!!.write)
    }

    @Test
    fun `unrelated status neither advances nor retries an ambiguous recovery probe across restart`() {
        val key = ByteArray(32) { 9 }
        val store = lowerBoundStore(key)
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val coordinator = YpsoHistorySelectorCoordinator(session, SessionCrypto(), transport)
        val gatt = Any()
        val frames = mutableListOf<ByteArray>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val owner = YpsoHistorySelectorCoordinator.Owner(gatt, "ambiguous", token)

        assertTrue(coordinator.recoverLowerBound("ambiguous", owner, 0, null, 8_000, { frames += it; true }, outcomes::add))
        repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 0) }
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.EVENT_INDEX_UUID, 133)

        val ambiguous = outcomes.single()
        assertTrue(ambiguous is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(9_036L, ambiguous.counter)
        assertEquals(0, session.snapshot()!!.counterRecoveryExponent)
        assertEquals(9_036L, session.snapshot()!!.write)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, session.snapshot()!!.writeBootstrapState)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in session.availability().causes)

        // A restart cannot retire an ambiguous recovery probe: the unresolved reservation blocks.
        val restarted = PumpSession(store)
        val restartedToken = restarted.open("pump", key)
        val restartedCoordinator = YpsoHistorySelectorCoordinator(
            restarted,
            SessionCrypto(),
            YpsoSerializedWriteTransport({ _, _ -> }, {}),
        )
        val restartedGatt = Any()
        val restartedFrames = mutableListOf<ByteArray>()
        val restartedOutcomes = mutableListOf<YpsoWriteOutcome>()
        assertFalse(
            restartedCoordinator.recoverLowerBound(
                "after-ambiguous-restart",
                YpsoHistorySelectorCoordinator.Owner(restartedGatt, "restart", restartedToken),
                0,
                null,
                8_000,
                { restartedFrames += it; true },
                restartedOutcomes::add,
            ),
        )
        assertTrue(restartedOutcomes.single() is YpsoWriteOutcome.NotSent)
        assertTrue(restartedFrames.isEmpty())
        assertEquals(9_036L, restarted.snapshot()!!.write)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, restarted.snapshot()!!.reservation!!.phase)
        assertEquals(0, restarted.snapshot()!!.counterRecoveryExponent)
    }

    private fun lowerBoundStore(key: ByteArray) = Store(
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

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
