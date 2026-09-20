package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBolusWriteCoordinator
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusRequestValidator
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoBolusWriteCoordinatorTest {
    private class Store(var state: PumpSession.State = PumpSession.State()) : PumpSession.Store {
        override fun load() = state
        override fun commit(state: PumpSession.State) { this.state = state }
    }

    @Test
    fun `unready session rejects before dispatch or reservation`() {
        val key = ByteArray(32) { 1 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 10, write = null, serial = "10000001", keyHex = key.joinToString("") { "%02x".format(it) },
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val token = session.open("pump", key)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val coordinator = coordinator(session)

        assertFalse(
            coordinator.start(
                "bolus-1",
                YpsoBolusWriteCoordinator.Owner(Any(), "connection", token),
                YpsoBolusRequestValidator.validate(1.0, YpsoBolusTreatment.NORMAL, 30.0),
                null,
                5_000,
                beforeDispatch = {},
                dispatch = { error("dispatch") },
                onOutcome = outcomes::add,
            ),
        )
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertEquals(null, session.snapshot()?.reservation)
    }

    @Test
    fun `lower bound recovery state cannot dispatch therapy`() {
        val key = ByteArray(32) { 7 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 10, write = 9_035, serial = "10000001", keyHex = key.joinToString("") { "%02x".format(it) },
            writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
            lowerBoundRecoveryReboot = 21,
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        val token = session.open("pump", key)
        val outcomes = mutableListOf<YpsoWriteOutcome>()

        assertFalse(
            coordinator(session).start(
                "bolus-recovery-block",
                YpsoBolusWriteCoordinator.Owner(Any(), "connection", token),
                YpsoBolusRequestValidator.validate(1.0, YpsoBolusTreatment.NORMAL, 30.0),
                null,
                5_000,
                beforeDispatch = { error("journal") },
                dispatch = { error("dispatch") },
                onOutcome = outcomes::add,
            ),
        )
        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertNull(session.snapshot()!!.reservation)
    }

    @Test
    fun `start uses one durable therapy reservation and encrypted CRC framed exact dose`() {
        val fixture = readyFixture()
        val callbacks = mutableListOf<Runnable>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val frames = mutableListOf<ByteArray>()
        val transport = YpsoSerializedWriteTransport({ runnable, _ -> callbacks += runnable }, { callbacks -= it })
        val coordinator = YpsoBolusWriteCoordinator(fixture.session, SessionCrypto(), transport)
        val owner = YpsoBolusWriteCoordinator.Owner(fixture.gatt, "connection", fixture.token)

        var journaledCounter: Long? = null
        assertTrue(
            coordinator.start(
                "bolus-1",
                owner,
                YpsoBolusRequestValidator.validate(1.2, YpsoBolusTreatment.NORMAL, 30.0),
                "V05.00.52",
                5_000,
                { journaledCounter = it.counter },
                { frames += it; true },
                outcomes::add,
            ),
        )
        assertEquals(4810, journaledCounter)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, fixture.session.snapshot()?.reservation?.phase)
        assertEquals("THERAPY_COMMAND", fixture.session.snapshot()?.reservation?.purpose)
        assertEquals(4810, fixture.session.snapshot()?.reservation?.counter)
        val expectedPlaintext = YpsoCrc.appendCrc(BolusCommand(1.2).encode())
        assertEquals(sha256(expectedPlaintext), fixture.session.snapshot()?.reservation?.payloadHash)

        repeat(YpsoFraming.chunkPayload(SessionCrypto().encrypt(expectedPlaintext, fixture.key, 21, 4810)).size) {
            transport.onCharacteristicWrite(fixture.gatt, app.aaps.pump.ypsopump.ble.YpsoWritePolicy.BOLUS_START_STOP_UUID, 0)
        }
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(PumpSession.Phase.ACKED, fixture.session.snapshot()?.reservation?.phase)
    }

    @Test
    fun `slow block cancellation reserves and encrypts the extended cancel payload`() {
        val fixture = readyFixture()
        val callbacks = mutableListOf<Runnable>()
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val frames = mutableListOf<ByteArray>()
        val transport = YpsoSerializedWriteTransport({ runnable, _ -> callbacks += runnable }, { callbacks -= it })
        val coordinator = YpsoBolusWriteCoordinator(fixture.session, SessionCrypto(), transport)
        val owner = YpsoBolusWriteCoordinator.Owner(fixture.gatt, "connection", fixture.token)

        var journaledCounter: Long? = null
        assertTrue(
            coordinator.cancel(
                "cancel-1",
                owner,
                YpsoBolusBlock.SLOW,
                "V05.00.52",
                5_000,
                { journaledCounter = it.counter },
                { frames += it; true },
                outcomes::add,
            ),
        )
        assertEquals(4810, journaledCounter)
        val expectedPlaintext = YpsoCrc.appendCrc(BolusCommand.cancelPayload(extended = true))
        assertEquals(sha256(expectedPlaintext), fixture.session.snapshot()?.reservation?.payloadHash)

        repeat(YpsoFraming.chunkPayload(SessionCrypto().encrypt(expectedPlaintext, fixture.key, 21, 4810)).size) {
            transport.onCharacteristicWrite(fixture.gatt, app.aaps.pump.ypsopump.ble.YpsoWritePolicy.BOLUS_START_STOP_UUID, 0)
        }
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
    }

    private data class Fixture(val session: PumpSession, val token: PumpSession.Token, val key: ByteArray, val gatt: Any)

    private fun readyFixture(): Fixture {
        val key = ByteArray(32) { 2 }
        val record = PumpSession.Record(
            pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = 21,
            read = 100, write = 4809, serial = "10000001", keyHex = key.joinToString("") { "%02x".format(it) },
            writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
        )
        val session = PumpSession(Store(PumpSession.State(records = listOf(record), activeGeneration = "generation")))
        return Fixture(session, session.open("pump", key), key, Any())
    }

    private fun coordinator(session: PumpSession) = YpsoBolusWriteCoordinator(
        session,
        SessionCrypto(),
        YpsoSerializedWriteTransport({ _, _ -> }, {}),
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
