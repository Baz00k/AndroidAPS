package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.ble.ConnectionState
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoGattLayout
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_CONTROL_VERSION
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_EXTREAD
import app.aaps.pump.ypsopump.ble.YpsoGattLayout.CHAR_STATUS
import app.aaps.pump.ypsopump.ble.YpsoLinkLease
import app.aaps.pump.ypsopump.ble.YpsoLinkOperation
import app.aaps.pump.ypsopump.ble.YpsoRemoteWrite
import app.aaps.pump.ypsopump.ble.YpsoWriteAccounting
import app.aaps.pump.ypsopump.ble.YpsoWriteOutcome
import app.aaps.pump.ypsopump.ble.YpsoWritePolicy
import app.aaps.pump.ypsopump.bolus.YpsoBolusBlock
import app.aaps.pump.ypsopump.bolus.YpsoBolusRequestValidator
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import app.aaps.pump.ypsopump.comm.commands.TbrCommand
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Pump operations of [app.aaps.pump.ypsopump.ble.YpsoBleManager] against a fake pump. */
class YpsoBleManagerTest {
    private val fixture = YpsoPumpFixture()
    private val manager = fixture.manager
    private val pumpState = fixture.pumpState
    private val provisioning = fixture.provisioning
    private val sessionCrypto = fixture.sessionCrypto

    private fun stubStatus(body: ByteArray = validStatusPayload()) = fixture.decryptsTo(body)

    @Test
    fun `display readings survive same session configuration but clear for a replacement generation`() {
        val connection = fixture.connectionSession()
        whenever(provisioning.connectionSession()).thenReturn(connection)
        manager.configureInstalledSession()
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)
        manager.disconnect()
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(42.0, pumpState.displayStatus().snapshot?.reservoirUnits)
        manager.configureInstalledSession()
        assertEquals(42.0, pumpState.displayStatus().snapshot?.reservoirUnits)
        whenever(provisioning.connectionSession()).thenReturn(connection.copy(generation = "replacement"))
        manager.configureInstalledSession()
        assertNull(pumpState.displayStatus().snapshot)
    }

    @Test
    fun `valid callback publishes only after decrypt CRC and decode`() {
        val pump = fixture.connect()
        stubStatus()
        val results = mutableListOf<Boolean>()
        pumpState.publishStatus(42.0, 50, false, 100, 1234L)
        assertTrue(pumpState.hasVerifiedStatus)

        manager.readStatus(results::add)

        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(42.0, pumpState.displayStatus().snapshot?.reservoirUnits)
        assertFalse(pumpState.displayStatus().isCurrent)
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

        assertEquals(listOf(true), results)
        assertTrue(pumpState.hasVerifiedStatus)
        assertEquals(5.5, pumpState.reservoirUnits)
        assertEquals(5.5, pumpState.displayStatus().snapshot?.reservoirUnits)
        assertTrue(pumpState.displayStatus().isCurrent)
        assertEquals(null, pumpState.statusSnapshot?.batteryPercent)
        assertEquals(2, pumpState.statusSnapshot?.batteryBars)
        assertEquals("1.3", pumpState.controlServiceVersion)
        verify(sessionCrypto).decrypt(eq(byteArrayOf(0x55)), any())
    }

    @Test
    fun `multi frame status is reassembled from the frames the pump returned`() {
        val pump = fixture.connect()
        stubStatus()
        val results = mutableListOf<Boolean>()
        manager.readStatus(results::add)
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x12) + ByteArray(19) { 0x41 })
        pump.answerRead(CHAR_EXTREAD, byteArrayOf(0x22, 0x42))
        assertEquals(listOf(true), results)
        verify(sessionCrypto).decrypt(eq(ByteArray(19) { 0x41 } + byteArrayOf(0x42)), any())
    }

    @Test
    fun `a status code 140 is attributed to the connection's verification attempt rather than globally`() {
        fixture.installSession(attemptId = "attempt")
        val pump = fixture.connect()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        pump.answerRead(CHAR_STATUS, ByteArray(0), 140)

        assertEquals(listOf(false), results)
        verify(provisioning).failCandidateOrRecord(
            eq(fixture.generation), eq("attempt"), eq(setOf(PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED)),
            any(), any(), anyOrNull(), eq(140)
        )
        verify(provisioning, never()).recordUnavailable(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
    }

    @Test
    fun `a key that cannot authenticate the status is reported as rejected`() {
        val pump = fixture.connect()
        whenever(sessionCrypto.decrypt(any(), any())).thenThrow(SessionCrypto.AuthenticationFailedException())
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

        assertEquals(listOf(false), results)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        verify(provisioning).failCandidateOrRecord(
            eq(fixture.generation), isNull(), eq(setOf(PumpSession.AvailabilityCause.KEY_REJECTED)),
            eq("encrypted-status"), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `cancelled owner cannot invalidate a later session sample`() {
        fixture.connect()
        val firstResults = mutableListOf<Boolean>()
        val attempt = manager.readStatus(firstResults::add)
        manager.disconnect()
        val next = fixture.connect()
        stubStatus()
        val nextResults = mutableListOf<Boolean>()
        manager.readStatus(nextResults::add)
        next.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
        assertFalse(attempt.cancel())
        assertEquals(listOf(false), firstResults)
        assertEquals(listOf(true), nextResults)
        assertEquals(5.5, pumpState.statusSnapshot?.reservoirUnits)
    }

    @Test
    fun `cancelling an active status read records one transport failure`() {
        fixture.connect()
        val results = mutableListOf<Boolean>()

        val attempt = manager.readStatus(results::add)
        attempt.cancel()

        assertEquals(listOf(false), results)
        verify(provisioning, times(1)).recordCandidateOrUnavailable(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    /** Starts a status read and answers it with [frame]; returns what the caller was told. */
    private fun readStatus(pump: FakeYpsoPump.Connection, frame: ByteArray = byteArrayOf(0x11, 0x55)): List<Boolean> {
        val results = mutableListOf<Boolean>()
        manager.readStatus(results::add)
        pump.answerRead(CHAR_STATUS, frame)
        return results
    }

    @Test
    fun `normal status logging excludes decrypted payload and therapy values`() {
        val pump = fixture.connect()
        clearInvocations(fixture.logger)
        stubStatus()

        readStatus(pump)

        val infoMessages = argumentCaptor<String>()
        verify(fixture.logger, atLeastOnce()).info(eq(LTag.PUMP), infoMessages.capture())
        assertTrue(infoMessages.allValues.contains("YpsoPump encrypted status accepted"))
        assertFalse(infoMessages.allValues.any { it.contains("reservoir=") || it.contains("basal=") || it.contains("raw=") })
        verify(fixture.logger, never()).debug(eq(LTag.PUMP), any<String>())
    }

    @Test
    fun `successful decryption cannot publish when independent serial verification is unavailable`() {
        val pump = fixture.connect()
        stubStatus()
        doThrow(SecurityException("Pump serial could not be independently observed"))
            .whenever(provisioning).markVerified(any(), anyOrNull(), isNull(), any())

        assertEquals(listOf(false), readStatus(pump))
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `matching GATT serial independently verifies status`() {
        fixture.pump.configure = { it.expose(FakeYpsoPump.SERIAL, properties = FakeYpsoPump.READ); it.values[FakeYpsoPump.SERIAL] = "10000001\u0000".toByteArray() }
        val pump = fixture.connect()
        stubStatus()

        assertEquals(listOf(true), readStatus(pump))
        assertTrue(pumpState.hasVerifiedStatus)
        verify(provisioning).markVerified(any(), anyOrNull(), eq("10000001"), any())
    }

    @Test
    fun `mismatched GATT serial cannot publish status`() {
        fixture.pump.configure = { it.expose(FakeYpsoPump.SERIAL, properties = FakeYpsoPump.READ); it.values[FakeYpsoPump.SERIAL] = "10000002\u0000".toByteArray() }
        val pump = fixture.connect()
        stubStatus()
        doThrow(SecurityException("Observed pump identity does not match configured serial"))
            .whenever(provisioning).markVerified(any(), anyOrNull(), eq("10000002"), any())

        assertEquals(listOf(false), readStatus(pump))
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `missing malformed or changed control version rejects status before publication`() {
        for (controlVersion in listOf<ByteArray?>(null, "1.3".toByteArray(), "1.3\u0000\u0000".toByteArray(), "1.4\u0000".toByteArray())) {
            val fixture = YpsoPumpFixture()
            fixture.pump.configure = { pump ->
                if (controlVersion == null) pump.characteristics.remove(CHAR_CONTROL_VERSION)
                else pump.values[CHAR_CONTROL_VERSION] = controlVersion
            }
            val pump = fixture.connect()
            fixture.decryptsTo(validStatusPayload())
            val results = mutableListOf<Boolean>()

            fixture.manager.readStatus(results::add)
            pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

            val label = "controlVersion=${controlVersion?.contentToString()}"
            assertEquals(listOf(false), results, label)
            assertFalse(fixture.pumpState.hasVerifiedStatus, label)
            assertEquals(ConnectionState.DISCONNECTED, fixture.pumpState.connectionState, label)
        }
    }

    @Test
    fun `invalid CRC fails once without publishing status`() {
        val pump = fixture.connect()
        stubStatus(ByteArray(20))

        assertEquals(listOf(false), readStatus(pump))
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
        assertTrue(pump.closed)
    }

    @Test
    fun `decrypt failure fails once without publishing status`() {
        val pump = fixture.connect()
        whenever(sessionCrypto.decrypt(any(), any())).thenThrow(SecurityException("authentication failed"))

        assertEquals(listOf(false), readStatus(pump))
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `publication failure does not add a generic encrypted-status cause`() {
        val pump = fixture.connect()
        stubStatus()
        whenever(provisioning.markVerified(any(), anyOrNull(), anyOrNull(), any()))
            .thenThrow(SecurityException("Configured serial does not match the connected pump"))

        assertEquals(listOf(false), readStatus(pump))
        verify(provisioning, never()).failCandidateOrRecord(
            any(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, never()).recordCandidateOrUnavailable(
            any(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
    }

    @Test
    fun `decoded short status fails without publishing`() {
        val pump = fixture.connect()
        stubStatus(YpsoCrc.appendCrc(byteArrayOf(0x01)))

        assertEquals(listOf(false), readStatus(pump))
        assertFalse(pumpState.hasVerifiedStatus)
        assertEquals(ConnectionState.DISCONNECTED, pumpState.connectionState)
    }

    @Test
    fun `empty frame fails once without decrypting`() {
        val pump = fixture.connect()

        assertEquals(listOf(false), readStatus(pump, byteArrayOf()))
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any(), any())
    }

    @Test
    fun `cancelled attempt suppresses late valid publication`() {
        val pump = fixture.connect()
        stubStatus()
        val results = mutableListOf<Boolean>()
        val attempt = manager.readStatus(results::add)

        assertTrue(attempt.cancel())
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `timeout fails once and ignores a late callback`() {
        val pump = fixture.connect()
        stubStatus()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        fixture.scheduler.fire()
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any(), any())
    }

    @Test
    fun `duplicate same UUID frame fails transaction once`() {
        val pump = fixture.connect()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x13) + ByteArray(19) { 1 })
        pump.answerRead(CHAR_EXTREAD, byteArrayOf(0x23) + ByteArray(19) { 2 })
        pump.answerRead(CHAR_EXTREAD, byteArrayOf(0x23) + ByteArray(19) { 2 })
        pump.answerRead(CHAR_EXTREAD, byteArrayOf(0x33, 0x03))

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        verify(sessionCrypto, never()).decrypt(any(), any())
        assertEquals(2, pump.reads.count { it == CHAR_EXTREAD })
    }

    @Test
    fun `changed frame total fails transaction`() {
        val pump = fixture.connect()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)
        pump.answerRead(CHAR_STATUS, byteArrayOf(0x12) + ByteArray(19) { 1 })
        pump.answerRead(CHAR_EXTREAD, byteArrayOf(0x23, 0x02))

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
    }

    @Test
    fun `immediate read dispatch failure completes caller once`() {
        fixture.pump.configure = { pump -> pump.refuseRead = { it == CHAR_STATUS } }
        val pump = fixture.connect()
        val results = mutableListOf<Boolean>()

        manager.readStatus(results::add)

        assertEquals(listOf(false), results)
        assertFalse(pumpState.hasVerifiedStatus)
        assertTrue(pump.closed)
    }

    @Test
    fun `status completion callback runs after the link lock is released`() {
        val pump = fixture.connect()
        stubStatus()
        var callbackEntered = false
        manager.readStatus {
            // The link lock is private; any link call from another thread must not wait for this callback.
            val probe = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                assertTrue(probe.submit<Boolean> { fixture.link.isActive(YpsoLinkOperation.STATUS); true }
                    .get(2, java.util.concurrent.TimeUnit.SECONDS), "domain callback ran while holding the link lock")
            } finally {
                probe.shutdownNow()
            }
            callbackEntered = true
        }

        pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))

        assertTrue(callbackEntered)
    }

    @Test
    fun `callback and close exceptions still allow the next session to read`() {
        val first = fixture.connect()
        first.teardownFailure = SecurityException("permission revoked")
        var completions = 0
        manager.readStatus { completions++; throw IllegalStateException("consumer failed") }
        manager.disconnect()
        assertEquals(1, completions)
        assertTrue(first.disconnected)
        assertTrue(first.closed, "a failed disconnect must not skip close")
        val next = fixture.connect()
        stubStatus()
        assertEquals(listOf(true), readStatus(next))
    }

    @Test
    fun `remote disconnect during an active read records one transport failure`() {
        val pump = fixture.connect()

        manager.readStatus { }
        pump.remoteDisconnect()

        // One physical disconnect is exactly one retryable transport report, and it must be the
        // teardown-owned one rather than a drained-read report.
        verify(provisioning, times(1)).recordCandidateOrUnavailable(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            anyOrNull(), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, times(1)).recordCandidateOrUnavailable(
            anyOrNull(), anyOrNull(), eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)),
            eq("gatt-disconnected"), any(), anyOrNull(), anyOrNull()
        )
        verify(provisioning, never()).recordUnavailable(
            eq(setOf(PumpSession.AvailabilityCause.TRANSPORT)), anyOrNull(), anyOrNull(), anyOrNull(), any()
        )
    }

    @Test
    fun `intentional local disconnect during an active read records no failure`() {
        fixture.connect()

        manager.readStatus { }
        manager.disconnect()

        verify(provisioning, never()).recordCandidateOrUnavailable(any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        verify(provisioning, never()).recordUnavailable(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(provisioning, never()).failCandidateOrRecord(any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `teardown during callback delivery cannot detach a read into a spurious report`() {
        val pump = fixture.connect()
        stubStatus()
        manager.readStatus { }
        var tornDown = false
        fixture.scheduler.onCancel = {
            // The timeout cancel runs between detaching a completed operation and delivering its
            // result; a teardown landing there must not turn the detached delivery into a failure.
            if (!tornDown) {
                tornDown = true
                manager.disconnect()
            }
        }

        pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
        assertTrue(tornDown, "the teardown seam never ran")

        verify(provisioning, never()).recordCandidateOrUnavailable(any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull())
        verify(provisioning, never()).recordUnavailable(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())
        verify(provisioning, never()).failCandidateOrRecord(any(), anyOrNull(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull())
    }

    private fun YpsoPumpFixture.firmware(master: String, control: String = "1.3") {
        pump.configure = { pump ->
            pump.values[FakeYpsoPump.MASTER_VERSION] = "$master\u0000".toByteArray()
            pump.values[FakeYpsoPump.SUPERVISOR_VERSION] = "$master\u0000".toByteArray()
            pump.values[CHAR_CONTROL_VERSION] = "$control\u0000".toByteArray()
        }
    }

    @Test
    fun `reboot recovery uses minimum firmware policy and requires fresh reconnect`() {
        for (firmware in listOf("V05.00.52", "V05.02.03", "V06.00.00")) {
            val fixture = YpsoPumpFixture().apply { firmware(firmware) }
            val old = fixture.connect()
            whenever(fixture.sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(validStatusPayload(), 9, 1))
            val results = mutableListOf<Boolean>()
            fixture.manager.readStatus(results::add)
            old.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
            assertEquals(listOf(false), results, firmware)
            assertFalse(fixture.pumpState.hasVerifiedStatus)
            assertEquals(ConnectionState.DISCONNECTED, fixture.pumpState.connectionState)
            val next = fixture.connect()
            whenever(fixture.sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(validStatusPayload(), 9, 2))
            fixture.manager.readStatus(results::add)
            old.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
            assertEquals(listOf(false), results, firmware)
            next.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
            assertEquals(listOf(false, true), results, firmware)
            assertEquals(9, fixture.session.snapshot()!!.reboot)
            assertEquals(9, fixture.manager.epoch()?.reboot)
        }
    }

    @Test
    fun `unsupported firmware or control cannot adopt reboot`() {
        for ((firmware, control) in listOf("V05.00.51" to "1.3", "invalid" to "1.3", "V05.02.03" to "1.4")) {
            val fixture = YpsoPumpFixture().apply { firmware(firmware, control) }
            val pump = fixture.connect()
            whenever(fixture.sessionCrypto.decrypt(any(), any())).thenReturn(SessionCrypto.Message(validStatusPayload(), 9, 1))
            fixture.manager.readStatus()
            pump.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
            fixture.connect()
            assertEquals(8, fixture.session.snapshot()!!.reboot, "$firmware/$control")
        }
    }

    // ---- therapy writes ----

    @Test
    fun `a bolus for another connection is never dispatched`() {
        val pump = fixture.connectWithProtocol()
        pump.expose(YpsoWritePolicy.BOLUS_START_STOP_UUID, YpsoWritePolicy.CONTROL_NOTIFY_UUID)
        val outcomes = mutableListOf<YpsoWriteOutcome>()

        manager.startBolus("bolus", YpsoBolusRequestValidator.validate(1.2, YpsoBolusTreatment.NORMAL, 30.0), "0:other", {}) { outcome, _ ->
            outcomes += outcome
        }

        assertTrue(outcomes.single() is YpsoWriteOutcome.NotSent)
        assertTrue(pump.writes.none { it.first == YpsoWritePolicy.BOLUS_START_STOP_UUID })
        assertEquals(0L, manager.writeCounter)
    }

    @Test
    fun `each therapy command reserves exactly its encoded payload`() {
        val pump = fixture.connectWithProtocol()
        pump.expose(YpsoWritePolicy.BOLUS_START_STOP_UUID, YpsoWritePolicy.TBR_START_STOP_UUID)
        pump.onDescriptorWrite = { pump.ackDescriptor() }
        val reserved = mutableListOf<String?>()
        fun acknowledge(uuid: java.util.UUID) {
            var acknowledged = 0
            while (acknowledged < pump.writes.count { it.first == uuid }) {
                acknowledged++
                pump.ackWrite(uuid)
            }
        }
        val evidence = "ab".repeat(32)
        val connection = checkNotNull(manager.epoch()).key

        // Outcome callbacks run inside the transport, so each owner is resolved after its acknowledgement.
        var bolusOwner: YpsoBleManager.BolusCommandOwner? = null
        manager.startBolus("bolus", YpsoBolusRequestValidator.validate(1.2, YpsoBolusTreatment.NORMAL, 30.0), connection, { reserved += it.payloadHash }) { _, owner ->
            bolusOwner = owner
        }
        acknowledge(YpsoWritePolicy.BOLUS_START_STOP_UUID)
        assertTrue(manager.verifyBolusAccepted(checkNotNull(bolusOwner), "bolus", evidence, "status showed the bolus"))
        pump.writes.clear()
        manager.cancelBolus("cancel", YpsoBolusBlock.SLOW, connection, { reserved += it.payloadHash }) { _, owner -> bolusOwner = owner }
        acknowledge(YpsoWritePolicy.BOLUS_START_STOP_UUID)
        assertTrue(manager.verifyBolusAccepted(checkNotNull(bolusOwner), "cancel", evidence, "status showed no slow bolus"))
        var tbrOwner: YpsoBleManager.TbrCommandOwner? = null
        manager.writeTbr("tbr", 150, 30, { reserved += it.payloadHash }) { _, owner -> tbrOwner = owner }
        acknowledge(YpsoWritePolicy.TBR_START_STOP_UUID)
        assertTrue(manager.verifyTbrAccepted(checkNotNull(tbrOwner), "tbr", evidence, "status showed the TBR"))

        assertEquals(
            listOf(
                YpsoCrc.appendCrc(BolusCommand(1.2).encode()),
                YpsoCrc.appendCrc(BolusCommand.cancelPayload(extended = true)),
                TbrCommand(150, 30).encode(),
            ).map(YpsoWriteAccounting::sha256),
            reserved,
        )
    }

    @Test
    fun `a TBR command whose journal and session saves outlast its send window leaves no frame and is not sent`() {
        val pump = fixture.connectWithProtocol()
        pump.expose(YpsoWritePolicy.TBR_START_STOP_UUID)
        pump.onDescriptorWrite = { pump.ackDescriptor() }
        var elapsed = 0L
        val link = app.aaps.pump.ypsopump.tbr.YpsoTbrBleLink(manager, readStatus = { true }, now = { 1_000L }, elapsed = { elapsed })

        // Saving the attempt stalls for 150 s, as a slow journal commit would.
        val evidence = link.command(0, 30, effective = { true }, beforeDispatch = { elapsed += 150_000L })

        assertTrue(evidence.result is app.aaps.pump.ypsopump.tbr.YpsoTbrWriteResult.NotSent, "${evidence.result}")
        assertEquals(1_000L, evidence.dispatchedAt)
        assertTrue(pump.writes.none { it.first == YpsoWritePolicy.TBR_START_STOP_UUID })
    }

    @Test
    fun `therapy and descriptor writes outside their operation never reach the pump`() {
        val pump = fixture.connectWithProtocol()
        pump.expose(YpsoWritePolicy.BOLUS_START_STOP_UUID, YpsoWritePolicy.TBR_START_STOP_UUID, YpsoWritePolicy.EVENT_INDEX_UUID)
        val frame = byteArrayOf(1, 2, 3)

        for (uuid in listOf(YpsoWritePolicy.BOLUS_START_STOP_UUID, YpsoWritePolicy.TBR_START_STOP_UUID)) {
            assertFalse(pump.writeCharacteristic(uuid, frame, YpsoRemoteWrite.THERAPY_COMMAND))
        }
        assertFalse(pump.writeCharacteristic(YpsoWritePolicy.EVENT_INDEX_UUID, frame, YpsoRemoteWrite.HISTORY_SELECTOR))
        for (category in YpsoRemoteWrite.entries.filter { it != YpsoRemoteWrite.CONTROL_NOTIFICATION_DESCRIPTOR }) {
            assertFalse(pump.writeDescriptor(YpsoWritePolicy.CONTROL_NOTIFY_UUID, YpsoWritePolicy.CCCD_UUID, byteArrayOf(1, 0), category))
        }
        // A link that does not own the operation refuses its frame too.
        assertFalse(fixture.link.writeFrame(checkNotNull(manager.epoch()), YpsoLinkLease(YpsoLinkOperation.BOLUS), frame))
        assertTrue(pump.writes.none { it.first != YpsoGattLayout.CHAR_AUTH })
        assertTrue(pump.descriptorWrites.isEmpty())
    }

    @Test
    fun `an identity read delivered after its connection was replaced cannot overwrite the successor`() {
        val serials = ArrayDeque(listOf(null, "10000002"))
        fixture.pump.configure = { connection ->
            connection.expose(FakeYpsoPump.SERIAL, properties = FakeYpsoPump.READ)
            // The first connection's serial read waits; the successor answers at once.
            serials.removeFirst()?.let { connection.values[FakeYpsoPump.SERIAL] = "$it\u0000".toByteArray() }
        }
        val first = fixture.connect()
        val firstResults = mutableListOf<Boolean>()
        manager.readStatus(firstResults::add)
        lateinit var successor: FakeYpsoPump.Connection
        var replaced = false
        // The operation has been detached and accepted for delivery; replace the connection before the
        // result reaches the identity reader.
        fixture.scheduler.onCancel = {
            if (!replaced) {
                replaced = true
                manager.disconnect()
                successor = fixture.connect()
                manager.readStatus { }
            }
        }

        first.answerRead(FakeYpsoPump.SERIAL, "10000001\u0000".toByteArray())

        assertTrue(replaced, "the replacement seam never ran")
        assertEquals("10000002", pumpState.observedIdentitySerial)
        assertEquals("V05.00.52", pumpState.masterVersion)
        assertEquals(listOf(false), firstResults)
        stubStatus()
        successor.answerRead(CHAR_STATUS, byteArrayOf(0x11, 0x55))
        verify(provisioning).markVerified(any(), anyOrNull(), eq("10000002"), any())
        verify(provisioning, never()).markVerified(any(), anyOrNull(), eq("10000001"), any())
    }

    private fun validStatusPayload(): ByteArray {
        val body = ByteArray(18)
        body[0] = 0x0a
        body[1] = 0x26
        body[2] = 0x02
        body[5] = 0x02
        body[6] = 0x55
        body[10] = 0x64
        return YpsoCrc.appendCrc(body)
    }
}
