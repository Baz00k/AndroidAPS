package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoLinkOperation
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.bolus.YpsoBolusRequestValidator
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Therapy writes keep the link from dispatch until their read-back resolves them, and only their own owner releases it. */
class YpsoBleManagerTherapyLeaseTest {
    private val fixture = YpsoPumpFixture()
    private val manager = fixture.manager
    private val link = fixture.link
    private val evidence = "ab".repeat(32)

    /** A connected pump that acknowledges each therapy frame after the dispatch call returns, as Android does. */
    private fun connectTherapyPump(): FakeYpsoPump.Connection {
        val pump = fixture.connectWithProtocol()
        pump.expose(YpsoWritePolicy.BOLUS_START_STOP_UUID, YpsoWritePolicy.TBR_START_STOP_UUID)
        pump.onDescriptorWrite = { pump.ackDescriptor() }
        return pump
    }

    private fun acknowledge(pump: FakeYpsoPump.Connection, uuid: UUID) {
        var acknowledged = 0
        while (acknowledged < pump.writes.count { it.first == uuid }) {
            acknowledged++
            pump.ackWrite(uuid)
        }
    }

    private fun startBolus(pump: FakeYpsoPump.Connection, writeId: String): Pair<YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?> {
        val outcomes = mutableListOf<Pair<YpsoWriteOutcome, YpsoBleManager.BolusCommandOwner?>>()
        manager.startBolus(
            writeId, YpsoBolusRequestValidator.validate(1.2, YpsoBolusTreatment.NORMAL, 30.0), checkNotNull(manager.epoch()).key, {},
        ) { outcome, owner -> outcomes += outcome to owner }
        acknowledge(pump, YpsoWritePolicy.BOLUS_START_STOP_UUID)
        return outcomes.single()
    }

    private fun startTbr(pump: FakeYpsoPump.Connection, writeId: String): Pair<YpsoWriteOutcome, YpsoBleManager.TbrCommandOwner?> {
        val outcomes = mutableListOf<Pair<YpsoWriteOutcome, YpsoBleManager.TbrCommandOwner?>>()
        manager.writeTbr(writeId, 150, 30, {}) { outcome, owner -> outcomes += outcome to owner }
        acknowledge(pump, YpsoWritePolicy.TBR_START_STOP_UUID)
        return outcomes.single()
    }

    private fun assertOwned(operation: YpsoLinkOperation) {
        assertTrue(link.isActive(operation), "$operation no longer owns the link")
        assertNull(link.acquire(YpsoLinkOperation.STATUS), "another operation could take the link")
    }

    private fun assertReleased() {
        val next = link.acquire(YpsoLinkOperation.STATUS)
        assertTrue(next != null, "the link was not released")
        link.release(checkNotNull(next))
    }

    @Test
    fun `an acknowledged bolus keeps the link until status proves it`() {
        val pump = connectTherapyPump()

        val (outcome, owner) = startBolus(pump, "bolus")

        assertTrue(outcome is YpsoWriteOutcome.AcceptedUnverified, "$outcome")
        assertOwned(YpsoLinkOperation.BOLUS)
        assertTrue(manager.verifyBolusAccepted(checkNotNull(owner), "bolus", evidence, "status showed the bolus"))
        assertReleased()
    }

    @Test
    fun `an unresolved bolus releases the link`() {
        val pump = connectTherapyPump()
        val (_, owner) = startBolus(pump, "bolus")
        assertOwned(YpsoLinkOperation.BOLUS)

        manager.recordBolusUnresolved(checkNotNull(owner), "bolus", evidence, "status did not show the bolus")

        assertReleased()
    }

    @Test
    fun `a bolus owner from a replaced connection cannot release its successor`() {
        val (_, stale) = startBolus(connectTherapyPump(), "first")
        manager.disconnect()
        connectTherapyPump()
        // The unresolved first write blocks another therapy write, so the successor claims the lane directly.
        val successor = checkNotNull(link.acquire(YpsoLinkOperation.BOLUS))

        manager.recordBolusUnresolved(checkNotNull(stale), "first", evidence, "connection replaced")

        assertOwned(YpsoLinkOperation.BOLUS)
        link.release(successor)
        assertReleased()
    }

    @Test
    fun `an acknowledged TBR keeps the link until status proves it`() {
        val pump = connectTherapyPump()

        val (outcome, owner) = startTbr(pump, "tbr")

        assertTrue(outcome is YpsoWriteOutcome.AcceptedUnverified, "$outcome")
        assertOwned(YpsoLinkOperation.TBR)
        assertTrue(manager.verifyTbrAccepted(checkNotNull(owner), "tbr", evidence, "status showed the TBR"))
        assertReleased()
    }

    @Test
    fun `an unresolved TBR releases the link`() {
        val pump = connectTherapyPump()
        val (_, owner) = startTbr(pump, "tbr")
        assertOwned(YpsoLinkOperation.TBR)

        manager.recordTbrUnresolved(checkNotNull(owner), "tbr", evidence, "status did not show the TBR")

        assertReleased()
    }

    @Test
    fun `a TBR that was never sent releases the link at once`() {
        val pump = connectTherapyPump()
        pump.acceptWrites = false

        val (outcome, owner) = startTbr(pump, "tbr")

        assertTrue(outcome is YpsoWriteOutcome.NotSent, "$outcome")
        assertNull(owner)
        assertReleased()
    }

    @Test
    fun `a TBR owner from a replaced connection cannot release its successor`() {
        val (_, stale) = startTbr(connectTherapyPump(), "first")
        manager.disconnect()
        connectTherapyPump()
        val successor = checkNotNull(link.acquire(YpsoLinkOperation.TBR))

        manager.recordTbrUnresolved(checkNotNull(stale), "first", evidence, "connection replaced")

        assertOwned(YpsoLinkOperation.TBR)
        link.release(successor)
        assertReleased()
    }
}

