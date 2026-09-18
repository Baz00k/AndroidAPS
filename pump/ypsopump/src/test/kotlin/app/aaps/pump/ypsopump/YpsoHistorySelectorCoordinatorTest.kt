package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoHistorySelectorCoordinator
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
