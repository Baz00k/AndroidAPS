package app.aaps.workflow

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.core.utils.receivers.DataWorkerStorage
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

/**
 * Advance the chart window to the current hour and load the readings inside it.
 *
 * The re-alignment used to live at the far end of the chain in `PreparePredictionsWorker`, which moved
 * `fromTime`/`toTime` *after* this worker had already loaded readings for the previous window — and
 * shortened the history by up to two hours to make room for a prediction tail that the Compose chart
 * does not draw. Doing both here, in that order, keeps the window and the readings in step.
 */
class PrepareBgDataWorker(
    context: Context,
    params: WorkerParameters
) : LoggingWorker(context, params, Dispatchers.Default) {

    @Inject lateinit var dataWorkerStorage: DataWorkerStorage
    @Inject lateinit var persistenceLayer: PersistenceLayer

    class PrepareBgData(
        val iobCobCalculator: IobCobCalculator, // cannot be injected : HistoryBrowser uses different instance
        val overviewData: OverviewData
    )

    override suspend fun doWorkAndLog(): Result {

        val data = dataWorkerStorage.pickupObject(inputData.getLong(DataWorkerStorage.STORE_KEY, -1)) as PrepareBgData?
            ?: return Result.failure(workDataOf("Error" to "missing input data"))

        data.overviewData.initRange()
        data.overviewData.bgReadingsArray =
            persistenceLayer.getBgReadingsDataFromTimeToTime(data.overviewData.fromTime, data.overviewData.toTime, false)
        return Result.success()
    }
}
