package app.aaps.ui.activities

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.activities.stats.StatsLoader
import app.aaps.ui.activities.stats.StatsReloader
import app.aaps.ui.activities.stats.StatsScreen
import app.aaps.ui.activities.stats.StatsUiState
import javax.inject.Inject

class StatsActivity : TranslatedDaggerAppCompatActivity() {
    @Inject lateinit var loader: StatsLoader
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val statsState = mutableStateOf(StatsUiState())
    private lateinit var reloader: StatsReloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        statsState.value = StatsUiState(rangeDays = savedInstanceState?.getInt("rangeDays", 7) ?: 7)
        reloader = StatsReloader(loader::load, aapsSchedulers.io, aapsSchedulers.main,
                                 { statsState.value = it }, fabricPrivacy::logException)
        setContentView(ComposeView(this).apply {
            setContent { AapsTheme { StatsScreen(statsState.value, onRange = reloader::reload, onBack = { finish() }) } }
        })
    }

    override fun onResume() {
        super.onResume()
        reloader.reload(statsState.value.rangeDays)
    }

    override fun onPause() {
        reloader.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("rangeDays", statsState.value.rangeDays)
        super.onSaveInstanceState(outState)
    }
}
