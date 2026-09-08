package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventIobCalculationProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.objects.workflow.LoggingWorker
import kotlinx.coroutines.Dispatchers
import java.security.spec.InvalidParameterSpecException
import javax.inject.Inject

class UpdateGraphWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.IO) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var activePlugin: ActivePlugin

    override suspend fun doWorkAndLog(): Result {
        val pass = inputData.getInt(CalculationWorkflow.PASS, -1)
        // Always the overview's own bus: that is where OverviewFragment listens, and it is now the only
        // listener. The rxBus branch this replaces was for HistoryBrowseActivity, which is gone — so a
        // rebuild triggered by a scale change or a therapy event was being sent where nobody was.
        activePlugin.activeOverview.overviewBus.send(EventUpdateOverviewGraph("UpdateGraphWorker"))
        rxBus.send(EventIobCalculationProgress(CalculationWorkflow.ProgressData.entries.find { it.pass == pass } ?: throw InvalidParameterSpecException(), 100, null))
        return Result.success()
    }
}