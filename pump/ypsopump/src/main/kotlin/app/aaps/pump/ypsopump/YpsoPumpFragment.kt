package app.aaps.pump.ypsopump

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.compactDurationLabel
import app.aaps.pump.ypsopump.compose.PumpStatusRow
import app.aaps.pump.ypsopump.compose.PumpStatusScreen
import app.aaps.pump.ypsopump.compose.PumpStatusState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.crypto.KeyTiming
import app.aaps.pump.ypsopump.compose.keyDateLabel
import dagger.android.support.DaggerFragment
import javax.inject.Inject

/**
 * YpsoPump driver tab: Compose status view over [YpsoPumpState] + [CommandQueue].
 */
class YpsoPumpFragment : DaggerFragment() {

    @Inject lateinit var pumpState: YpsoPumpState
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var provisioning: YpsoProvisioningService

    private val state = mutableStateOf(PumpStatusState())
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            build()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    PumpStatusScreen(state.value)
                }
            }
        }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun build() {
        val installed = provisioning.installed()
        val timing = installed?.keyTiming?.status(installed.createdAt?.toEpochMilli(), installed.importedAt?.toEpochMilli(), dateUtil.now())
        state.value = buildPumpStatusState(pumpState, commandQueue, dateUtil, rh, timing)
    }
}

/** Build one render from one measurement snapshot, even at the expiry boundary. */
internal fun buildPumpStatusState(
    pumpState: YpsoPumpState,
    commandQueue: CommandQueue,
    dateUtil: DateUtil,
    rh: ResourceHelper,
    keyTiming: KeyTiming.Status? = null,
): PumpStatusState {
    val status = pumpStatusPresentation(pumpState, rh)
    val snapshot = status.snapshot
    // Ordered by how much the value can change what the user does next: what the pump is currently
    // delivering, then how well that is known, then unchanging identity.
    val rows = buildList {
        if (snapshot != null) {
            add(PumpStatusRow(rh.gs(R.string.ypsopump_last_status), dateUtil.minOrSecAgo(rh, snapshot.acquiredAt)))
        }
        if (pumpState.profileConfigurationReadAt > 0) {
            add(PumpStatusRow(rh.gs(R.string.ypsopump_last_read_program), pumpState.lastReadProgram))
            add(PumpStatusRow(rh.gs(R.string.ypsopump_profile_read_at), compactDurationLabel(dateUtil.now() - pumpState.profileConfigurationReadAt)))
        }
        if (pumpState.serialNumber.isNotEmpty()) add(PumpStatusRow(rh.gs(R.string.ypsopump_serial), pumpState.serialNumber))
        keyTiming?.let {
            val label = when (it.origin) {
                KeyTiming.Origin.SOURCE -> R.string.ypsopump_key_expiry_source_short
                KeyTiming.Origin.IMPORT_ESTIMATE -> R.string.ypsopump_key_expiry_estimate_short
                KeyTiming.Origin.USER -> R.string.ypsopump_key_expiry_user_short
                KeyTiming.Origin.UNKNOWN -> R.string.ypsopump_key_unknown
            }
            add(PumpStatusRow(rh.gs(label), if (it.expiryDue) rh.gs(R.string.ypsopump_key_overdue_short)
                else it.expiresAt?.let(::keyDateLabel) ?: rh.gs(R.string.ypsopump_key_unknown)))
        }
        if (pumpState.firmwareVersion.isNotEmpty()) add(PumpStatusRow(rh.gs(R.string.ypsopump_firmware), pumpState.firmwareVersion))
    }
    val presentation = pumpSetupPresentation(
        causes = pumpState.availability.causes,
        hasSavedDetails = pumpState.claimedSerialNumber.isNotBlank(),
        verified = pumpState.serialNumber.isNotBlank(),
    )
    return PumpStatusState(
        title = "YpsoPump",
        connectionSummary = status.connectionSummary,
        alert = when (pumpState.profileComparison) {
            YpsoPumpState.ProfileComparison.MISMATCH -> rh.gs(R.string.ypsopump_profile_mismatch_notification, pumpState.lastReadProgram)
            else                                     -> null
        },
        connectionAction = when {
            presentation != PumpSetupPresentation.READY -> rh.gs(presentation.message)
            else -> null
        },
        connectionHealthy = status.connectionHealthy,
        reservoir = snapshot?.reservoirUnits,
        // Bars are the internal wire representation; the UI only ever shows the mapped percent.
        battery = status.battery,
        unavailableLabel = rh.gs(R.string.ypsopump_value_unavailable),
        rows = rows,
        queue = pumpActivityItems(commandQueue, rh),
        queueTitle = rh.gs(R.string.ypsopump_queue_title),
        queueIdleLabel = rh.gs(R.string.ypsopump_queue_idle),
    )
}
