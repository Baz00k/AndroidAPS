package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoGattLayout
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EVENT_COUNT
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EVENT_VALUE
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_SETTING_VALUE
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_STATUS
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_SYSTEM_DATE
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_SYSTEM_TIME
import app.aaps.pump.ypsopump.ble.YpsoLinkOperation
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy.EVENT_INDEX_UUID
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy.SETTING_ID_UUID
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoGlb
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoProfileReadback
import app.aaps.pump.ypsopump.history.YpsoEventIdentity
import app.aaps.pump.ypsopump.history.YpsoHistoryCursor
import app.aaps.pump.ypsopump.history.YpsoHistoryEntry
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciler
import app.aaps.pump.ypsopump.history.YpsoHistoryReconciliation
import app.aaps.pump.ypsopump.history.YpsoHistorySnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Profile and history selector acquisition of [YpsoBleManager] against a fake pump. */
class YpsoBleManagerAcquisitionTest {

    @Test
    fun `lower bound recovery owns GATT lane permits selector frames and releases lease`() {
        val fixture = YpsoPumpFixture(
            initial = readBaseline(MAC, KEY, 8, 0).let { state ->
                state.copy(
                    records = state.records.map {
                        it.copy(
                            write = 4_280,
                            writeBootstrapState = PumpSession.WriteBootstrapState.RECOVERING_LOWER_BOUND,
                            lowerBoundRecoveryReboot = it.reboot,
                        )
                    },
                    availability = PumpSession.Availability(setOf(PumpSession.AvailabilityCause.COUNTER_UNCERTAIN)),
                )
            },
        )
        fixture.pump.configure = { it.expose(EVENT_INDEX_UUID, CHAR_EVENT_COUNT) }
        val connection = fixture.connect()
        val manager = fixture.manager
        val recovery = mutableListOf<YpsoBleManager.LowerBoundRecoveryResult>()
        val competingStatus = mutableListOf<Boolean>()

        manager.recoverHistorySelectorLowerBound(recovery::add)
        manager.readStatus(competingStatus::add)

        assertEquals(listOf(false), competingStatus)
        assertFalse(CHAR_STATUS in connection.reads)
        connection.ackDescriptor()
        fixture.respond(connection, CHAR_EVENT_COUNT, YpsoGlb.encode(2))
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(0))
        repeat(4) { connection.ackWrite(EVENT_INDEX_UUID) }
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(1))

        assertEquals(listOf(YpsoBleManager.LowerBoundRecoveryResult.RECOVERED), recovery)
        assertEquals(PumpSession.WriteBootstrapState.ESTABLISHED, fixture.session.snapshot()!!.writeBootstrapState)
        assertEquals(4, connection.writes.count { it.first == EVENT_INDEX_UUID })
        manager.readStatus()
        assertTrue(CHAR_STATUS in connection.reads)
    }

    @Test
    fun `profile readiness depends on the authenticated read floor not the write allocation`() {
        val fixture = YpsoPumpFixture()
        fixture.connect()

        assertNull(fixture.session.snapshot()!!.write)
        assertTrue(fixture.manager.canReadProfile)
    }

    @Test
    fun `profile read refuses a write ready session when selector identity is not readable`() {
        val fixture = YpsoPumpFixture(initial = established(write = 4_153))
        fixture.connectWithProtocol()
        val results = mutableListOf<Boolean>()

        fixture.manager.readProfile(results::add)

        assertTrue(fixture.manager.canReadProfile)
        assertEquals(listOf(false), results)
        assertEquals(4_153, fixture.manager.writeCounter)
        assertEquals(null, fixture.session.snapshot()!!.reservation)
    }

    @Test
    fun `production acquisition reconciles all fifty selectors before publishing profile`() {
        acquireProfile()
    }

    @Test
    fun `explicit acquisition rejects a history change during profile acquisition`() {
        acquireProfile(finalCount = 3002, expectedSuccess = false)
    }

    @Test
    fun `production acquisition rejects malformed selector identity without reading its value`() {
        acquireProfile(malformedIdentity = true)
    }

    @Test
    fun `production acquisition cancellation prevents queued continuation from reserving another selector`() {
        acquireProfile(cancelAfterFirstRow = true)
    }

    @Test
    fun `configuration refresh yields after reconciled selector and retains previous complete schedules`() {
        acquireProfile(yieldAfterFirstRow = true)
    }

    @Test
    fun `active program check reuses both stored schedules and uses one selector`() {
        acquireProfile(activeOnly = true)
    }

    @Test
    fun `active program check rejects cached schedules from another time zone`() {
        val foreignZone = if (ZoneId.systemDefault() == ZoneId.of("Europe/Warsaw")) ZoneId.of("UTC") else ZoneId.of("Europe/Warsaw")
        acquireProfile(activeOnly = true, expectedSuccess = false, cachedZone = foreignZone)
    }

    @Test
    fun `stable history includes the head once and continues with the next logical index`() {
        val fixture = YpsoPumpFixture(initial = established())
        fixture.pump.configure = { it.expose(EVENT_INDEX_UUID, CHAR_EVENT_VALUE, CHAR_EVENT_COUNT, properties = FakeYpsoPump.READ) }
        val connection = fixture.connectWithProtocol()
        fun selected(index: Int, sequence: Long) {
            repeat(4) { connection.ackWrite(EVENT_INDEX_UUID) }
            fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(index))
            fixture.respond(connection, CHAR_EVENT_VALUE, row(index, sequence))
        }
        val results = mutableListOf<YpsoHistorySnapshot?>()

        fixture.manager.readStableHistory(null, maxRows = 2, onResult = results::add)
        connection.ackDescriptor()
        fixture.respond(connection, CHAR_EVENT_COUNT, YpsoGlb.encode(2))
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(9))
        selected(0, 101)
        // Answers a selector pre-read only if one is pending; a walk that already verified its selector has none.
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(0))
        selected(1, 100)
        fixture.respond(connection, CHAR_EVENT_COUNT, YpsoGlb.encode(2))
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(1))
        selected(0, 101)

        val snapshot = requireNotNull(results.singleOrNull()) { "results=$results reads=${connection.reads}" }
        assertEquals(listOf(0, 1), snapshot.rowsNewestFirst.map { it.index })
        assertEquals(listOf(101L, 100L), snapshot.rowsNewestFirst.map { it.sequence })
    }

    /** Drives real selector writes, read-back, row callbacks and attempt cancellation without BLE time. */
    private class HistoryPump {
        val fixture = YpsoPumpFixture(initial = established())
        val manager: YpsoBleManager get() = fixture.manager
        val pending = ArrayDeque<() -> Unit>()
        var rows = (108L downTo 101L).mapIndexed { index, sequence ->
            YpsoHistoryEntry(1000 + sequence, 2, 100, 0, 0, sequence, index)
        }
        var selected = 9
        var staleValueIndex: Int? = null
        var rowReads = 0
        var beforeRow: () -> Unit = {}

        /** Journal reservation phase at every dispatch: a transport-ACK commit is visible before the read-back read. */
        val phases = mutableListOf<PumpSession.Phase?>()

        init {
            fixture.pump.configure = { connection ->
                connection.expose(EVENT_INDEX_UUID, CHAR_EVENT_VALUE, CHAR_EVENT_COUNT, properties = FakeYpsoPump.READ)
                connection.onDescriptorWrite = { pending.add { connection.ackDescriptor() } }
                connection.onWrite = { uuid, _ ->
                    recordPhase()
                    if (uuid != YpsoGattLayout.CHAR_AUTH) pending.add { connection.ackWrite(uuid) }
                }
                connection.onRead = { uuid ->
                    recordPhase()
                    pending.add { answer(connection, uuid) }
                }
            }
            val crypto = SessionCrypto()
            doAnswer {
                val plaintext = it.getArgument<ByteArray>(0)
                selected = requireNotNull(YpsoGlb.decodeExact(plaintext))
                staleValueIndex = null
                crypto.encrypt(plaintext, it.getArgument(1), it.getArgument(2), it.getArgument(3))
            }.whenever(fixture.sessionCrypto).encrypt(any(), any(), any(), any())
            fixture.connectWithProtocol()
        }

        private fun recordPhase() {
            phases += fixture.state.records.singleOrNull { it.generation == fixture.state.activeGeneration }?.reservation?.phase
        }

        private fun answer(connection: FakeYpsoPump.Connection, uuid: UUID) {
            val body = when (uuid) {
                CHAR_EVENT_COUNT -> YpsoGlb.encode(rows.size)
                EVENT_INDEX_UUID -> YpsoGlb.encode(selected)
                CHAR_EVENT_VALUE -> {
                    rowReads++
                    beforeRow()
                    val valueIndex = staleValueIndex ?: selected
                    val row = rows[valueIndex]
                    YpsoCrc.appendCrc(
                        ByteBuffer.allocate(YpsoHistoryEntry.PAYLOAD_SIZE)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(row.factorySeconds.toInt()).put(row.eventType.toByte()).putShort(row.value1.toShort())
                            .putShort(row.value2.toShort()).putShort(row.value3.toShort())
                            .putInt(row.sequence.toInt()).putShort(valueIndex.toShort()).array()
                    )
                }
                else -> error("Unexpected read $uuid")
            }
            fixture.respond(connection, uuid, body)
        }

        fun drain() {
            var callbacks = 0
            while (pending.isNotEmpty()) {
                check(callbacks++ < 10_000) { "History did not stop" }
                pending.removeFirst().invoke()
            }
        }

        fun append() {
            rows = (listOf(rows.first().copy(sequence = rows.first().sequence + 1, factorySeconds = rows.first().factorySeconds + 1)) + rows)
                .mapIndexed { index, row -> row.copy(index = index) }
        }
    }

    @Test
    fun `interrupted recovery reaches its cursor while new pump events arrive`() {
        val pump = HistoryPump()
        val oldest = pump.rows.last()
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("test-pump", 0, oldest.sequence), oldest.fingerprint(), 8)
        val results = mutableListOf<YpsoHistorySnapshot?>()
        for (leg in 0 until 8) {
            val startReads = pump.rowReads
            val attempt = pump.manager.readStableHistory(cursor, maxRows = 3000, onResult = results::add)
            pump.beforeRow = { if (pump.rowReads - startReads >= 5) attempt.requestYield() }
            pump.drain()
            if (results.lastOrNull() != null) break
            pump.append()
        }
        val snapshot = requireNotNull(results.firstOrNull { it != null }) { "Recovery restarted on every new event and never reached the cursor" }
        assertTrue(YpsoHistoryReconciler.reconcile(cursor, snapshot) is YpsoHistoryReconciliation.Stable)
    }

    @Test
    fun `cancelled scan ignores late callback and resumes its checkpoint`() {
        val pump = HistoryPump()
        val results = mutableListOf<YpsoHistorySnapshot?>()
        val first = pump.manager.readStableHistory(null, 3000, results::add)
        pump.beforeRow = { if (pump.rowReads == 4) assertTrue(first.cancel()) }
        pump.drain()
        val before = pump.rowReads
        pump.beforeRow = {}
        pump.manager.readStableHistory(null, 3000, results::add)
        pump.drain()
        assertEquals(pump.rows, results.last()!!.rowsNewestFirst)
        // Head + cached tail + five unread rows + final head, rather than eight rows + final head.
        assertEquals(8, pump.rowReads - before)
    }

    @Test
    fun `full ring advancing between interrupted scans still recovers`() {
        val pump = HistoryPump()
        val results = mutableListOf<YpsoHistorySnapshot?>()
        for (leg in 0 until 8) {
            val before = pump.rowReads
            val attempt = pump.manager.readStableHistory(null, 3000, results::add)
            pump.beforeRow = { if (pump.rowReads - before >= 5) attempt.requestYield() }
            pump.drain()
            if (results.lastOrNull() != null) break
            pump.append()
            pump.rows = pump.rows.take(8)
        }
        assertEquals(pump.rows, results.lastOrNull()?.rowsNewestFirst)
        assertTrue(results.last()!!.fullCoverage)
    }

    @Test
    fun `resumed scan refreshes a TBR rewritten below the head`() {
        val pump = HistoryPump()
        pump.rows = pump.rows.mapIndexed { index, row -> if (index == 1) row.copy(eventType = 9, value2 = 30) else row }
        val results = mutableListOf<YpsoHistorySnapshot?>()
        val attempt = pump.manager.readStableHistory(null, 3000, results::add)
        pump.beforeRow = { if (pump.rowReads >= 4) attempt.requestYield() }
        pump.drain()
        pump.rows = pump.rows.mapIndexed { index, row -> if (index == 1) row.copy(eventType = 10, value2 = 2) else row }
        pump.beforeRow = {}
        pump.manager.readStableHistory(null, 3000, results::add)
        pump.drain()
        assertEquals(pump.rows, results.last()!!.rowsNewestFirst)
    }

    @Test
    fun `a TBR row read in this scan is not read a second time`() {
        val pump = HistoryPump()
        pump.rows = pump.rows.mapIndexed { index, row -> if (index in 1..3) row.copy(eventType = 9, value2 = 30) else row }
        val results = mutableListOf<YpsoHistorySnapshot?>()

        pump.manager.readStableHistory(null, 3000, results::add)
        pump.drain()

        assertEquals(pump.rows, results.single()!!.rowsNewestFirst)
        // Eight rows plus the final head read; the three TBR rows are not re-selected.
        assertEquals(9, pump.rowReads)
    }

    @Test
    fun `history selector writes skip the transport-ACK commit and resolve from POSSIBLY_SENT`() {
        val pump = HistoryPump()
        pump.phases.clear()
        val results = mutableListOf<YpsoHistorySnapshot?>()

        pump.manager.readStableHistory(null, 2, results::add)
        pump.drain()

        assertEquals(pump.rows.take(2), results.single()!!.rowsNewestFirst)
        assertTrue(PumpSession.Phase.POSSIBLY_SENT in pump.phases)
        assertFalse(PumpSession.Phase.ACKED in pump.phases)
        assertEquals(PumpSession.Phase.VERIFIED, pump.fixture.state.records.single().reservation?.phase)
    }

    @Test
    fun `changed cached tail causes fresh reads instead of stale reuse`() {
        val pump = HistoryPump()
        val results = mutableListOf<YpsoHistorySnapshot?>()
        val attempt = pump.manager.readStableHistory(null, 3000, results::add)
        pump.beforeRow = { if (pump.rowReads >= 4) attempt.requestYield() }
        pump.drain()
        pump.rows = pump.rows.mapIndexed { index, row -> if (index == 3) row.copy(value1 = 54) else row }
        pump.beforeRow = {}
        pump.manager.readStableHistory(null, 3000, results::add)
        pump.drain()
        assertEquals(pump.rows, results.last()!!.rowsNewestFirst)
    }

    @Test
    fun `cursor at head finishes without walking older history`() {
        val pump = HistoryPump()
        val head = pump.rows.first()
        val cursor = YpsoHistoryCursor(YpsoEventIdentity("test-pump", 0, head.sequence), head.fingerprint(), 8)
        val results = mutableListOf<YpsoHistorySnapshot?>()
        pump.manager.readStableHistory(cursor, 3000, results::add)
        pump.drain()
        assertEquals(listOf(head), results.single()!!.rowsNewestFirst)
        assertEquals(2, pump.rowReads)
    }

    @Test
    fun `already selected head with stale event value recovers by changed selector writes`() {
        val pump = HistoryPump()
        pump.selected = 0
        pump.staleValueIndex = 1
        val results = mutableListOf<YpsoHistorySnapshot?>()
        pump.manager.readStableHistory(null, 1, results::add)
        pump.drain()
        assertEquals(listOf(pump.rows.first()), results.single()?.rowsNewestFirst)
    }

    @Test
    fun `new provisioned pump cannot reuse another pumps checkpoint`() {
        val pump = HistoryPump()
        val fixture = pump.fixture
        val results = mutableListOf<YpsoHistorySnapshot?>()
        val attempt = pump.manager.readStableHistory(null, 3000, results::add)
        pump.beforeRow = { if (pump.rowReads >= 4) attempt.requestYield() }
        pump.drain()
        // Another provisioned pump: a new generation on its own session, reached over a new connection.
        val record = fixture.session.snapshot()!!
        val another = PumpSession(object : PumpSession.Store {
            var state = PumpSession.State(
                records = listOf(record.copy(generation = "another-pump", reservation = null)),
                activeGeneration = "another-pump",
            )
            override fun load() = state
            override fun commit(state: PumpSession.State) { this.state = state }
        })
        pump.manager.disconnect()
        whenever(fixture.provisioning.owner).thenReturn(another)
        whenever(fixture.provisioning.connectionSession()).thenReturn(fixture.connectionSession().copy(generation = "another-pump"))
        pump.manager.configureInstalledSession()
        fixture.connectWithProtocol()
        // Same head, count and reboot, different payload below the head on the other pump.
        pump.rows = pump.rows.mapIndexed { index, row -> if (index == 1) row.copy(value1 = 54) else row }
        pump.beforeRow = {}
        pump.manager.readStableHistory(null, 3000, results::add)
        pump.drain()
        assertEquals(pump.rows, results.last()!!.rowsNewestFirst)
    }

    @Test
    fun `stable history yield reconciles an in flight selector before releasing ownership`() {
        val fixture = YpsoPumpFixture(initial = established())
        fixture.pump.configure = { it.expose(EVENT_INDEX_UUID, CHAR_EVENT_VALUE, CHAR_EVENT_COUNT, properties = FakeYpsoPump.READ) }
        val connection = fixture.connectWithProtocol()
        val results = mutableListOf<YpsoHistorySnapshot?>()

        val attempt = fixture.manager.readStableHistory(null, maxRows = 2, onResult = results::add)
        connection.ackDescriptor()
        fixture.respond(connection, CHAR_EVENT_COUNT, YpsoGlb.encode(2))
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(9))
        assertTrue(attempt.requestYield())
        repeat(4) { connection.ackWrite(EVENT_INDEX_UUID) }
        fixture.respond(connection, EVENT_INDEX_UUID, YpsoGlb.encode(0))
        fixture.respond(connection, CHAR_EVENT_VALUE, row(0, 101))

        assertEquals(listOf(null), results)
        assertEquals(PumpSession.Phase.VERIFIED, fixture.session.snapshot()!!.reservation?.phase)
        assertEquals(2, connection.reads.count { it == EVENT_INDEX_UUID })
    }

    @Test
    fun `authentication after a lost connection retires only its stranded selector write`() {
        for ((purpose, characteristic, retired) in listOf(
            Triple("HISTORY_SELECTOR", EVENT_INDEX_UUID, true),
            Triple("SETTINGS_SELECTOR", SETTING_ID_UUID, true),
            Triple("THERAPY_COMMAND", EVENT_INDEX_UUID, false),
        )) {
            val fixture = YpsoPumpFixture(initial = stranded("selector-lost", characteristic, purpose))

            fixture.connect()

            val after = fixture.session.snapshot()!!
            assertEquals(retired, after.reservation == null, purpose)
            assertEquals(4281L, after.write)
        }
    }

    @Test
    fun `authentication does not retire a selector a read still holds, or one it cannot save`() {
        for (case in listOf("history read", "profile read", "unsaved")) {
            val initial = stranded("history-lost", EVENT_INDEX_UUID, "HISTORY_SELECTOR")
            val fixture = YpsoPumpFixture(initial = initial)
            // The fixture's own store is not reachable as a failable store, so install an equivalent one.
            val store = FailableStore(initial)
            whenever(fixture.provisioning.owner).thenReturn(PumpSession(store))
            fixture.manager.configureInstalledSession()
            fixture.pumpState.pumpAddress = fixture.mac
            fixture.manager.connectConfiguredSession()
            val connection = fixture.pump.current
            connection.connected()
            connection.servicesDiscovered()
            when (case) {
                "history read" -> assertNotNull(fixture.link.acquire(YpsoLinkOperation.HISTORY))
                "profile read" -> assertNotNull(fixture.link.acquire(YpsoLinkOperation.PROFILE))
                else -> store.fail = true
            }

            connection.ackWrite(YpsoGattLayout.CHAR_AUTH)

            // Still reserved, so write readiness keeps refusing therapy past it.
            assertEquals(PumpSession.Phase.POSSIBLY_SENT, store.state.records.single().reservation?.phase, case)
            assertEquals("stranded", store.state.records.single().reservation?.id, case)
        }
    }

    @Test
    fun `authentication retires an unheld selector it can save`() {
        // Control for the test above: the same stranded selector is retired when nothing holds it.
        val initial = stranded("history-lost", EVENT_INDEX_UUID, "HISTORY_SELECTOR")
        val fixture = YpsoPumpFixture(initial = initial)
        val store = FailableStore(initial)
        whenever(fixture.provisioning.owner).thenReturn(PumpSession(store))
        fixture.manager.configureInstalledSession()

        fixture.connect()

        assertNull(store.state.records.single().reservation)
    }

    private class FailableStore(var state: PumpSession.State) : PumpSession.Store {
        var fail = false
        override fun load() = state
        override fun commit(state: PumpSession.State) {
            check(!fail) { "journal unavailable" }
            this.state = state
        }
    }

    private fun acquireProfile(
        finalCount: Int = 3000, malformedIdentity: Boolean = false, cancelAfterFirstRow: Boolean = false,
        yieldAfterFirstRow: Boolean = false, activeOnly: Boolean = false, expectedSuccess: Boolean = true,
        cachedZone: ZoneId = ZoneId.systemDefault(),
    ) {
        val fixture = YpsoPumpFixture(initial = established())
        val manager = fixture.manager
        val pumpState = fixture.pumpState
        fixture.pump.configure = {
            it.expose(SETTING_ID_UUID, CHAR_SETTING_VALUE, CHAR_SYSTEM_DATE, CHAR_SYSTEM_TIME, CHAR_EVENT_COUNT, properties = FakeYpsoPump.READ)
        }
        val connection = fixture.connectWithProtocol()
        // Journal reservation phase at every dispatch: a transport-ACK commit is visible before the read-back read.
        val phases = mutableListOf<PumpSession.Phase?>()
        fun recordPhase() {
            phases += fixture.state.records.single().reservation?.phase
        }
        connection.onWrite = { _, _ -> recordPhase() }
        connection.onRead = { recordPhase() }
        fun respond(uuid: UUID, body: ByteArray) = fixture.respond(connection, uuid, body)
        fun selected(id: Int, settingValue: Int) {
            repeat(4) { connection.ackWrite(SETTING_ID_UUID) }
            respond(SETTING_ID_UUID, YpsoGlb.encode(id))
            respond(CHAR_SETTING_VALUE, YpsoGlb.encode(settingValue))
        }
        val results = mutableListOf<Boolean>()
        val previous = if (yieldAfterFirstRow || activeOnly) cachedProfile(fixture, cachedZone) else null
        var yielding = false
        val attempt = manager.readProfileConfiguration(activeOnly, { yielding }, results::add)
        connection.ackDescriptor()
        respond(CHAR_EVENT_COUNT, YpsoGlb.encode(3000))
        respond(SETTING_ID_UUID, YpsoGlb.encode(61))
        if (malformedIdentity) {
            repeat(4) { connection.ackWrite(SETTING_ID_UUID) }
            respond(SETTING_ID_UUID, YpsoGlb.encode(1) + byteArrayOf(0))
            assertEquals(listOf(false), results)
            assertFalse(pumpState.hasFreshProfileEvidence)
            assertFalse(CHAR_SETTING_VALUE in connection.reads)
            // Unreconciled, so it keeps blocking later writes; the transport ACK itself is not journalled.
            assertEquals(PumpSession.Phase.POSSIBLY_SENT, fixture.session.snapshot()!!.reservation!!.phase)
            return
        }
        selected(1, if (activeOnly) 10 else 3)
        if (activeOnly) {
            respond(CHAR_EVENT_COUNT, YpsoGlb.encode(3000))
            if (!expectedSuccess) {
                assertEquals(listOf(false), results)
                assertFalse(pumpState.hasFreshProfileEvidence)
                return
            }
            assertEquals(listOf(true), results)
            assertEquals("B", pumpState.lastReadProgram)
            assertTrue(pumpState.profileEvidence!!.profileA === previous!!.profileA)
            assertTrue(pumpState.profileEvidence!!.profileB === previous.profileB)
            assertTrue(pumpState.profileEvidence!!.observedAt >= previous.observedAt)
            assertEquals(4281, manager.writeCounter)
            return
        }
        for (id in 14..61) {
            if (yieldAfterFirstRow && id == 14) yielding = true
            if (cancelAfterFirstRow && id == 14) {
                val continuations = mutableListOf<Runnable>()
                fixture.scheduler.execute = { continuations.add(it) }
                repeat(4) {
                    connection.ackWrite(SETTING_ID_UUID)
                    continuations.removeAt(0).run()
                }
                respond(SETTING_ID_UUID, YpsoGlb.encode(id))
                respond(CHAR_SETTING_VALUE, YpsoGlb.encode(50))
                assertTrue(attempt.cancel())
                continuations.forEach(Runnable::run)
                assertEquals(listOf(false), results)
                assertEquals(4282L, fixture.state.records.single().write)
                assertFalse(pumpState.hasFreshProfileEvidence)
                return
            }
            selected(id, if (id < 38) 50 else 35)
            if (yieldAfterFirstRow) {
                assertEquals(listOf(false), results)
                assertTrue(pumpState.profileEvidence === previous)
                assertEquals(4282, manager.writeCounter)
                assertEquals(PumpSession.Phase.VERIFIED, fixture.session.snapshot()!!.reservation!!.phase)
                return
            }
            assertFalse(pumpState.hasFreshProfileEvidence)
        }
        selected(1, 3)
        val now = LocalDateTime.now()
        respond(CHAR_SYSTEM_DATE, byteArrayOf((now.year and 255).toByte(), (now.year shr 8).toByte(), now.monthValue.toByte(), now.dayOfMonth.toByte()))
        respond(CHAR_SYSTEM_TIME, byteArrayOf(now.hour.toByte(), now.minute.toByte(), now.second.toByte()))
        respond(CHAR_EVENT_COUNT, YpsoGlb.encode(finalCount))
        assertEquals(listOf(expectedSuccess), results)
        assertEquals(expectedSuccess, pumpState.hasFreshProfileEvidence)
        assertEquals(4330, manager.writeCounter)
        assertEquals(PumpSession.Phase.VERIFIED, fixture.session.snapshot()!!.reservation!!.phase)
        // Selectors resolve from POSSIBLY_SENT by identity read-back: no Keystore-rotating ACK commit.
        assertTrue(PumpSession.Phase.POSSIBLY_SENT in phases)
        assertFalse(PumpSession.Phase.ACKED in phases)
    }

    private fun cachedProfile(fixture: YpsoPumpFixture, zone: ZoneId): YpsoProfileReadback.VerifiedReadback {
        val template = YpsoProfileReadbackTest.verified()
        val record = fixture.session.snapshot()!!
        fixture.pumpState.elapsedRealtime = { 2001L }
        return YpsoProfileReadback.VerifiedReadback(
            record.generation, record.reboot!!, "connection", template.activeProgram,
            template.profileA, template.profileB, 2000L, zone, 3000,
        ).also(fixture.pumpState::publishProfileEvidence)
    }

    private companion object {
        const val MAC = "12:34:56:78:9A:BC"
        val KEY = ByteArray(32)

        /** Authenticated read floor with an established write floor. */
        fun established(write: Long = 4280, reservation: PumpSession.Reservation? = null): PumpSession.State =
            readBaseline(MAC, KEY, 8, 0).let { state ->
                state.copy(
                    records = state.records.map {
                        it.copy(write = write, writeBootstrapState = PumpSession.WriteBootstrapState.ESTABLISHED, reservation = reservation)
                    },
                )
            }

        /** A write a lost connection left possibly sent above floor 4280. */
        fun stranded(operationId: String, characteristic: UUID, purpose: String): PumpSession.State = established(
            write = 4281,
            reservation = PumpSession.Reservation(
                "stranded", 4281, PumpSession.Phase.POSSIBLY_SENT, operationId,
                characteristic.toString(), purpose, "ab".repeat(32), priorWrite = 4280,
            ),
        )

        fun row(index: Int, sequence: Long): ByteArray {
            val payload = ByteBuffer.allocate(YpsoHistoryEntry.PAYLOAD_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(100 + index).put(2).putShort(100).putShort(0).putShort(0).putInt(sequence.toInt()).putShort(index.toShort())
                .array()
            return YpsoCrc.appendCrc(payload)
        }
    }
}
