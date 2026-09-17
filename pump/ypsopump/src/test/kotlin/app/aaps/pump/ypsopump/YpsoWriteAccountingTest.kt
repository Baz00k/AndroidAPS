package app.aaps.pump.ypsopump.ble

import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
