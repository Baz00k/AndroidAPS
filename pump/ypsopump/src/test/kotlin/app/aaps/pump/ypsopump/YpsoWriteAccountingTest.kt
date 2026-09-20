package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YpsoWriteAccountingTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `duplicate live write ID is rejected before durable state changes`() {
        val store = MemoryStore()
        PumpSession(store).provisionReadBaseline("pump", key, reboot = 8, read = 100)
        store.saved = store.saved.copy(
            records = store.saved.records.map {
                it.copy(write = 42, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED)
            },
        )
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val accounting = YpsoWriteAccounting(session, SessionCrypto(), transport)
        val owner = YpsoWriteAccounting.Owner(Any(), "connection", token)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        fun request() = YpsoWriteAccounting.Request(
            writeId = "selector-1",
            owner = owner,
            category = YpsoRemoteWrite.SETTINGS_SELECTOR,
            characteristic = YpsoWritePolicy.SETTING_ID_UUID,
            plaintext = YpsoGlb.encode(1),
            firmware = "V05.00.52",
            deadlineMs = 8_000,
            dispatch = { true },
            onOutcome = outcomes::add,
        )

        assertTrue(accounting.execute(request()))
        val reservation = session.snapshot()!!.reservation
        val commits = store.commits

        assertFalse(accounting.execute(request()))

        val rejection = outcomes.single() as YpsoWriteOutcome.NotSent
        assertEquals(YpsoWriteFailure.Layer.SESSION, rejection.failure.layer)
        assertEquals("write ID is already owned", rejection.failure.detail)
        assertEquals(reservation, session.snapshot()!!.reservation)
        assertEquals(commits, store.commits)
    }

    /**
     * The unknown-floor contract end to end: allocate from zero, advance only on pump-confirmed
     * 139, redispatches happen inside the write lifecycle, and acceptance establishes the floor.
     */
    @Test
    fun `unknown floor reconciles automatically until a counter is accepted`() {
        val store = MemoryStore()
        store.saved = PumpSession.State(
            availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
        )
        val session = PumpSession(store)
        session.provisionReadBaseline("pump", key, reboot = 8, read = 100)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in session.availability().causes)
        val token = session.open("pump", key)
        val gatt = Any()
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val accounting = YpsoWriteAccounting(session, SessionCrypto(), transport)
        val owner = YpsoWriteAccounting.Owner(gatt, "connection", token)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val frames = mutableListOf<ByteArray>()
        val dispatchedCounters = mutableListOf<Long>()
        val crypto = SessionCrypto()

        fun dispatchedCounter(): Long = crypto.decrypt(YpsoFraming.parseMultiFrameRead(frames), key).counter
        fun sendRemainingFrames() {
            repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
        }
        fun rejectWithCounterError() {
            // The terminal callback may synchronously start the next attempt; drop only this
            // attempt's frames first so the retried dispatch stays observable.
            frames.clear()
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 139)
        }

        assertTrue(
            accounting.execute(
                YpsoWriteAccounting.Request(
                    writeId = "selector-1",
                    owner = owner,
                    category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                    characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                    plaintext = YpsoGlb.encode(1),
                    firmware = "V05.00.52",
                    deadlineMs = 8_000,
                    beforeDispatch = { dispatchedCounters += it.counter },
                    dispatch = { frames += it.copyOf(); true },
                    onOutcome = outcomes::add,
                ),
            ),
        )

        sendRemainingFrames()
        assertEquals(1L, dispatchedCounter())
        rejectWithCounterError()
        sendRemainingFrames()
        assertEquals(2L, dispatchedCounter())
        rejectWithCounterError()
        sendRemainingFrames()
        assertEquals(4L, dispatchedCounter())
        rejectWithCounterError()
        sendRemainingFrames()
        assertEquals(8L, dispatchedCounter())
        assertTrue(outcomes.isEmpty())
        assertEquals(listOf(1L, 2L, 4L, 8L), dispatchedCounters)

        frames.clear()
        transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0)
        assertTrue(outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertTrue(
            accounting.reconcile(
                "selector-1",
                owner,
                YpsoSemanticEvidence.ACCEPTED,
                PumpSession.WriteResolution.ACCEPTED,
                "ab".repeat(32),
                "same-link selector identity read-back matched",
            ),
        )
        assertTrue(outcomes.last() is YpsoWriteOutcome.Verified)
        val record = session.snapshot()!!
        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, record.writeBootstrapState)
        assertEquals(8L, record.write)
        assertEquals(0, record.counterRecoveryExponent)
        assertFalse(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in session.availability().causes)
    }

    /**
     * The redispatch budget is finite. When it is spent the durable 139 still proves the command
     * was not applied: the reservation must be closed and the caller must see a proven rejection,
     * never a stranded possibly-applied write.
     */
    @Test
    fun `counter search exhaustion surfaces a proven rejection with a resolved reservation`() {
        val store = MemoryStore()
        val session = PumpSession(store)
        session.provisionReadBaseline("pump", key, reboot = 8, read = 100)
        val token = session.open("pump", key)
        val gatt = Any()
        val transport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val accounting = YpsoWriteAccounting(session, SessionCrypto(), transport)
        val owner = YpsoWriteAccounting.Owner(gatt, "connection", token)
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val frames = mutableListOf<ByteArray>()

        assertTrue(
            accounting.execute(
                YpsoWriteAccounting.Request(
                    writeId = "selector-exhausted",
                    owner = owner,
                    category = YpsoRemoteWrite.SETTINGS_SELECTOR,
                    characteristic = YpsoWritePolicy.SETTING_ID_UUID,
                    plaintext = YpsoGlb.encode(1),
                    firmware = "V05.00.52",
                    deadlineMs = 8_000,
                    dispatch = { frames += it.copyOf(); true },
                    onOutcome = outcomes::add,
                ),
            ),
        )
        repeat(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT + 1) {
            repeat(3) { transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 0) }
            frames.clear()
            transport.onCharacteristicWrite(gatt, YpsoWritePolicy.SETTING_ID_UUID, 139)
        }

        assertTrue(outcomes.single() is YpsoWriteOutcome.ProvenRejected)
        val record = session.snapshot()!!
        assertNull(record.reservation)
        assertEquals(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT, record.counterRecoveryExponent)
        assertEquals(PumpSession.MAX_COUNTER_RECOVERY_EXPONENT + 1, record.writeEvidence.size)
        assertEquals(1L shl PumpSession.MAX_COUNTER_RECOVERY_EXPONENT, record.write)
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
