package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoConnectionEpoch
import app.aaps.pump.ypsopump.ble.YpsoLinkLease
import app.aaps.pump.ypsopump.ble.YpsoSemanticEvidence
import app.aaps.pump.ypsopump.ble.YpsoWriteAccounting
import app.aaps.pump.ypsopump.ble.YpsoWriteFailure
import app.aaps.pump.ypsopump.ble.YpsoWriteLane
import app.aaps.pump.ypsopump.ble.YpsoWriteLane.Category
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.mock

/** Counter accounting, transport and link ownership of every write category, driven through [YpsoWriteLane]. */
internal class YpsoWriteLaneTest {
    private class Store(var state: PumpSession.State) : PumpSession.Store {
        var fail = false
        override fun load() = state
        override fun commit(state: PumpSession.State) {
            check(!fail) { "journal unavailable" }
            this.state = state
        }
    }

    /** The link a lane writes on: it records frames and released leases, and retires through the session. */
    private class Link(override var session: PumpSession?, var token: PumpSession.Token?) : YpsoWriteLane.Link {
        override val scheduler = ManualScheduler()
        val frames = mutableListOf<ByteArray>()
        val released = mutableListOf<YpsoLinkLease>()
        var acceptFrames = true
        var onRecoverInterruptedWrite: () -> Unit = {}

        override fun writeFrame(epoch: YpsoConnectionEpoch, lease: YpsoLinkLease, value: ByteArray): Boolean {
            frames += value.copyOf()
            return acceptFrames
        }

        override fun release(lease: YpsoLinkLease) {
            released += lease
        }

        override fun recoverInterruptedWrite() {
            onRecoverInterruptedWrite()
            session?.recoverInterruptedWrite(checkNotNull(token))
        }
    }

    private class Pump(
        val category: Category,
        val key: ByteArray = ByteArray(32) { 2 },
        reboot: Int? = 21,
        read: Long? = 100,
        write: Long? = 4_809,
        bootstrap: PumpSession.WriteBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED,
        availability: PumpSession.Availability = PumpSession.Availability(),
    ) {
        val store = Store(
            PumpSession.State(
                records = listOf(
                    PumpSession.Record(
                        pump = "pump", keyId = PumpSession.fingerprint(key), generation = "generation", reboot = reboot,
                        read = read, write = write, serial = "10000001", keyHex = key.joinToString("") { "%02x".format(it) },
                        writeBootstrapState = bootstrap,
                        lowerBoundRecoveryReboot = 21.takeIf { bootstrap == PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND },
                    ),
                ),
                activeGeneration = "generation",
                availability = availability,
            ),
        )
        val session = PumpSession(store)
        val token = session.open("pump", key)
        val link = Link(session, token)
        val lane = YpsoWriteLane(category, link, SessionCrypto(), mock())
        val outcomes = mutableListOf<YpsoWriteOutcome>()

        val plaintext: ByteArray = when (category) {
            Category.PROFILE_SELECTOR -> YpsoGlb.encode(14)
            Category.HISTORY_SELECTOR -> YpsoGlb.encode(0)
            Category.BOLUS -> YpsoCrc.appendCrc(BolusCommand(1.2).encode())
            Category.TBR -> TbrCommand(150, 30).encode()
        }
        val frameCount: Int get() = YpsoFraming.chunkPayload(SessionCrypto().encrypt(plaintext, key, 21, 1)).size

        fun owner(lowerBoundRecovery: Boolean = false, token: PumpSession.Token = this.token) = YpsoWriteLane.Owner(
            YpsoConnectionEpoch(mock(), token, "key", null, 1, 21),
            YpsoLinkLease(category.operation),
            "connection",
            lowerBoundRecovery,
        )

        fun write(
            owner: YpsoWriteLane.Owner,
            writeId: String = "write",
            plaintext: ByteArray = this.plaintext,
            beforeDispatch: (PumpSession.Reservation) -> Unit = {},
        ) = lane.write(owner, writeId, plaintext, "V05.00.52", beforeDispatch, outcomes::add)

        fun callback(owner: YpsoWriteLane.Owner, status: Int = 0) = lane.onCharacteristicWrite(owner.epoch.connection, status)

        /** Acknowledges frames until the write has an outcome. */
        fun acknowledge(owner: YpsoWriteLane.Owner) {
            while (outcomes.isEmpty()) callback(owner)
        }

        /** Answers every attempt's final frame with pump counter error 139 until the search gives up. */
        fun rejectCounterUntilExhausted(owner: YpsoWriteLane.Owner) {
            while (outcomes.isEmpty()) {
                repeat(frameCount - 1) { callback(owner) }
                callback(owner, 139)
            }
        }

        fun holds(owner: YpsoWriteLane.Owner) = link.released.none { it === owner.lease }
        val reservation: PumpSession.Reservation get() = checkNotNull(session.snapshot()?.reservation)
        fun sentCounter(): Long = SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(link.frames), key).counter
    }

    private val evidence = "ab".repeat(32)

    // ---- link release, per category and write outcome ----

    @Test
    fun `each category states which write outcomes keep the link`() {
        val failure = YpsoWriteFailure(YpsoWriteFailure.Layer.GATT_CALLBACK, YpsoWritePolicy.TBR_START_STOP_UUID, null, detail = "test")
        val outcomes = listOf(
            YpsoWriteOutcome.NotSent("w", null, failure),
            YpsoWriteOutcome.ProvenRejected("w", 1, failure),
            YpsoWriteOutcome.PossiblyApplied("w", 1, failure),
            YpsoWriteOutcome.AcceptedUnverified("w", 1),
            YpsoWriteOutcome.Verified("w", 1, "test"),
        )
        val expected = mapOf(
            // The read that acquired the link holds it across every selector write.
            Category.PROFILE_SELECTOR to listOf(true, true, true, true, true),
            Category.HISTORY_SELECTOR to listOf(true, true, true, true, true),
            // A bolus keeps it only while the command may have been applied and is unproven.
            Category.BOLUS to listOf(false, false, true, true, false),
            // A TBR keeps it on every sent outcome, for the same-link status read that follows.
            Category.TBR to listOf(false, false, true, true, true),
        )
        for ((category, keeps) in expected) {
            assertEquals(keeps, outcomes.map(category::keepsLink), "$category")
        }
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a write refused before reservation reports not sent and keeps the link only for a selector read`(category: Category) {
        val pump = Pump(category, reboot = null, read = null, write = null, bootstrap = PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH)
        val owner = pump.owner()

        assertFalse(pump.write(owner))

        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.NotSent)
        assertNull(pump.session.snapshot()?.reservation)
        assertTrue(pump.link.frames.isEmpty())
        assertEquals(category.selector, pump.holds(owner))
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a first frame the link refused reports not sent and keeps the link only for a selector read`(category: Category) {
        val pump = Pump(category)
        pump.link.acceptFrames = false
        val owner = pump.owner()

        pump.write(owner)

        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.NotSent, "${pump.outcomes}")
        assertEquals(category.selector, pump.holds(owner))
        assertTrue(pump.lane.isIdle())
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a therapy write that never started gives the link back even when its journal failed after reservation`(category: Category) {
        val pump = Pump(category)
        val owner = pump.owner()

        // POSSIBLY_SENT cannot be recorded, so nothing is dispatched but the counter may be consumed.
        assertFalse(pump.write(owner, beforeDispatch = { pump.store.fail = true }))

        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.PossiblyApplied, "${pump.outcomes}")
        assertTrue(pump.link.frames.isEmpty())
        assertEquals(category.selector, pump.holds(owner))
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `an acknowledged write keeps the link until reconciliation, which hands back a therapy write only`(category: Category) {
        val pump = Pump(category)
        val owner = pump.owner()

        assertTrue(pump.write(owner))
        pump.acknowledge(owner)

        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertTrue(pump.holds(owner))
        assertTrue(pump.lane.reconcile(owner, "write", YpsoSemanticEvidence.ACCEPTED, evidence, "read-back matched"))
        // The caller hears the transport outcome once; reconciliation answers through its result.
        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(category.selector, pump.holds(owner))
        assertEquals(PumpSession.Phase.VERIFIED, pump.reservation.phase)
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a write past its deadline is possibly applied and keeps the link`(category: Category) {
        val pump = Pump(category)
        val owner = pump.owner()

        assertTrue(pump.write(owner))
        pump.link.scheduler.fire()

        val outcome = pump.outcomes.single()
        assertTrue(outcome is YpsoWriteOutcome.PossiblyApplied && outcome.failure.layer == YpsoWriteFailure.Layer.DEADLINE, "$outcome")
        assertTrue(pump.holds(owner))
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, pump.reservation.phase)
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `an exhausted counter search is proven rejected and keeps the link only for a selector read`(category: Category) {
        val pump = Pump(category)
        val owner = pump.owner()

        assertTrue(pump.write(owner))
        pump.rejectCounterUntilExhausted(owner)

        val outcome = pump.outcomes.single()
        assertTrue(outcome is YpsoWriteOutcome.ProvenRejected && outcome.failure.code == 139, "$outcome")
        assertEquals(category.selector, pump.holds(owner))
    }

    @Test
    fun `a bolus left unresolved hands back the link and leaves its reservation for the bolus journal`() {
        val pump = Pump(Category.BOLUS)
        val owner = pump.owner()
        pump.write(owner)
        pump.acknowledge(owner)

        assertFalse(pump.lane.recordUnresolved(owner, "write", evidence, "status did not show the bolus"))

        assertFalse(pump.holds(owner))
        assertEquals(PumpSession.Phase.ACKED, pump.reservation.phase)
        assertEquals(evidence, pump.session.snapshot()!!.writeEvidence.last().evidenceHash)
    }

    @Test
    fun `a TBR left unresolved hands back the link and retires its reservation at the counter it used`() {
        val pump = Pump(Category.TBR)
        val owner = pump.owner()
        pump.write(owner)
        pump.acknowledge(owner)

        assertFalse(pump.lane.recordUnresolved(owner, "write", evidence, "status did not show the TBR"))

        assertFalse(pump.holds(owner))
        assertTrue(pump.lane.isIdle())
        val record = pump.session.snapshot()!!
        assertTrue(record.reservation == null || record.reservation.phase == PumpSession.Phase.VERIFIED, "${record.reservation}")
        assertEquals(4_810L, record.write)
    }

    @Test
    fun `a bolus whose unresolved evidence cannot be saved still hands back the link and stays uncertain`() {
        val pump = Pump(Category.BOLUS)
        val owner = pump.owner()
        pump.write(owner)
        pump.acknowledge(owner)
        val frames = pump.link.frames.size
        pump.store.fail = true

        assertThrows<SecurityException> { pump.lane.recordUnresolved(owner, "write", evidence, "status did not show the bolus") }

        assertFalse(pump.holds(owner))
        // The durable journal still holds the acknowledged, unproven reservation; nothing is redispatched.
        assertEquals(PumpSession.Phase.ACKED, pump.store.state.records.single().reservation?.phase)
        assertEquals(frames, pump.link.frames.size)
        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
    }

    @Test
    fun `a TBR left unresolved tears down its write before retiring it, and hands back the link even when neither can be saved`() {
        val pump = Pump(Category.TBR)
        val owner = pump.owner()
        pump.write(owner)
        pump.acknowledge(owner)
        val frames = pump.link.frames.size
        var idleAtRetire: Boolean? = null
        var heldAtRetire: Boolean? = null
        pump.link.onRecoverInterruptedWrite = {
            idleAtRetire = pump.lane.isIdle()
            heldAtRetire = pump.holds(owner)
        }
        pump.store.fail = true

        assertThrows<SecurityException> { pump.lane.recordUnresolved(owner, "write", evidence, "status did not show the TBR") }

        assertEquals(true, idleAtRetire)
        assertEquals(true, heldAtRetire)
        assertFalse(pump.holds(owner))
        // Nothing could be saved, so the reservation stays uncertain and is never redispatched.
        assertEquals(PumpSession.Phase.ACKED, pump.store.state.records.single().reservation?.phase)
        assertEquals(frames, pump.link.frames.size)
    }

    @Test
    fun `a TBR rejection confirmed by status resolves the write with its counter consumed`() {
        val pump = Pump(Category.TBR)
        val owner = pump.owner()
        pump.write(owner)
        repeat(pump.frameCount - 1) { pump.callback(owner) }
        pump.callback(owner, 134)
        assertTrue(pump.holds(owner))

        assertTrue(pump.lane.reconcile(owner, "write", YpsoSemanticEvidence.REJECTED, evidence, "TBR_ALREADY_ACTIVE; status unchanged"))

        assertFalse(pump.holds(owner))
        assertEquals(PumpSession.WriteResolution.REJECTED_COUNTER_CONSUMED, pump.session.snapshot()!!.writeEvidence.last().resolution)
    }

    @Test
    fun `an owner writes only on the lane of the operation it holds`() {
        val bolus = Pump(Category.BOLUS)
        val tbr = Pump(Category.TBR)

        assertThrows<IllegalArgumentException> { bolus.write(tbr.owner()) }
        assertThrows<IllegalArgumentException> { bolus.write(bolus.owner(lowerBoundRecovery = true)) }
        assertTrue(bolus.link.frames.isEmpty())
    }

    // ---- counter accounting ----

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `an unknown write floor reconciles the first write from zero`(category: Category) {
        val pump = Pump(category, write = null, bootstrap = PumpSession.WriteBootstrapState.UNKNOWN_MID_EPOCH)

        assertTrue(pump.write(pump.owner()))

        assertEquals(1L, pump.reservation.counter)
        assertEquals(0L, pump.reservation.priorWrite)
        assertEquals(PumpSession.WriteCandidate.STANDARD, pump.reservation.candidate)
        assertTrue(pump.link.frames.isNotEmpty())
        assertTrue(pump.outcomes.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a durable lower bound permits ordinary writes above the seeded floor`(category: Category) {
        val pump = Pump(category, write = 9_035, bootstrap = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND)

        assertTrue(pump.write(pump.owner()))

        assertEquals(9_036L, pump.reservation.counter)
        assertEquals(PumpSession.WriteCandidate.STANDARD, pump.reservation.candidate)
        assertTrue(pump.outcomes.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `each write holds one strict-next durable reservation of its exact payload`(category: Category) {
        val pump = Pump(category)
        var journaledCounter: Long? = null

        assertTrue(pump.write(pump.owner(), beforeDispatch = { journaledCounter = it.counter }))

        assertEquals(4_810L, journaledCounter)
        assertEquals(4_810L, pump.reservation.counter)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, pump.reservation.phase)
        assertEquals(category.purpose.name, pump.reservation.purpose)
        assertEquals(category.characteristic.toString(), pump.reservation.characteristic)
        assertEquals(YpsoWriteAccounting.sha256(pump.plaintext), pump.reservation.payloadHash)
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `only a therapy write records its transport acknowledgement as its own phase`(category: Category) {
        val pump = Pump(category)
        val owner = pump.owner()

        pump.write(owner)
        pump.acknowledge(owner)

        val message = SessionCrypto().decrypt(YpsoFraming.parseMultiFrameRead(pump.link.frames), pump.key)
        assertArrayEquals(pump.plaintext, message.body)
        assertEquals(21, message.reboot)
        assertEquals(4_810L, message.counter)
        // A selector ACK proves no more than POSSIBLY_SENT; only identity read-back resolves it.
        assertEquals(if (category.selector) PumpSession.Phase.POSSIBLY_SENT else PumpSession.Phase.ACKED, pump.reservation.phase)
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a pump counter error redispatches the same write with the next exponential candidate`(category: Category) {
        val pump = Pump(category, write = 4_280)
        val owner = pump.owner()

        assertTrue(pump.write(owner))
        repeat(pump.frameCount - 1) { pump.callback(owner) }
        assertEquals(4_281L, pump.sentCounter())
        pump.link.frames.clear()
        pump.callback(owner, 139)

        // The rejection is durable, nothing is reported to the caller, and the same logical write
        // is redispatched above the retained search position without caller involvement.
        assertTrue(pump.outcomes.isEmpty())
        assertEquals(1, pump.session.snapshot()!!.counterRecoveryExponent)
        assertEquals(4_282L, pump.reservation.counter)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, pump.reservation.phase)
        pump.acknowledge(owner)
        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(4_282L, pump.sentCounter())
        assertTrue(pump.lane.reconcile(owner, "write", YpsoSemanticEvidence.ACCEPTED, evidence, "read-back matched"))
        assertEquals(0, pump.session.snapshot()!!.counterRecoveryExponent)
        assertEquals(4_282L, pump.session.snapshot()!!.write)
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `an unrelated status neither advances the counter search nor retries`(category: Category) {
        val pump = Pump(category, write = 4_280)
        val owner = pump.owner()

        assertTrue(pump.write(owner))
        repeat(pump.frameCount - 1) { pump.callback(owner) }
        pump.callback(owner, 133)

        val outcome = pump.outcomes.single()
        assertTrue(outcome is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(4_281L, outcome.counter)
        assertEquals(0, pump.session.snapshot()!!.counterRecoveryExponent)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, pump.reservation.phase)
    }

    @ParameterizedTest
    @EnumSource(Category::class)
    fun `a callback that cannot be assigned to a frame leaves the write unreconcilable`(category: Category) {
        val pump = Pump(category)
        val owner = pump.owner()
        pump.write(owner)
        pump.acknowledge(owner)

        // A second same-UUID callback after the final frame cannot belong to any submitted frame.
        pump.callback(owner)

        assertFalse(pump.lane.reconcile(owner, "write", YpsoSemanticEvidence.ACCEPTED, evidence, "read-back matched"))
        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertTrue(pump.holds(owner))
        assertNull(pump.session.snapshot()!!.writeEvidence.last().resolution)
    }

    // ---- selectors ----

    @Test
    fun `a profile selector outside the active and schedule allowlist is never reserved`() {
        val pump = Pump(Category.PROFILE_SELECTOR)

        assertFalse(pump.write(pump.owner(), plaintext = YpsoGlb.encode(13)))

        val outcome = pump.outcomes.single()
        assertTrue(outcome is YpsoWriteOutcome.NotSent && outcome.failure.layer == YpsoWriteFailure.Layer.POLICY, "$outcome")
        assertNull(pump.session.snapshot()!!.reservation)
    }

    @Test
    fun `a selector does not start behind an unresolved write of its lane`() {
        val pump = Pump(Category.HISTORY_SELECTOR)
        val first = pump.owner()
        pump.write(first, "first")
        pump.callback(first, 133)
        pump.outcomes.clear()

        assertFalse(pump.write(pump.owner(), "second"))

        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.NotSent)
        assertEquals("first", pump.reservation.operationId)
    }

    @Test
    fun `an interrupted selector is retired on reconnect without knowing whether the pump consumed its counter`() {
        val pump = Pump(Category.PROFILE_SELECTOR, write = 4_280)
        repeat(3) { interruption ->
            // Every reconnect opens the session again; the lane rebuilds its accounting for it.
            val session = PumpSession(pump.store)
            val token = session.open("pump", pump.key)
            pump.link.session = session
            pump.link.token = token
            pump.link.frames.clear()
            pump.outcomes.clear()
            val owner = pump.owner(token = token)

            assertTrue(pump.write(owner, "profile-reconnect-$interruption"), "A lost final callback must not permanently block the next acquisition")
            repeat(pump.frameCount - 1) { pump.callback(owner) }
            pump.callback(owner, 133)
            assertTrue(pump.outcomes.single() is YpsoWriteOutcome.PossiblyApplied)
            assertEquals(4_281L + interruption, pump.sentCounter())
            assertEquals(interruption, session.snapshot()!!.writeEvidence.size)
            assertTrue(session.snapshot()!!.writeEvidence.all { it.resolution == null })
            pump.lane.ownerDisconnected(owner.epoch.connection, "lost final callback")
        }
        assertEquals(4_283L, pump.store.state.records.single().write)
    }

    // ---- history lower-bound recovery ----

    private fun lowerBoundPump() = Pump(
        Category.HISTORY_SELECTOR,
        key = ByteArray(32) { 8 },
        write = 9_035,
        bootstrap = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
        availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
    )

    @Test
    fun `lower-bound recovery is established only after exact semantic acceptance`() {
        val pump = lowerBoundPump()
        val owner = pump.owner(lowerBoundRecovery = true)

        assertTrue(pump.write(owner))
        val reservation = pump.reservation
        assertEquals(PumpSession.WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR, reservation.candidate)
        pump.acknowledge(owner)
        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        // Unlike an ordinary selector, the recovery probe records its transport acknowledgement.
        assertEquals(PumpSession.Phase.ACKED, pump.reservation.phase)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, pump.session.snapshot()!!.writeBootstrapState)

        assertTrue(pump.lane.reconcile(owner, "write", YpsoSemanticEvidence.ACCEPTED, evidence, "exact selector read-back matched"))

        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, pump.session.snapshot()!!.writeBootstrapState)
        assertEquals(PumpSession.Phase.VERIFIED, pump.reservation.phase)
        assertEquals(reservation.counter, pump.session.snapshot()!!.write)
        assertTrue(pump.holds(owner))
    }

    @Test
    fun `lower-bound recovery is only for a session that is recovering one`() {
        val pump = Pump(Category.HISTORY_SELECTOR)

        assertFalse(pump.write(pump.owner(lowerBoundRecovery = true)))

        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.NotSent)
        assertNull(pump.session.snapshot()!!.reservation)
    }

    @Test
    fun `a pump counter error redispatches the recovery probe one exponential step higher`() {
        val pump = lowerBoundPump()
        val owner = pump.owner(lowerBoundRecovery = true)

        assertTrue(pump.write(owner))
        repeat(pump.frameCount - 1) { pump.callback(owner) }
        assertEquals(9_036L, pump.sentCounter())
        pump.link.frames.clear()
        pump.callback(owner, 139)

        assertTrue(pump.outcomes.isEmpty())
        assertEquals(1, pump.session.snapshot()!!.counterRecoveryExponent)
        assertEquals(9_037L, pump.reservation.counter)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, pump.reservation.phase)
        pump.acknowledge(owner)
        assertTrue(pump.outcomes.single() is YpsoWriteOutcome.AcceptedUnverified)
        assertEquals(9_037L, pump.sentCounter())
        assertTrue(pump.lane.reconcile(owner, "write", YpsoSemanticEvidence.ACCEPTED, evidence, "exact selector read-back matched"))
        assertEquals(0, pump.session.snapshot()!!.counterRecoveryExponent)
        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, pump.session.snapshot()!!.writeBootstrapState)
        assertEquals(9_037L, pump.session.snapshot()!!.write)
    }

    @Test
    fun `an ambiguous recovery probe is neither advanced nor retried, even across a restart`() {
        val pump = lowerBoundPump()
        val owner = pump.owner(lowerBoundRecovery = true)

        assertTrue(pump.write(owner))
        repeat(pump.frameCount - 1) { pump.callback(owner) }
        pump.callback(owner, 133)

        val ambiguous = pump.outcomes.single()
        assertTrue(ambiguous is YpsoWriteOutcome.PossiblyApplied)
        assertEquals(9_036L, ambiguous.counter)
        assertEquals(0, pump.session.snapshot()!!.counterRecoveryExponent)
        assertEquals(9_036L, pump.session.snapshot()!!.write)
        assertEquals(PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND, pump.session.snapshot()!!.writeBootstrapState)
        assertTrue(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN in pump.session.availability().causes)
        // Losing the connection cannot make it look like an ordinary selector that reconnect may retire.
        pump.lane.ownerDisconnected(owner.epoch.connection, "connection lost")
        assertEquals(PumpSession.WriteCandidate.LOWER_BOUND_HISTORY_RECOVERY_SELECTOR, pump.reservation.candidate)
        assertFalse(pump.session.isAbandonableSelector(pump.reservation))

        // A restart cannot retire an ambiguous recovery probe: the unresolved reservation blocks.
        val restarted = PumpSession(pump.store)
        val restartedToken = restarted.open("pump", pump.key)
        val link = Link(restarted, restartedToken)
        val lane = YpsoWriteLane(Category.HISTORY_SELECTOR, link, SessionCrypto(), mock())
        val outcomes = mutableListOf<YpsoWriteOutcome>()
        val restartedOwner = YpsoWriteLane.Owner(
            YpsoConnectionEpoch(mock(), restartedToken, "key", null, 2, 21),
            YpsoLinkLease(Category.HISTORY_SELECTOR.operation),
            "restart",
            lowerBoundRecovery = true,
        )

        assertFalse(lane.write(restartedOwner, "after-ambiguous-restart", YpsoGlb.encode(0), null, onOutcome = outcomes::add))

        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertTrue(link.frames.isEmpty())
        assertEquals(9_036L, restarted.snapshot()!!.write)
        assertEquals(PumpSession.Phase.POSSIBLY_SENT, restarted.snapshot()!!.reservation!!.phase)
        assertEquals(0, restarted.snapshot()!!.counterRecoveryExponent)
    }
}
