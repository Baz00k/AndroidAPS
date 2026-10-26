package app.aaps.core.ui.activities

import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Lays the window out edge to edge, as Android 15 enforces, and pads the content back inside the
 * system bars, display cutout, keyboard and home gesture area.
 */
fun AppCompatActivity.fitContentToSystemBars() {
    enableEdgeToEdge()
    ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { content, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        val gesture = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        content.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, gesture.bottom))
        WindowInsetsCompat.CONSUMED
    }
}
