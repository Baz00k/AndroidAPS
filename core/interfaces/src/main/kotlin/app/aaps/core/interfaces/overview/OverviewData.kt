package app.aaps.core.interfaces.overview

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import app.aaps.core.data.model.GV

/**
 * Shared state behind the overview: the window the home chart covers, plus the few pump/basal
 * strings the home screen and the widget both render.
 *
 * This used to carry ~30 `SeriesData` fields as well — one per layer of the GraphView chart, each
 * filled by its own worker. The home chart is Compose now and reads its own sources, so the series
 * and the workers behind them are gone; what is left is the window and [bgReadingsArray].
 */
interface OverviewData {

    var rangeToDisplay: Int // for graph
    var toTime: Long  // current time rounded up to 1 hour
    var fromTime: Long // toTime - range

    fun reset()
    fun initRange()
    /*
     * PUMP STATUS
     */

    var pumpStatus: String

    /*
     * CALC PROGRESS
     */

    var calcProgressPct: Int

    /*
     * TEMPORARY BASAL
     */

    fun temporaryBasalText(): String
    fun temporaryBasalDialogText(): String
    @DrawableRes fun temporaryBasalIcon(): Int
    @AttrRes fun temporaryBasalColor(context: Context?): Int

    /*
     * EXTENDED BOLUS
    */
    fun extendedBolusText(): String
    fun extendedBolusDialogText(): String

    /*
     * Graphs
     */

    /** Sensor readings covering [fromTime]..[toTime], loaded by `PrepareBgDataWorker`. */
    var bgReadingsArray: List<GV>
}
