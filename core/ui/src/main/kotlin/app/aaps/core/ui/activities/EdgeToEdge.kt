package app.aaps.core.ui.activities

import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Lays the window out edge to edge, as Android 15 enforces, and pads the content back inside the
 * system bars, display cutout and keyboard. Screens stay clear of them on every API level, and the
 * keyboard never covers a form's confirm or cancel controls.
 */
fun AppCompatActivity.fitContentToSystemBars() {
    enableEdgeToEdge()
    ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { content, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        content.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = safe.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
