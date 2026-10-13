package app.aaps.plugins.main.general.overview

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.Overview
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventLoopUpdateGui
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.formatBolus
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.objects.extensions.toStringMedium
import app.aaps.plugins.main.general.actions.ExtendedBolusActions
import app.aaps.plugins.main.general.actions.TherapyActionAvailability
import app.aaps.plugins.main.general.overview.compose.HomeMenuItem
import app.aaps.plugins.main.general.overview.compose.homeActionLayout
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.utils.compactDurationLabel
import app.aaps.plugins.main.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.aaps.core.interfaces.profile.Profile
import app.aaps.plugins.main.general.overview.compose.BASAL_SAMPLE_MS
import app.aaps.plugins.main.general.overview.compose.batterySupply
import app.aaps.plugins.main.general.overview.compose.reservoirSupply
import app.aaps.plugins.main.general.overview.compose.CHART_HISTORY_MS
import app.aaps.plugins.main.general.overview.compose.CHART_MAX_FUTURE_MS
import app.aaps.plugins.main.general.overview.compose.ChartPanState
import app.aaps.plugins.main.general.overview.compose.ChartWindow
import app.aaps.plugins.main.general.overview.compose.rememberChartInsets
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsTone
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.main.general.overview.compose.HomeActions
import app.aaps.plugins.main.general.overview.compose.HomeScreen
import app.aaps.plugins.main.general.overview.compose.TreatmentKind
import app.aaps.plugins.main.general.overview.compose.HomeGlucoseChart
import app.aaps.plugins.main.general.overview.compose.buildHomePredictions
import app.aaps.plugins.main.general.overview.compose.AdditionalGraphData
import app.aaps.plugins.main.general.overview.compose.AdditionalGraphSettings
import app.aaps.plugins.main.general.overview.compose.AdditionalSeries
import app.aaps.plugins.main.general.overview.compose.HomeGraphSettings
import app.aaps.plugins.main.general.overview.compose.forDisplay
import app.aaps.plugins.main.general.overview.compose.HomeAdditionalGraphs
import app.aaps.plugins.main.general.overview.compose.HomeChartData
import app.aaps.plugins.main.general.overview.compose.GlucosePoint
import app.aaps.plugins.main.general.overview.compose.ChartTreatment
import app.aaps.plugins.main.general.overview.compose.BasalStep
import app.aaps.core.data.model.BS
import app.aaps.plugins.main.general.overview.compose.HomeUiState
import app.aaps.plugins.main.general.overview.compose.HomeGlucose
import app.aaps.plugins.main.general.overview.compose.recentInsulinEntries
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.plugins.main.general.overview.notifications.events.EventUpdateOverviewNotification
import app.aaps.plugins.main.general.overview.ui.StatusLightHandler
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.roundToInt

class OverviewFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var statusLightHandler: StatusLightHandler
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var nsSettingsStatus: NSSettingsStatus
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var xDripSource: XDripSource
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var overview: Overview
    @Inject lateinit var bgQualityCheck: BgQualityCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var calculationWorkflow: CalculationWorkflow
    @Inject lateinit var extendedBolusActions: ExtendedBolusActions

    private val disposable = CompositeDisposable()
    private val extendedBolusCancelGuard = ExtendedBolusActions.CancelGuard()

    private var smallWidth = false
    private var smallHeight = false
    private var axisWidth: Int = 0
    private var composeHome: ComposeView? = null
    private val graphSettings = mutableStateOf(HomeGraphSettings())
    private val additionalGraphSettings = mutableStateOf(AdditionalGraphSettings.decode(""))
    private val chartData = mutableStateOf(HomeChartData())
    private var sensorGlucose = HomeGlucose()
    // The visible range is UI state: switching it re-windows the loaded snapshot immediately, with
    // no rebuild. The horizontal position is shared by every graph panel and never read off-thread.
    private val chartRangeHours = mutableStateOf(6)
    private val chartPan = ChartPanState()
    private lateinit var refreshLoop: Runnable
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)



    // ---- Redesigned Home (Compose overlay) ----
    private val homeState = mutableStateOf(HomeUiState())
    // Recent carb records for the COB-tap undo sheet. Computed off the UI thread in updateIobCob()
    // (which already reads persistence there) and read synchronously by buildHomeState().
    private var recentCarbs: List<HomeUiState.CarbEntry> = emptyList()
    private var recentInsulin: List<HomeUiState.InsulinEntry> = emptyList()

    //@SuppressLint("NewApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).also { composeHome = it }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        axisWidth = when {
            resources.displayMetrics.densityDpi <= 120 -> 3
            resources.displayMetrics.densityDpi <= 160 -> 10
            resources.displayMetrics.densityDpi <= 320 -> 35
            resources.displayMetrics.densityDpi <= 420 -> 50
            resources.displayMetrics.densityDpi <= 560 -> 70
            else                                       -> 80
        }

        // ---- Redesigned Home (Compose) ----
        graphSettings.value = HomeGraphSettings.decode(preferences.get(StringNonKey.OverviewGlucoseGraphSettings))
        additionalGraphSettings.value = AdditionalGraphSettings.decode(preferences.get(StringNonKey.OverviewAdditionalGraphs))
        chartRangeHours.value = overviewData.rangeToDisplay
        val actions = buildHomeActions()
        composeHome?.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeHome?.setContent {
            AapsTheme {
                val settings = graphSettings.value
                val displayed = remember(chartData.value, settings) { chartData.value.forDisplay(settings) }
                // Width depends on the chosen range and whether forecasts are switched on — never on
                // whether a forecast is currently available.
                val window = ChartWindow.of(chartRangeHours.value, settings.forecasts.isNotEmpty())
                val insets = rememberChartInsets(displayed, additionalGraphSettings.value)
                HomeScreen(
                    state = homeState.value,
                    actions = actions,
                    graph = { HomeGlucoseChart(displayed, window, chartPan, Modifier.fillMaxWidth(), settings, insets) },
                    additionalGraphs = { HomeAdditionalGraphs(displayed, additionalGraphSettings.value, window, chartPan, insets) }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // Preferences is a separate activity; this fragment can resume without recreating its view.
        graphSettings.value = HomeGraphSettings.decode(preferences.get(StringNonKey.OverviewGlucoseGraphSettings))
        additionalGraphSettings.value = AdditionalGraphSettings.decode(preferences.get(StringNonKey.OverviewAdditionalGraphs))
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateIobCob() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ refreshChart() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           overviewData.rangeToDisplay = it.hours
                           // Re-window now and move the selector with it, instead of waiting for
                           // the next rebuild of the home state.
                           chartRangeHours.value = it.hours
                           homeState.value = homeState.value.copy(graphRangeHours = it.hours)
                           preferences.put(IntNonKey.RangeToDisplay, it.hours)
                           rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
                           preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventLoopUpdateGui::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewBG::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           if (it.now) refreshAll()
                           else scheduleUpdateGUI()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                           overviewData.pumpStatus = it.getStatus(requireContext())
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)

        // Coming back to Home shows the present, not wherever the graph was left paused.
        chartPan.returnToLive()

        refreshLoop = Runnable {
            refreshAll()
            handler.postDelayed(refreshLoop, 60 * 1000L)
        }
        handler.postDelayed(refreshLoop, 60 * 1000L)

        // Graph series are not prepared while the overview is off screen (see
        // CalculationWorkflowImpl.runCalculation), so rebuild them now rather than showing a stale
        // or empty chart until the next CGM tick. Presentation only - no IOB/COB, no loop.
        calculationWorkflow.runGraphsOnly(iobCobCalculator, overviewData)

        handler.post { refreshAll() }

        popupBolusDialogIfRunning(onClick = false)
    }

    fun refreshAll() {
        if (!config.appInitialized) return
        // updateIobCob() is the fast path that refreshes the Compose hero's IOB/COB about a second
        // after a carb entry, instead of waiting for the next 60 s tick.
        updateIobCob()
        refreshChart()
        runOnUiThread {
            composeHome ?: return@runOnUiThread
            buildHomeState()
        }
    }

    // region ---- Redesigned Home (Compose) ----

    /** Wire the Compose Home actions to the SAME protected dialog paths as the legacy buttons. */
    private fun buildHomeActions(): HomeActions {
        fun bolusProtected(run: () -> Unit) = activity?.let { act ->
            if (childFragmentManager.isStateSaved) return@let
            protectionCheck.queryProtection(act, ProtectionCheck.Protection.BOLUS, UIRunnable { if (isAdded) run() })
        }
        return HomeActions(
            onCalculator = { bolusProtected { uiInteraction.runWizardDialog(childFragmentManager) } },
            onCarbs = { bolusProtected { uiInteraction.runCarbsDialog(childFragmentManager) } },
            onInsulin = { bolusProtected { uiInteraction.runInsulinDialog(childFragmentManager) } },
            onTempTarget = { bolusProtected { uiInteraction.runTempTargetDialog(childFragmentManager) } },
            onExtendedBolus = { activity?.let { if (!childFragmentManager.isStateSaved) extendedBolusActions.start(it, childFragmentManager) } },
            onCancelExtendedBolus = { activity?.let { extendedBolusActions.confirmCancel(it, Sources.Overview, extendedBolusCancelGuard) { scheduleUpdateGUI() } } },
            onCalibration = { bolusProtected { uiInteraction.runCalibrationDialog(childFragmentManager) } },
            onLoop = { bolusProtected { uiInteraction.runLoopDialog(childFragmentManager, 1) } },
            onBasal = { activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.basal), overviewData.temporaryBasalDialogText()) } },
            onDeleteCarb = { entry -> bolusProtected { removeCarbEntry(entry) } },
            onDeleteInsulin = { entry -> bolusProtected { removeInsulinEntry(entry) } },
            // graph range: reuse the existing EventScale path (persists RangeToDisplay + refreshes)
            onRange = { hours -> rxBus.send(EventScale(hours)) },
            onDismissAlert = { alert ->
                context?.let { ctx ->
                    notificationStore.snapshot().firstOrNull { it.id == alert.id }?.let { notificationStore.dismiss(it, ctx) }
                }
                refreshAll()
            }
        )
    }

    /**
     * Undo a recent carb entry from the COB-tap sheet. Confirms first, then reuses the SAME
     * `persistenceLayer.invalidateCarbs` path (with UEL audit log) as the legacy Treatments screen —
     * no bypass. The next iobCob refresh rebuilds the hero + sheet, so the removed entry disappears.
     */
    private fun removeCarbEntry(entry: HomeUiState.CarbEntry) {
        val activity = activity ?: return
        OKDialog.showConfirmation(
            activity,
            rh.gs(app.aaps.core.ui.R.string.removerecord),
            rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + entry.grams + "\n" +
                rh.gs(app.aaps.core.ui.R.string.date) + ": " + dateUtil.dateAndTimeString(entry.timestamp),
            Runnable {
                disposable += persistenceLayer.invalidateCarbs(
                    entry.id,
                    action = Action.CARBS_REMOVED,
                    source = Sources.Overview,
                    listValues = listOf(
                        ValueWithUnit.Timestamp(entry.timestamp),
                        ValueWithUnit.Gram(entry.amount)
                    )
                ).subscribe()
            }
        )
    }

    /**
     * Undo a recent bolus or extended bolus from the IOB-tap sheet. Confirms first, then reuses the SAME
     * persistence invalidation paths (with UEL audit log) the legacy Treatments screen used — no bypass.
     * This is the repair path for insulin the pump never delivered: an unconfirmed dose is recorded on
     * purpose so IOB is not under-counted, and when the pump turns out to have been stopped or empty that
     * record has to be removable. Removal never commands the pump, so a running extended bolus is refused
     * (here and again inside the database transaction) until it has been cancelled or has finished.
     */
    private fun removeInsulinEntry(entry: HomeUiState.InsulinEntry) {
        val activity = activity ?: return
        if (!entry.removable) return
        val extended = entry.type == HomeUiState.InsulinType.EXTENDED
        OKDialog.showConfirmation(
            activity,
            rh.gs(app.aaps.core.ui.R.string.removerecord),
            rh.gs(if (extended) app.aaps.core.ui.R.string.extended_bolus else app.aaps.core.ui.R.string.bolus) + ": " + entry.units + "\n" +
                rh.gs(app.aaps.core.ui.R.string.date) + ": " + dateUtil.dateAndTimeString(entry.timestamp) +
                if (extended) "\n\nThis removes the record only. It does not change anything on the pump." else "",
            Runnable {
                disposable += if (extended)
                    persistenceLayer.invalidateEndedExtendedBolus(
                        entry.id,
                        action = Action.EXTENDED_BOLUS_REMOVED,
                        source = Sources.Overview,
                        listValues = listOf(
                            ValueWithUnit.Timestamp(entry.timestamp),
                            ValueWithUnit.Insulin(entry.amount),
                            ValueWithUnit.UnitPerHour(entry.amount * 3_600_000.0 / entry.durationMs),
                            ValueWithUnit.Minute((entry.durationMs / 60_000).toInt())
                        )
                    ).observeOn(aapsSchedulers.main).subscribe({ result ->
                        if (result.refusedActive.isNotEmpty())
                            this.activity?.let {
                                OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.removerecord), "The extended bolus is still running. Cancel it before removing the record.")
                            }
                    }, fabricPrivacy::logException)
                else
                    persistenceLayer.invalidateBolus(
                        entry.id,
                        action = Action.BOLUS_REMOVED,
                        source = Sources.Overview,
                        listValues = listOf(
                            ValueWithUnit.Timestamp(entry.timestamp),
                            ValueWithUnit.Insulin(entry.amount)
                        )
                    ).subscribe()
            }
        )
    }

    private fun trendSymbol(arrow: TrendArrow?): String = when (arrow) {
        TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP -> "⇈"
        TrendArrow.SINGLE_UP                       -> "↑"
        TrendArrow.FORTY_FIVE_UP                   -> "↗"
        TrendArrow.FLAT                            -> "→"
        TrendArrow.FORTY_FIVE_DOWN                 -> "↘"
        TrendArrow.SINGLE_DOWN                     -> "↓"
        TrendArrow.TRIPLE_DOWN, TrendArrow.DOUBLE_DOWN -> "⇊"
        else                                       -> ""
    }

    /** Map the current Overview providers into [HomeUiState]. Runs on the UI thread. */
    @SuppressLint("SetTextI18n")
    private fun buildHomeState() {
        if (!config.appInitialized) return
        val ctx = context ?: return
        val units = profileFunction.getUnits()
        val unitsStr = if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
        val now = dateUtil.now()
        val lastBg = sensorGlucose.reading
        val isActual = sensorGlucose.isFresh(now)
        val profile = profileFunction.getProfile()
        val bgMgdl = lastBg?.value
        // Colour the BG value against the display HYPO/HYPER thresholds (Overview Low/High marks —
        // the same thresholds AAPS uses for BG colouring elsewhere), NOT the tighter profile target
        // band, so a BG just above target isn't alarmingly amber. The state line below still describes
        // position vs the target band (informational).
        val lowMarkMgdl = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), units)
        val highMarkMgdl = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewHighMark), units)
        val bgTone = when {
            bgMgdl == null        -> AapsTone.Neutral
            bgMgdl > highMarkMgdl -> AapsTone.High   // amber (hyper)
            bgMgdl < lowMarkMgdl  -> AapsTone.Low    // red (hypo)
            else                  -> AapsTone.InRange // green
        }

        // Loop mode → pill label / color / looping
        val mode = loop.runningMode
        val loopActive = mode == RM.Mode.CLOSED_LOOP || mode == RM.Mode.CLOSED_LOOP_LGS || mode == RM.Mode.SUPER_BOLUS
        val loopTone = when {
            loopActive                          -> AapsTone.InRange
            mode == RM.Mode.OPEN_LOOP           -> AapsTone.High
            mode == RM.Mode.DISABLED_LOOP ||
                mode == RM.Mode.DISCONNECTED_PUMP -> AapsTone.Low
            else                                -> AapsTone.High // suspended variants
        }
        val loopLabel = when (mode) {
            RM.Mode.CLOSED_LOOP       -> rh.gs(app.aaps.core.ui.R.string.closedloop)
            RM.Mode.CLOSED_LOOP_LGS   -> rh.gs(app.aaps.core.ui.R.string.uel_lgs_loop_mode)
            RM.Mode.OPEN_LOOP         -> rh.gs(app.aaps.core.ui.R.string.openloop)
            RM.Mode.DISABLED_LOOP     -> rh.gs(R.string.disabled_loop)
            RM.Mode.DISCONNECTED_PUMP -> rh.gs(app.aaps.core.ui.R.string.disconnected)
            RM.Mode.SUPER_BOLUS       -> rh.gs(app.aaps.core.ui.R.string.superbolus)
            else                      -> rh.gs(app.aaps.core.ui.R.string.pumpsuspended)
        }
        val loopSub = when {
            loopActive || mode == RM.Mode.OPEN_LOOP -> loop.lastRun?.lastAPSRun
                ?.takeIf { it > 0 && it <= now }
                ?.let { "· ${dateUtil.minOrSecAgo(rh, it)}" }.orEmpty()
            mode == RM.Mode.SUSPENDED_BY_USER || mode == RM.Mode.DISCONNECTED_PUMP || mode == RM.Mode.SUSPENDED_BY_DST ->
                dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
            else -> ""
        }

        // Eventual BG = the algorithm's own output (APSResult.eventualBG via RT). The ONLY forward-
        // looking number on the hero. Hidden when null (open loop / no run yet).
        val rt = loop.lastRun?.constraintsProcessed?.rawData() as? RT
        val eventualMgdl = if (config.APS) rt?.eventualBG else null

        // Read once so the range, comparison and inline target status describe the same target.
        val target = TargetDisplay.at(
            now, persistenceLayer.getTemporaryTargetActiveAt(now),
            profile?.getTargetLowMgdl(), profile?.getTargetHighMgdl()
        )
        val targetRange = target.range(units, profileUtil)
        val stateLine = target.stateLine(bgMgdl, units, profileUtil)
        val tempTarget = target.temporaryTarget?.let {
            "$targetRange · ${dateUtil.untilString(it.end, rh)}"
        }

        // Basal — lead with the delivered rate (U/h); scheduled changes through the day.
        val basalData = profile?.let { iobCobCalculator.getBasalData(it, dateUtil.now()) }
        val scheduledBasal = basalData?.basal ?: 0.0
        val rateNow = if (basalData?.isTempBasalRunning == true) basalData.tempBasalAbsolute else scheduledBasal
        val basalPercent = if (scheduledBasal > 0) (rateNow / scheduledBasal * 100).roundToInt() else 100
        val basalText = String.format(Locale.getDefault(), "%.2f U/h", rateNow)
        val basalSubText = if (basalData?.isTempBasalRunning == true && scheduledBasal > 0)
            "$basalPercent% · ${String.format(Locale.getDefault(), "%.2f", scheduledBasal)} sched" else ""

        // Stats
        val cobText = iobCobCalculator.getCobInfo("Overview COB").displayText(rh, decimalFormatter)

        // Supplies: cannula + sensor age (always available from therapy events) + reservoir/battery
        // (only when the pump actually reports them — they read 0/unknown until a fresh pump read).
        val pump = activePlugin.activePump
        fun ageLabel(type: TE.Type): String? = persistenceLayer.getLastTherapyRecordUpToNow(type)?.let {
            compactDurationLabel(now - it.timestamp)
        }
        val supplies = buildList {
            ageLabel(TE.Type.CANNULA_CHANGE)?.let {
                add(HomeUiState.Supply(if (pump.pumpDescription.isPatchPump) "Patch" else "Cannula", it, AapsTone.InRange))
            }
            // Sensor: a depleting countdown to EXPIRY (not elapsed age), with the warm-up window drawn
            // as a FILLING ring instead. Expiry = last SENSOR_CHANGE + life.
            //
            // Life and warm-up come from preferences because they are per-sensor-family facts that
            // cannot be inferred from a BG broadcast. This used to hardcode 10 d ("Dexcom G6"), which
            // silently misreports every other sensor: on a 15-day Libre 3+ the ring went amber on day
            // 8.5, red on day 9.5 and read "Expired" for the final five days of a perfectly good
            // sensor. Defaults reproduce the old G6 behaviour (10 d, warm-up off).
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.let { te ->
                val lifeMs = TimeUnit.DAYS.toMillis(preferences.get(IntKey.OverviewSensorLifeDays).toLong())
                val warmupMs = TimeUnit.MINUTES.toMillis(preferences.get(IntKey.OverviewSensorWarmupMinutes).toLong())
                val elapsed = now - te.timestamp
                if (warmupMs > 0 && elapsed >= 0 && elapsed < warmupMs) {
                    // Warming up. An hour with no readings must read as "not ready yet", never as
                    // "broken", so the ring FILLS toward the first reading rather than draining toward
                    // expiry — same component, inverted fraction.
                    val minsLeft = TimeUnit.MILLISECONDS.toMinutes(warmupMs - elapsed) + 1
                    add(
                        HomeUiState.Supply(
                            "Sensor", "Warming up ${minsLeft}m", AapsTone.High,
                            fraction = (elapsed.toFloat() / warmupMs).coerceIn(0f, 1f)
                        )
                    )
                } else {
                    val remaining = te.timestamp + lifeMs - now
                    val fraction = (remaining.toFloat() / lifeMs).coerceIn(0f, 1f)
                    val remH = TimeUnit.MILLISECONDS.toHours(remaining)
                    val label = if (remaining <= 0) "Expired" else compactDurationLabel(remaining)
                    val tone = when {
                        remaining <= 0 -> AapsTone.Low
                        remH < 12      -> AapsTone.Low
                        remH < 48      -> AapsTone.High
                        else           -> AapsTone.InRange
                    }
                    add(HomeUiState.Supply("Sensor", label, tone, fraction = fraction))
                }
            }
            // Reservoir. This used to be drawn ONLY when `> 0`, so the pill quietly VANISHED at exactly
            // the moment it mattered — an empty cartridge looked identical to a screen that had never
            // shown one. Draw it whenever the pump has been read, and say "Empty" out loud. Thresholds
            // are the app's own reservoir preferences, the same ones the driver alerts on.
            val levels = pump.lastKnownLevels
            if (pump.isInitialized()) {
                add(
                    reservoirSupply(
                        levels.reservoir,
                        preferences.get(IntKey.OverviewResCritical).toDouble(), preferences.get(IntKey.OverviewResWarning).toDouble()
                    ) { rh.gs(app.aaps.core.ui.R.string.format_insulin_units, it) }
                )
            }
            // Keep the battery pill stable rather than letting it vanish and reappear.
            levels.battery?.let { add(batterySupply(it)) }
        }

        val targetEditable = TherapyActionAvailability.tempTarget(target.temporaryTarget != null, profile != null, mode)
        val extendedBolusAvailable = TherapyActionAvailability.extendedBolus(
            pump.pumpDescription.isExtendedBolusCapable, pump.isInitialized(), pump.isSuspended(), mode,
            pump.isFakingTempsByExtendedBoluses, config.AAPSCLIENT
        )
        val runningExtendedBolus = if (extendedBolusAvailable) persistenceLayer.getExtendedBolusActiveAt(now) else null
        val actionLayout = homeActionLayout(
            showCalculator = preferences.get(BooleanKey.OverviewShowWizardButton),
            showCarbs = preferences.get(BooleanKey.OverviewShowCarbsButton),
            showInsulin = preferences.get(BooleanKey.OverviewShowInsulinButton),
            tempTarget = if (targetEditable) HomeMenuItem.TempTarget(tempTarget) else null,
            extendedBolus = if (extendedBolusAvailable) HomeMenuItem.ExtendedBolus(
                status = runningExtendedBolus?.toStringMedium(dateUtil, rh),
                // A cancel already queued cannot be confirmed a second time.
                enabled = runningExtendedBolus == null || !commandQueue.extendedBolusInQueue()
            ) else null,
            calibration = xDripSource.isEnabled()
        )

        homeState.value = HomeUiState(
            loopStateLabel = loopLabel,
            loopSubLabel = loopSub,
            loopTone = loopTone,
            looping = loopActive,
            bg = bgMgdl?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--",
            bgTone = bgTone,
            bgStale = !isActual,
            units = unitsStr,
            trendArrow = if (isActual) trendSymbol(sensorGlucose.trend) else "",
            delta = if (isActual) sensorGlucose.deltaMgdl?.let { profileUtil.fromMgdlToSignedStringInUnits(it) }.orEmpty() else "",
            timeAgo = dateUtil.minOrSecAgo(rh, lastBg?.timestamp),
            eventualBg = eventualMgdl?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "",
            stateLine = stateLine,
            targetRange = targetRange,
            iob = iobText(),
            iobSub = rh.gs(app.aaps.core.ui.R.string.bolus) + " + " + rh.gs(app.aaps.core.ui.R.string.basal),
            cob = cobText ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short),
            cobSub = "",
            basal = basalText,
            basalSub = basalSubText,
            supplies = supplies,
            recentCarbs = recentCarbs,
            recentInsulin = recentInsulin,
            iobBolus = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob),
            iobBasal = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, basalIob().basaliob),
            graphRangeHours = overviewData.rangeToDisplay,
            tempTarget = tempTarget,
            ready = true,
            actions = actionLayout,
            calculatorEnabled = profile != null,
            targetEditable = targetEditable,
            notifications = notificationStore.snapshot().map {
                HomeUiState.Alert(
                    id = it.id,
                    text = it.text,
                    time = dateUtil.timeString(it.date),
                    level = it.level,
                    buttonText = if (it.buttonText != 0) rh.gs(it.buttonText) else rh.gs(app.aaps.core.ui.R.string.snooze)
                )
            }
        )
    }

    /** Refresh providers off the UI thread; panning only reuses the published snapshot. */
    private fun refreshChart() {
        handler.post {
            val now = dateUtil.now()
            val from = now - CHART_HISTORY_MS
            try {
                val snapshot = HomeGlucoseSnapshot.load(persistenceLayer, from, now)
                val chart = try { buildChartData(snapshot.readings, from, now) } catch (e: Exception) {
                    fabricPrivacy.logException(e)
                    HomeChartData()
                }
                runOnUiThread {
                    if (composeHome != null) {
                        sensorGlucose = snapshot.glucose
                        chartData.value = chart
                        buildHomeState()
                    }
                }
            } catch (e: Exception) {
                fabricPrivacy.logException(e)
                // Do not retain a seemingly fresh value when the database snapshot cannot be read.
                runOnUiThread {
                    if (composeHome != null) {
                        sensorGlucose = HomeGlucose()
                        chartData.value = HomeChartData()
                        buildHomeState()
                    }
                }
            }
        }
    }

    /** Load the full pan budget off the UI thread; viewport changes reuse this snapshot. */
    private fun buildChartData(sensorReadings: List<GV>, from: Long, now: Long): HomeChartData {
        profileFunction.getProfile() ?: return HomeChartData()
        val units = profileFunction.getUnits()

        // Straight from the database rather than the legacy graph worker's array, whose span follows
        // the old hour-aligned range. Invalidated and nonsensical values are not drawn; a reading
        // stamped in the future (phone/sensor clock skew) waits until its time has come.
        val readings = sensorReadings
            .sortedBy { it.timestamp }
            .map { GlucosePoint(it.timestamp, profileUtil.fromMgdlToUnits(it.value, units)) }
        if (readings.isEmpty()) return HomeChartData()

        // Use loop-bucketed readings without interpolated gap fills.
        val bucketed = iobCobCalculator.ads.getBucketedDataTableCopy().orEmpty()
            .filter { !it.filledGap && it.timestamp in from..now && it.recalculated.isFinite() && it.recalculated > 0.0 }
            .sortedBy { it.timestamp }
            .map { GlucosePoint(it.timestamp, profileUtil.fromMgdlToUnits(it.recalculated, units)) }

        // Historical profiles, re-resolved only where the effective profile changed.
        val switches = persistenceLayer.getEffectiveProfileSwitchesFromTimeToTime(from, now, ascending = true)
            .filter { it.isValid && it.timestamp > from && it.timestamp <= now }
            .map { it.timestamp }.distinct().sorted()

        // Sample effective rates on an epoch-aligned grid to avoid overlapping records and refresh jitter.
        val basalProfiles = ProfileCursor(from, switches) { profileFunction.getProfile(it) }
        val basal = ArrayList<BasalStep>((CHART_HISTORY_MS / BASAL_SAMPLE_MS).toInt() + 2)
        var t = (from / BASAL_SAMPLE_MS + 1) * BASAL_SAMPLE_MS
        while (true) {
            val sampleTime = minOf(t, now)
            // No profile, no step: a gap, rather than today's schedule projected backwards.
            basalProfiles.at(sampleTime)?.let { historical ->
                val bd = iobCobCalculator.getBasalData(historical, sampleTime)
                basal.add(BasalStep(sampleTime, if (bd.isTempBasalRunning) bd.tempBasalAbsolute else bd.basal, bd.basal))
            }
            if (sampleTime == now) break
            t += BASAL_SAMPLE_MS
        }

        // Extend the planned profile target through forecasts, honoring temporary-target expiry.
        val targets = TargetChartData(persistenceLayer, profileFunction, profileUtil).build(from, now + CHART_MAX_FUTURE_MS, units)

        val treatments = ArrayList<ChartTreatment>()
        persistenceLayer.getBolusesFromTimeToTime(from, now, true).forEach { b ->
            if (b.isValid && b.type != BS.Type.PRIMING && b.amount > 0.0)
                treatments.add(ChartTreatment(b.timestamp, b.amount, if (b.type == BS.Type.SMB) TreatmentKind.SMB else TreatmentKind.BOLUS))
        }
        // Use unexpanded carbs so each meal has one marker.
        persistenceLayer.getCarbsFromTimeNotExpanded(from, true).blockingGet().forEach { c ->
            if (c.isValid && c.timestamp <= now && c.amount > 0.0)
                treatments.add(ChartTreatment(c.timestamp, c.amount, TreatmentKind.CARBS))
        }
        treatments.sortBy { it.time }

        val ads = iobCobCalculator.ads.clone()
        val autosensSamples = (0 until ads.autosensDataTable.size()).map { ads.autosensDataTable.valueAt(it) }
        val additional = AdditionalGraphData.fromAutosens(autosensSamples, from, now, now) { profileUtil.fromMgdlToUnits(it, units) }
        val iob = ArrayList<GlucosePoint>()
        val iobProfiles = ProfileCursor(from, switches) { profileFunction.getProfile(it) }
        val iobStep = 5 * 60_000L
        var iobTime = (from / iobStep + 1) * iobStep
        while (true) {
            val sampleTime = minOf(iobTime, now)
            // Resolve historical profiles just as the original AAPS graph worker does.
            iobProfiles.at(sampleTime)?.let { historicalProfile ->
                val value = iobCobCalculator.calculateFromTreatmentsAndTemps(sampleTime, historicalProfile).iob
                if (value.isFinite()) iob.add(GlucosePoint(sampleTime, value))
            }
            if (sampleTime == now) break
            iobTime += iobStep
        }

        return HomeChartData(
            additional = additional.copy(points = additional.points + (AdditionalSeries.IOB to iob)),
            from = from,
            now = now,
            predictions = buildHomePredictions(
                if (config.APS) loop.lastRun?.constraintsProcessed else processedDeviceStatusData.getAPSResult(),
                now,
                { profileUtil.fromMgdlToUnits(it) }
            ),
            readings = readings,
            bucketed = bucketed,
            basal = basal,
            treatments = treatments,
            targets = targets,
            // Overview thresholds are already in display units; do not convert them again.
            lowMark = preferences.get(UnitDoubleKey.OverviewLowMark),
            highMark = preferences.get(UnitDoubleKey.OverviewHighMark),
            decimals = if (units == GlucoseUnit.MGDL) 0 else 1,
            glucoseUnits = if (units == GlucoseUnit.MGDL) "mg/dL" else "mmol/L"
        )
    }

    // endregion

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        composeHome = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    var task: Runnable? = null

    private fun scheduleUpdateGUI() {
        class UpdateRunnable : Runnable {

            override fun run() {
                refreshAll()
                task = null
            }
        }
        task?.let { handler.removeCallbacks(it) }
        task = UpdateRunnable()
        task?.let { handler.postDelayed(it, 500) }
    }

    @SuppressLint("SetTextI18n")
    private fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()
    private fun basalIob(): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
    private fun iobText(): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob + basalIob().basaliob)

    private fun updateIobCob() {
        // Recent boluses and extended boluses for the IOB-tap undo sheet (newest first). The 6h window is
        // the point: it is longer than any sane DIA, so every dose that still contributes to the IOB on
        // screen is in this list and can be taken back out. Extended boluses are looked up a day back so
        // a long one that started earlier but delivered inside the window is not missed.
        val now = dateUtil.now()
        val insulinWindowStart = now - 6 * 60 * 60 * 1000L
        recentInsulin = recentInsulinEntries(
            boluses = persistenceLayer.getBolusesFromTime(insulinWindowStart, false).blockingGet(),
            extendedBoluses = persistenceLayer.getExtendedBolusesStartingFromTimeToTime(now - 24 * 60 * 60 * 1000L, now, false),
            windowStart = insulinWindowStart,
            now = now,
            limit = 10,
            timeString = dateUtil::timeString,
            unitsString = { rh.gs(app.aaps.core.ui.R.string.format_insulin_units_label, formatBolus(it)) }
        )
        // Recent carb entries for the COB-tap undo sheet (last 6h, newest first). Off the UI thread here.
        recentCarbs = persistenceLayer.getCarbsFromTimeNotExpanded(dateUtil.now() - 6 * 60 * 60 * 1000L, false)
            .blockingGet()
            .filter { it.amount > 0 }
            .take(10)
            .map { ca ->
                HomeUiState.CarbEntry(
                    id = ca.id,
                    time = dateUtil.timeString(ca.timestamp),
                    grams = rh.gs(app.aaps.core.objects.R.string.format_carbs, ca.amount.toInt()),
                    timestamp = ca.timestamp,
                    amount = ca.amount.toInt()
                )
            }
        runOnUiThread {
            composeHome ?: return@runOnUiThread
            // Refresh the hero so its IOB/COB reflect a just-entered treatment within ~1s (this event
            // fires debounced after the iobCob recalc). Previously the hero only rebuilt in refreshAll()
            // — up to 60s / next CGM tick later — so a fresh carb entry looked like it hadn't
            // registered, tempting a duplicate entry.
            buildHomeState()
        }
    }

    @SuppressLint("SetTextI18n")
    fun popupBolusDialogIfRunning(onClick: Boolean) {
        // Check if bolus is in progress and show dialog if needed
        // Only show for manual bolus (not SMB) with progress > 0
        if (commandQueue.bolusInQueue()) {

            // Show bolus progress dialog automatically only for manual bolus with progress
            if (!BolusProgressData.bolusEnded && (!BolusProgressData.isSMB || onClick)) {
                activity?.let { activity ->
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded)
                            uiInteraction.runBolusProgressDialog(childFragmentManager)
                    })
                }
            }
        }
    }
}

/**
 * The effective profile for increasing sample times, re-resolved only at effective-profile-switch
 * boundaries (the same rule as [TargetChartData]) instead of once per graph sample.
 */
private class ProfileCursor(from: Long, private val switches: List<Long>, private val resolve: (Long) -> Profile?) {

    private var next = 0
    private var profile: Profile? = resolve(from)

    fun at(time: Long): Profile? {
        var latest: Long? = null
        while (next < switches.size && switches[next] <= time) latest = switches[next++]
        latest?.let { profile = resolve(it) }
        return profile
    }
}
