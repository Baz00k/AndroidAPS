package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.QueueSnapshot
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.pump.PumpEnactResultObject
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.compose.QueueItem
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import javax.inject.Provider

class PumpActivityPresentationTest {

    private val rh: ResourceHelper = mock {
        on { gs(R.string.ypsopump_queue_running) } doReturn "Running"
        on { gs(R.string.ypsopump_queue_waiting) } doReturn "Waiting"
        on { gs(R.string.ypsopump_queue_read_status) } doReturn "Reading pump status"
        on { gs(R.string.ypsopump_queue_read_profiles) } doReturn "Reading basal profiles from the pump"
        on { gs(R.string.ypsopump_queue_check_program) } doReturn "Checking which schedule the pump has active"
        on { gs(R.string.ypsopump_queue_check_profile) } doReturn "Checking the pump basal profile"
    }

    private fun command(type: Command.CommandType, status: String, callback: Callback? = null): Command = mock {
        on { commandType } doReturn type
        on { this.status() } doReturn status
        on { this.callback } doReturn callback
    }

    private fun queue(running: Command?, vararg waiting: Command): CommandQueue = mock {
        on { snapshot() } doReturn QueueSnapshot(running, waiting.toList())
    }

    @Test
    fun `empty queue is idle`() {
        assertThat(pumpActivityItems(queue(null), rh)).isEmpty()
    }

    @Test
    fun `status read is described in plain words without the internal reason`() {
        val read = command(Command.CommandType.READSTATUS, "READSTATUS Scheduled Status Refresh")

        val items = pumpActivityItems(queue(read), rh)

        assertThat(items).containsExactly(QueueItem("Reading pump status", true, "Running"))
    }

    @Test
    fun `each waiting command is named in execution order instead of counted`() {
        val running = command(Command.CommandType.READSTATUS, "READSTATUS keepalive")
        val bolus = command(Command.CommandType.BOLUS, "BOLUS 1.50 U")
        val tbr = command(Command.CommandType.TEMPBASAL, "TEMP BASAL 120% 30 min")

        val items = pumpActivityItems(queue(running, bolus, tbr), rh)

        assertThat(items).containsExactly(
            QueueItem("Reading pump status", true, "Running"),
            QueueItem("BOLUS 1.50 U", false, "Waiting"),
            QueueItem("TEMP BASAL 120% 30 min", false, "Waiting"),
        ).inOrder()
    }

    @Test
    fun `insulin commands keep their own description so the dose and units stay visible`() {
        val items = pumpActivityItems(
            queue(
                command(Command.CommandType.SMB_BOLUS, "SMB BOLUS 0.30 U"),
                command(Command.CommandType.EXTENDEDBOLUS, "EXTENDED BOLUS 2.00 U 60 min"),
            ),
            rh
        )

        assertThat(items.map { it.text }).containsExactly("SMB BOLUS 0.30 U", "EXTENDED BOLUS 2.00 U 60 min")
    }

    @Test
    fun `basal profile command is described as a check because Ypso cannot send a profile`() {
        val items = pumpActivityItems(queue(command(Command.CommandType.BASAL_PROFILE, "SET PROFILE")), rh)

        assertThat(items.single().text).isEqualTo("Checking the pump basal profile")
    }

    @Test
    fun `explicit profile reads say which read is running`() {
        val captured = argumentCaptor<Callback>()
        val queued: CommandQueue = mock()
        val plugin = plugin(queued)

        plugin.queueConfigurationRead(YpsoPumpPlugin.PROFILE_READ_REASON) {}
        plugin.queueConfigurationRead(YpsoPumpPlugin.ACTIVE_PROGRAM_REASON) {}
        org.mockito.kotlin.verify(queued).readStatus(eq(YpsoPumpPlugin.PROFILE_READ_REASON), captured.capture())
        val profileRead = captured.lastValue
        org.mockito.kotlin.verify(queued).readStatus(eq(YpsoPumpPlugin.ACTIVE_PROGRAM_REASON), captured.capture())
        val programCheck = captured.lastValue

        val items = pumpActivityItems(
            queue(
                command(Command.CommandType.READSTATUS, "READSTATUS", profileRead),
                command(Command.CommandType.READSTATUS, "READSTATUS", programCheck),
            ),
            rh
        )

        assertThat(items.map { it.text }).containsExactly(
            "Reading basal profiles from the pump",
            "Checking which schedule the pump has active",
        ).inOrder()
    }

    private fun plugin(commandQueue: CommandQueue): YpsoPumpPlugin {
        val maxBolus: Constraint<Double> = mock { onGeneric { value() } doReturn 30.0 }
        val constraints: ConstraintsChecker = mock {
            on { getMaxBolusAllowed() } doReturn maxBolus
            on { getMaxExtendedBolusAllowed() } doReturn maxBolus
        }
        val resources: ResourceHelper = mock {
            on { gs(any()) } doReturn "text"
            on { gs(any(), org.mockito.kotlin.anyVararg()) } doReturn "text"
        }
        return YpsoPumpPlugin(
            AAPSLoggerTest(), resources, mock<Preferences>(), commandQueue, YpsoPumpState(), mock<YpsoBleManager>(),
            mock<PumpSync>(), mock<RxBus>(), mock<UiInteraction>(),
            Provider { PumpEnactResultObject(resources).success(true).enacted(true) },
            mock<YpsoProvisioningService>(), mock<ProfileFunction>(), constraints, mock<AppLifecycle>()
        )
    }
}
