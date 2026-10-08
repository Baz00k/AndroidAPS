package app.aaps.core.ui.activities

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.aaps.core.compose.theme.AapsColors
import app.aaps.core.compose.theme.AapsSkinState
import kotlinx.coroutines.launch

/**
 * Keeps an activity's native chrome — window and action bar — in the active appearance's colours, so
 * the XML frame matches the Compose content inside it: flat, the colour of the screen, no shadow.
 *
 * Painting once is not enough: switching between two appearances on the same light/dark ground
 * (Dark → Midnight) deliberately recreates nothing, and the picker makes that switch while it stays
 * resumed itself. So this repaints whenever the appearance changes while the activity is started.
 * Call it once, from `onCreate`.
 *
 * @param paintMore for an activity with chrome of its own (toolbar, tabs, drawer) to paint as well.
 */
fun AppCompatActivity.followSkinChrome(paintMore: (AapsColors) -> Unit = {}) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Reads the selection and the installed skins, both snapshot state.
            snapshotFlow { AapsSkinState.skin }.collect { paintMore(applySkinChrome()) }
        }
    }
}

private fun AppCompatActivity.applySkinChrome(): AapsColors {
    val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val colors = AapsSkinState.skin.resolvedColors(this, dark)
    window.decorView.setBackgroundColor(colors.background.toArgb())
    supportActionBar?.apply {
        setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
        elevation = 0f
    }
    return colors
}
