package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandAction
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
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.inject.Provider
import javax.xml.parsers.DocumentBuilderFactory

class PumpActivityPresentationTest {

    // The shipped wording, so these tests read exactly what a user would see. Amounts are joined to
    // their units with no-break spaces so a line wrap never strands a unit on its own line.
    private val strings: Map<Int, String> = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .filter { it.getAttribute("name").startsWith("ypsopump_queue_") }
                .associate { R.string::class.java.getField(it.getAttribute("name")).getInt(null) to it.textContent.replace("\\u00A0", "\u00A0") }
        }

    private val rh: ResourceHelper = mock {
        on { gs(any()) } doAnswer { strings.getValue(it.getArgument(0)) }
        on { gs(any(), anyVararg()) } doAnswer {
            String.format(Locale.US, strings.getValue(it.getArgument(0)), *it.arguments.drop(1).toTypedArray())
        }
    }

    private fun command(
        type: Command.CommandType,
        action: CommandAction = CommandAction.Other,
        callback: Callback? = null,
    ): Command = mock {
        on { commandType } doReturn type
        on { this.action } doReturn action
        on { this.callback } doReturn callback
        on { status() } doReturn "INTERNAL ${type.name}"
    }

    private fun queue(running: Command?, vararg waiting: Command): CommandQueue = mock {
        on { snapshot() } doReturn QueueSnapshot(running, waiting.toList())
    }

    private fun texts(running: Command?, vararg waiting: Command) = pumpActivityItems(queue(running, *waiting), rh).map { it.text }

    @Test
    fun `empty queue is idle`() {
        assertThat(pumpActivityItems(queue(null), rh)).isEmpty()
    }

    @Test
    fun `running and waiting tasks are named in execution order and labelled`() {
        val items = pumpActivityItems(
            queue(
                command(Command.CommandType.READSTATUS),
                command(Command.CommandType.BOLUS, CommandAction.Bolus(1.5, 0)),
                command(Command.CommandType.TEMPBASAL, CommandAction.TempBasalPercent(120, 30)),
            ),
            rh
        )

        assertThat(items).containsExactly(
            QueueItem("Check pump status", true, "In progress"),
            QueueItem("Give bolus of 1.50\u00A0U", false, "Waiting"),
            QueueItem("Set temporary basal rate to 120% for 30\u00A0min", false, "Waiting"),
        ).inOrder()
    }

    @Test
    fun `insulin tasks are worded plainly and keep the dose and its units`() {
        assertThat(
            texts(
                command(Command.CommandType.SMB_BOLUS, CommandAction.AutomaticBolus(0.3)),
                command(Command.CommandType.EXTENDEDBOLUS, CommandAction.ExtendedBolus(2.0, 60)),
                command(Command.CommandType.TEMPBASAL, CommandAction.TempBasalAbsolute(0.85, 45)),
                command(Command.CommandType.TEMPBASAL, CommandAction.TempBasalPercent(0, 90)),
            )
        ).containsExactly(
            "Give automatic bolus of 0.30\u00A0U",
            "Give extended bolus of 2.00\u00A0U over 1\u00A0h",
            "Set temporary basal rate to 0.85\u00A0U/h for 45\u00A0min",
            "Set temporary basal rate to 0% for 1\u00A0h\u00A030\u00A0min",
        ).inOrder()
    }

    @Test
    fun `cancellations say delivery stops rather than naming a dose`() {
        assertThat(
            texts(
                command(Command.CommandType.TEMPBASAL, CommandAction.CancelTempBasal),
                command(Command.CommandType.EXTENDEDBOLUS, CommandAction.CancelExtendedBolus),
            )
        ).containsExactly("Stop temporary basal rate", "Stop extended bolus").inOrder()
    }

    @Test
    fun `a carbs-only entry is not described as a bolus`() {
        assertThat(texts(command(Command.CommandType.BOLUS, CommandAction.Bolus(0.0, 20)))).containsExactly("Record 20\u00A0g of carbs")
        assertThat(texts(command(Command.CommandType.BOLUS, CommandAction.Bolus(2.0, 20)))).containsExactly("Give bolus of 2.00\u00A0U")
    }

    @Test
    fun `no row shows the queue's internal text`() {
        val everyType = Command.CommandType.entries.map { command(it) }

        val shown = texts(null, *everyType.toTypedArray())

        assertThat(shown.filter { it.startsWith("INTERNAL") }).isEmpty()
        assertThat(shown.filter { it.any(Char::isLowerCase).not() }).isEmpty()
    }

    @Test
    fun `basal profile command is described as a check because Ypso cannot send a profile`() {
        assertThat(texts(command(Command.CommandType.BASAL_PROFILE))).containsExactly("Check the basal profile on the pump")
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

        assertThat(
            texts(
                command(Command.CommandType.READSTATUS, callback = profileRead),
                command(Command.CommandType.READSTATUS, callback = programCheck),
            )
        ).containsExactly(
            "Read basal profiles from the pump",
            "Check which basal profile the pump is using",
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
            on { gs(any(), anyVararg()) } doReturn "text"
        }
        return YpsoPumpPlugin(
            AAPSLoggerTest(), resources, mock<Preferences>(), commandQueue, YpsoPumpState(), mock<YpsoBleManager>(),
            mock<PumpSync>(), mock<RxBus>(), mock<UiInteraction>(),
            Provider { PumpEnactResultObject(resources).success(true).enacted(true) },
            mock<YpsoProvisioningService>(), mock<ProfileFunction>(), constraints, mock<AppLifecycle>()
        )
    }
}
