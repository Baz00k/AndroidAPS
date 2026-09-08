package app.aaps.pump.medtrum.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsTone
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.code.EventType
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.di.MedtrumPluginQualifier
import app.aaps.pump.medtrum.ui.compose.MedtrumOverviewScreen
import app.aaps.pump.medtrum.ui.compose.MedtrumOverviewState
import app.aaps.pump.medtrum.ui.compose.MedtrumRow
import app.aaps.pump.medtrum.ui.viewmodel.MedtrumOverviewViewModel
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Medtrum patch status.
 *
 * UI is Compose ([MedtrumOverviewScreen]); the 745-line data-binding layout it replaces was the last
 * screen in the app still rendering as stock AAPS, and it is the pump tab of the pump that is actually
 * running the loop.
 *
 * The ViewModel is untouched — it still owns the state, the refresh/reset actions and the patch-change
 * decision. This fragment only observes it and hands the values to Compose, so the activation wizard
 * (which shares the ViewModel's event channel) keeps working exactly as before.
 */
class MedtrumOverviewFragment : DaggerFragment() {

    @Inject @MedtrumPluginQualifier lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var medtrumPump: MedtrumPump
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var rh: ResourceHelper

    private var disposable: CompositeDisposable = CompositeDisposable()
    private lateinit var viewModel: MedtrumOverviewViewModel
    private val state = mutableStateOf(MedtrumOverviewState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewModel = ViewModelProvider(this, viewModelFactory)[MedtrumOverviewViewModel::class.java]
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    MedtrumOverviewScreen(
                        state = state.value,
                        onRefresh = { viewModel.onClickRefresh() },
                        onResetAlarms = { viewModel.onClickResetAlarms() },
                        onChangePatch = { viewModel.onClickChangePatch() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        state.value = state.value.copy(
            changePatchLabel = rh.gs(R.string.change_patch_label),
            refreshLabel = rh.gs(R.string.refresh_label),
            resetAlarmsLabel = rh.gs(R.string.reset_alarms_label),
            title = medtrumPump.pumpType().description,
            reservoirMax = medtrumPump.pumpType().maxReservoirReading().toDouble()
        )

        viewModel.eventHandler.observe(viewLifecycleOwner) { evt ->
            when (evt.peekContent()) {
                EventType.CHANGE_PATCH_CLICKED -> requireContext().apply {
                    protectionCheck.queryProtection(
                        requireActivity(),
                        ProtectionCheck.Protection.PREFERENCES,
                        {
                            val nextStep = when {
                                medtrumPump.pumpState == MedtrumPumpState.STOPPED                                                                                           ->
                                    PatchStep.PREPARE_PATCH

                                medtrumPump.pumpState == MedtrumPumpState.NONE && !medtrumPump.patchPrimed                                                                  ->
                                    PatchStep.PREPARE_PATCH

                                medtrumPump.pumpState <= MedtrumPumpState.EJECTED && !(medtrumPump.pumpState < MedtrumPumpState.PRIMING && medtrumPump.patchPrimed)          ->
                                    PatchStep.RETRY_ACTIVATION

                                else                                                                                                                                        ->
                                    PatchStep.START_DEACTIVATION
                            }
                            startActivity(MedtrumActivity.createIntentFromMenu(this, nextStep))
                        }
                    )
                }

                EventType.PROFILE_NOT_SET      ->
                    OKDialog.show(requireActivity(), rh.gs(app.aaps.core.ui.R.string.message), rh.gs(R.string.no_profile_selected))

                EventType.SERIAL_NOT_SET       ->
                    OKDialog.show(requireActivity(), rh.gs(app.aaps.core.ui.R.string.message), rh.gs(R.string.no_sn_in_settings))
            }
        }

        // LiveData -> state
        viewModel.bleStatus.bind { copy(bleStatus = it) }
        // The ViewModel posts a bare "3m ago"; under the title that could be read as anything.
        viewModel.lastConnectionMinAgo.bind { copy(lastConnection = rh.gs(R.string.last_connection_label) + " " + it) }
        viewModel.canDoRefresh.bind { copy(canRefresh = it) }
        viewModel.canDoResetAlarms.bind { copy(canResetAlarms = it) }
        viewModel.activeAlarms.bind { copy(activeAlarms = it) }
        listOf(viewModel.lastBolus, viewModel.activeBolusStatus, viewModel.pumpType, viewModel.fwVersion,
               viewModel.patchNo, viewModel.patchExpiry, viewModel.patchAge).forEach { ld ->
            ld.observe(viewLifecycleOwner) { rebuildRows() }
        }

        // StateFlow -> state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    medtrumPump.pumpStateFlow, medtrumPump.lastBasalTypeFlow, medtrumPump.lastBasalRateFlow,
                    medtrumPump.reservoirFlow, medtrumPump.batteryVoltage_BFlow
                ) { pumpState, _, _, reservoir, battery -> Triple(pumpState, reservoir, battery) }
                    .collect { (pumpState, reservoir, battery) ->
                        state.value = state.value.copy(
                            pumpState = pumpState.name.replace('_', ' '),
                            tone = pumpState.tone(),
                            reservoir = reservoir,
                            reservoirText = rh.gs(R.string.reservoir_level, reservoir).trim(),
                            batteryText = rh.gs(R.string.battery_voltage, battery).trim()
                        )
                        rebuildRows()
                    }
            }
        }
    }

    /** Rebuild the two row lists from whatever the ViewModel and pump currently hold. */
    private fun rebuildRows() {
        val basal = String.format(Locale.getDefault(), "%.2f U/h", medtrumPump.lastBasalRateFlow.value)
        state.value = state.value.copy(
            status = listOf(
                MedtrumRow(rh.gs(R.string.ble_status_label), state.value.bleStatus),
                MedtrumRow(rh.gs(R.string.basal_type_label), medtrumPump.lastBasalTypeFlow.value.name.replace('_', ' ')),
                MedtrumRow(rh.gs(R.string.basal_rate_label), basal),
                MedtrumRow(rh.gs(app.aaps.core.ui.R.string.last_bolus_label), viewModel.lastBolus.value ?: ""),
                MedtrumRow(rh.gs(R.string.active_bolus_label), viewModel.activeBolusStatus.value ?: "")
            ).filter { it.value.isNotBlank() },
            patch = listOf(
                MedtrumRow(rh.gs(R.string.patch_no_label), viewModel.patchNo.value ?: ""),
                MedtrumRow(rh.gs(R.string.patch_activation_time_label), viewModel.patchAge.value ?: ""),
                MedtrumRow(rh.gs(R.string.patch_expiry_label), viewModel.patchExpiry.value ?: ""),
                MedtrumRow(rh.gs(R.string.pump_type_label), viewModel.pumpType.value ?: ""),
                MedtrumRow(rh.gs(R.string.fw_version_label), viewModel.fwVersion.value ?: "")
            ).filter { it.value.isNotBlank() }
        )
    }

    /**
     * Pill tone for a pump state. The enum's ordinal is not a health scale — OCCLUSION, PATCH_FAULT,
     * BATTERY_OUT and STOPPED all sort *above* ACTIVE — so a fault must be matched explicitly rather
     * than compared against ACTIVE.
     */
    private fun MedtrumPumpState.tone(): AapsTone = when (this) {
        MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT -> AapsTone.InRange
        MedtrumPumpState.PAUSED                              -> AapsTone.High
        MedtrumPumpState.OCCLUSION, MedtrumPumpState.EXPIRED, MedtrumPumpState.RESERVOIR_EMPTY,
        MedtrumPumpState.PATCH_FAULT, MedtrumPumpState.PATCH_FAULT2, MedtrumPumpState.BASE_FAULT,
        MedtrumPumpState.BATTERY_OUT, MedtrumPumpState.NO_CALIBRATION, MedtrumPumpState.STOPPED
                                                             -> AapsTone.Low

        else                                                 -> if (isSuspendedByPump()) AapsTone.High else AapsTone.Neutral
    }

    private fun <T> LiveData<T>.bind(reduce: MedtrumOverviewState.(T) -> MedtrumOverviewState) =
        observe(viewLifecycleOwner) { v -> state.value = state.value.reduce(v); rebuildRows() }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }
}
