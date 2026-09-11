package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoSemanticEvidence
import app.aaps.pump.ypsopump.ble.YpsoSerializedWriteTransport
import app.aaps.pump.ypsopump.ble.YpsoWriteBehavior
import app.aaps.pump.ypsopump.ble.YpsoWriteBehaviorRecorder
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class YpsoSerializedWriteTransportTest {
    private val characteristic: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecc3b7bc5")
    private val gatt = Any()
    private val callbacks = mutableListOf<YpsoWriteOutcome>()
    private val dispatches = mutableListOf<ByteArray>()
    private val events = mutableListOf<YpsoWriteBehavior>()
    private var deadline: Runnable? = null
    private val transport =
        YpsoSerializedWriteTransport(
            scheduleDeadline = { runnable, _ -> deadline = runnable },
            cancelDeadline = { if (deadline === it) deadline = null },
            recorder = YpsoWriteBehaviorRecorder(events::add),
        )

    @Test
    fun `first local dispatch refusal is typed not sent and clears ownership`() {
        start(frames = 3) { false }

        val result = callbacks.single() as YpsoWriteOutcome.NotSent
        assertEquals(YpsoWriteFailure.Layer.DISPATCH, result.failure.layer)
        assertEquals(1, result.failure.frame)
        assertFalse(transport.hasUnresolvedWrite())
    }

    @Test
    fun `first callback numeric failure remains uncertain with full provenance`() {
        start()
        transport.onCharacteristicWrite(gatt, characteristic, 138)

        val result = callbacks.single() as YpsoWriteOutcome.PossiblyApplied
        assertEquals(138, result.failure.code)
        assertEquals(characteristic, result.failure.characteristic)
        assertEquals("V05.00.52", result.failure.firmware)
        assertEquals(YpsoWriteFailure.Layer.GATT_CALLBACK, result.failure.layer)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `unclassified first callback failure is possibly applied rather than guessed rejected`() {
        start()
        transport.onCharacteristicWrite(gatt, characteristic, 133)

        val result = callbacks.single() as YpsoWriteOutcome.PossiblyApplied
        assertEquals(133, result.failure.code)
        assertEquals(YpsoWriteFailure.Layer.GATT_CALLBACK, result.failure.layer)
    }

    @Test
    fun `later fragment rejection is possibly applied and never dispatches another frame`() {
        start(frames = 3)
        transport.onCharacteristicWrite(gatt, characteristic, 0)
        transport.onCharacteristicWrite(gatt, characteristic, 141)

        assertEquals(2, dispatches.size)
        val result = callbacks.single() as YpsoWriteOutcome.PossiblyApplied
        assertEquals(2, result.failure.frame)
        assertEquals(141, result.failure.code)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `callback failure at every fragment position stops without another dispatch`() {
        for (failedFrame in 1..4) {
            callbacks.clear()
            dispatches.clear()
            events.clear()
            start(frames = 4)
            repeat(failedFrame - 1) { transport.onCharacteristicWrite(gatt, characteristic, 0) }

            transport.onCharacteristicWrite(gatt, characteristic, 139)

            val result = callbacks.single() as YpsoWriteOutcome.PossiblyApplied
            assertEquals(failedFrame, result.failure.frame)
            assertEquals(failedFrame, dispatches.size)
            transport.releaseOwner(gatt)
        }
    }

    @Test
    fun `inter-fragment duplicate cannot verify a write and eventually forces reconciliation`() {
        start(frames = 3)
        transport.onCharacteristicWrite(gatt, characteristic, 0) // frame 1
        transport.onCharacteristicWrite(gatt, characteristic, 0) // delayed duplicate, indistinguishable from frame 2
        transport.onCharacteristicWrite(gatt, characteristic, 0) // actual frame 2, indistinguishable from frame 3
        transport.onCharacteristicWrite(gatt, characteristic, 0) // actual frame 3 exposes the duplicate callback

        assertTrue(callbacks.first() is YpsoWriteOutcome.AcceptedUnverified)
        assertTrue(callbacks.last() is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `same UUID callback before dispatch immediately forces reconciliation`() {
        lateinit var earlyCallbackTransport: YpsoSerializedWriteTransport
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        earlyCallbackTransport =
            YpsoSerializedWriteTransport(
                scheduleDeadline = { _, _ ->
                    earlyCallbackTransport.onCharacteristicWrite(gatt, characteristic, 0)
                },
                cancelDeadline = {},
            )
        val request =
            request(frames = 2, onDispatch = { true }).copy(
                onOutcome = outcomes::add,
            )

        assertTrue(earlyCallbackTransport.start(request))

        assertTrue(dispatches.isEmpty())
        assertTrue(outcomes.single() is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(earlyCallbackTransport.hasUnresolvedWrite())
    }

    @Test
    fun `same UUID callback before platform dispatch returns cannot advance a fragment`() {
        lateinit var reentrantTransport: YpsoSerializedWriteTransport
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        var dispatchCount = 0
        reentrantTransport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val request =
            request(frames = 2, onDispatch = { true }).copy(
                dispatch = {
                    dispatchCount++
                    reentrantTransport.onCharacteristicWrite(gatt, characteristic, 0)
                    false
                },
                onOutcome = outcomes::add,
            )

        assertTrue(reentrantTransport.start(request))

        assertEquals(1, dispatchCount)
        assertTrue(outcomes.single() is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(reentrantTransport.hasUnresolvedWrite())
    }

    @Test
    fun `disconnect during platform dispatch is possibly applied rather than not sent`() {
        lateinit var reentrantTransport: YpsoSerializedWriteTransport
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        reentrantTransport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        val request =
            request(frames = 1, onDispatch = { true }).copy(
                dispatch = {
                    reentrantTransport.cancelOwner(gatt, "disconnect during platform dispatch")
                    true
                },
                onOutcome = outcomes::add,
            )

        assertTrue(reentrantTransport.start(request))

        assertTrue(outcomes.single() is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(reentrantTransport.hasUnresolvedWrite())
    }

    @Test
    fun `all fragment callbacks remain accepted unverified until semantic readback`() {
        start(frames = 3)
        repeat(3) { transport.onCharacteristicWrite(gatt, characteristic, 0) }

        assertEquals(listOf(YpsoWriteOutcome.AcceptedUnverified("write-1", 43)), callbacks)
        assertTrue(transport.hasUnresolvedWrite())

        transport.reconcile("write-1", YpsoSemanticEvidence.ACCEPTED, "history value index matched 17")

        assertEquals(2, callbacks.size)
        assertEquals("history value index matched 17", (callbacks.last() as YpsoWriteOutcome.Verified).evidence)
        assertFalse(transport.hasUnresolvedWrite())
    }

    @Test
    fun `duplicate same UUID callback has no invented operation identity and forces reconciliation`() {
        start(frames = 2)
        repeat(2) { transport.onCharacteristicWrite(gatt, characteristic, 0) }
        transport.onCharacteristicWrite(gatt, characteristic, 0)

        assertTrue(callbacks[0] is YpsoWriteOutcome.AcceptedUnverified)
        assertTrue(callbacks[1] is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(
            events
                .filterIsInstance<YpsoWriteBehavior.IgnoredCallback>()
                .single()
                .reason
                .contains("duplicate-same-uuid"),
        )

        transport.reconcile("write-1", YpsoSemanticEvidence.ACCEPTED, "selector readback matched")
        assertTrue(callbacks.last() is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `stale GATT and other characteristic callbacks cannot advance whole write`() {
        start(frames = 2)
        transport.onCharacteristicWrite(Any(), characteristic, 0)
        transport.onCharacteristicWrite(gatt, UUID.randomUUID(), 0)

        assertEquals(1, dispatches.size)
        assertTrue(callbacks.isEmpty())
        assertEquals(2, events.filterIsInstance<YpsoWriteBehavior.IgnoredCallback>().size)

        transport.onCharacteristicWrite(gatt, characteristic, 0)
        assertEquals(2, dispatches.size)
    }

    @Test
    fun `lost ACK deadline is possibly applied and late callback is inert`() {
        start()
        deadline!!.run()
        transport.onCharacteristicWrite(gatt, characteristic, 0)

        val result = callbacks.single() as YpsoWriteOutcome.PossiblyApplied
        assertEquals(YpsoWriteFailure.Layer.DEADLINE, result.failure.layer)
        assertTrue(transport.hasUnresolvedWrite())
    }

    @Test
    fun `disconnect after any dispatch is uncertain while idle cancellation is not sent`() {
        start()
        transport.cancelOwner(gatt, "link closed")
        assertTrue(callbacks.single() is YpsoWriteOutcome.PossiblyApplied)
        assertTrue(transport.hasUnresolvedWrite())
        transport.releaseOwner(gatt)
        assertFalse(transport.hasUnresolvedWrite())

        val waitingTransport = YpsoSerializedWriteTransport({ _, _ -> }, {})
        // A durable reservation, not this released transport object, blocks the next write.
        assertTrue(transport.start(request(onDispatch = { true })))
        assertTrue(waitingTransport.start(request(onDispatch = { true })))
    }

    @Test
    fun `disconnect after uncertainty does not publish a duplicate outcome`() {
        start()

        deadline!!.run()
        transport.cancelOwner(gatt, "close after deadline")
        transport.releaseOwner(gatt)

        assertEquals(1, callbacks.size)
        assertTrue(callbacks.single() is YpsoWriteOutcome.PossiblyApplied)
        assertFalse(transport.hasUnresolvedWrite())
    }

    @Test
    fun `behavior recorder contains ownership fragments callbacks and evidence`() {
        start(frames = 2)
        repeat(2) { transport.onCharacteristicWrite(gatt, characteristic, 0) }
        transport.reconcile("write-1", YpsoSemanticEvidence.REJECTED, "readback did not select requested index")

        val started = events.filterIsInstance<YpsoWriteBehavior.Started>().single()
        assertEquals("connection-7", started.connectionId)
        assertEquals("generation-9", started.generation)
        assertEquals(2, events.filterIsInstance<YpsoWriteBehavior.FrameDispatch>().size)
        assertEquals(2, events.filterIsInstance<YpsoWriteBehavior.Callback>().size)
        assertEquals(YpsoSemanticEvidence.REJECTED, events.filterIsInstance<YpsoWriteBehavior.Reconciled>().single().evidence)
    }

    private fun start(
        frames: Int = 1,
        onDispatch: (ByteArray) -> Boolean = { true },
    ) {
        assertTrue(transport.start(request(frames, onDispatch)))
    }

    private fun request(
        frames: Int = 1,
        onDispatch: (ByteArray) -> Boolean,
    ): YpsoSerializedWriteTransport.Request =
        YpsoSerializedWriteTransport.Request(
            writeId = "write-1",
            owner = YpsoSerializedWriteTransport.Owner(gatt, "connection-7", "generation-9"),
            category = YpsoRemoteWrite.HISTORY_SELECTOR,
            characteristic = characteristic,
            counter = 43,
            firmware = "V05.00.52",
            frames = (1..frames).map { byteArrayOf(it.toByte(), 0x55) },
            deadlineMs = 8_000,
            dispatch = { frame ->
                dispatches += frame.copyOf()
                onDispatch(frame)
            },
            onOutcome = callbacks::add,
        )
}
