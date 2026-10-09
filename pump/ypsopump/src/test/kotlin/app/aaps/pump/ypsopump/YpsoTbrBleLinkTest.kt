package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.tbr.YpsoTbrBleLink
import app.aaps.pump.ypsopump.tbr.YpsoTbrWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class YpsoTbrBleLinkTest {

    private val manager = mock<YpsoBleManager>()

    @Test
    fun `uncertain command retains first dispatch time across counter retries`() {
        var clock = 1_000_000L
        val persisted = mutableListOf<Long>()
        doAnswer { call ->
            val beforeDispatch = call.getArgument<(PumpSession.Reservation) -> Unit>(3)
            beforeDispatch(mock())
            clock += 100
            beforeDispatch(mock())
            call.getArgument<(YpsoWriteOutcome, YpsoBleManager.TbrCommandOwner?) -> Unit>(4)(
                YpsoWriteOutcome.PossiblyApplied("write", 500, failure()), null
            )
            null
        }.whenever(manager).writeTbr(any(), any(), any(), any(), any())

        val evidence = YpsoTbrBleLink(manager, { false }, { clock })
            .command(150, 30, { false }, { persisted += it })

        assertEquals(listOf(1_000_000L, 1_000_100L), persisted)
        assertEquals(1_000_000L, evidence.dispatchedAt)
        assertEquals(YpsoTbrWriteResult.Uncertain("test failure"), evidence.result)
        assertNull(evidence.acknowledgedAt)
        assertNull(evidence.after)
    }

    @Test
    fun `unsent command has no fabricated dispatch time`() {
        doAnswer { call ->
            call.getArgument<(YpsoWriteOutcome, YpsoBleManager.TbrCommandOwner?) -> Unit>(4)(
                YpsoWriteOutcome.NotSent("write", null, failure()), null
            )
            null
        }.whenever(manager).writeTbr(any(), any(), any(), any(), any())

        val evidence = YpsoTbrBleLink(manager, { false }, { 1_000_000L })
            .command(150, 30, { false }, { error("Unsent command must not be journalled as dispatched") })

        assertNull(evidence.dispatchedAt)
        assertNull(evidence.acknowledgedAt)
        assertNull(evidence.after)
    }

    private fun failure() = YpsoWriteFailure(
        YpsoWriteFailure.Layer.DISPATCH, UUID.randomUUID(), null, detail = "test failure"
    )
}
