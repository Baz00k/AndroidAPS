package app.aaps.ui.activities

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.activities.stats.StatsActions
import app.aaps.ui.activities.stats.StatsController
import app.aaps.ui.activities.stats.StatsLoader
import app.aaps.ui.activities.stats.StatsScreen
import javax.inject.Inject

class StatsActivity : TranslatedDaggerAppCompatActivity() {
    @Inject lateinit var loader: StatsLoader
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private lateinit var controller: StatsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        controller = StatsController(
            loader, rxBus.toObservable(EventNewHistoryData::class.java).map { it.oldDataTimestamp },
            aapsSchedulers.io, aapsSchedulers.main, fabricPrivacy::logException,
            rangeDays = savedInstanceState?.getInt("rangeDays", 7) ?: 7,
            cycleOffset = savedInstanceState?.getInt("cycleOffset", 0) ?: 0
        )
        val actions = StatsActions(
            onRange = controller::select, onBack = { finish() }, onRecalculate = controller::recalculate,
            onShowCycle = controller::showCycle, onCycleOffset = controller::setCycleOffset
        )
        setContentView(ComposeView(this).apply {
            setContent {
                AapsTheme { StatsScreen(controller.stats.value, actions, controller.cycle.value, controller.recalculation.value) }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        controller.start()
    }

    override fun onPause() {
        controller.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("rangeDays", controller.stats.value.rangeDays)
        outState.putInt("cycleOffset", controller.cycle.value.offset)
        super.onSaveInstanceState(outState)
    }
}
