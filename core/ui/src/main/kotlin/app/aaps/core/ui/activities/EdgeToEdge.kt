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
 * keyboard never covers a form's confirm or cancel controls. The bottom also clears the home gesture
 * area, which on gesture navigation is taller than the navigation bar.
 *
 * With [pagesBottom], the content runs down to the bottom edge instead (it is still lifted above the
 * keyboard) and is handed the bottom inset to keep its controls clear of, 0 while the keyboard shows.
 * A page that paints its own bottom bar can then let the bar's colour reach the edge.
 */
fun AppCompatActivity.fitContentToSystemBars(pagesBottom: ((Int) -> Unit)? = null) {
    enableEdgeToEdge()
    ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(android.R.id.content)) { content, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        val bottom = maxOf(safe.bottom, insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom)
        if (pagesBottom == null) {
            content.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = bottom)
            WindowInsetsCompat.CONSUMED
        } else {
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            content.updatePadding(left = safe.left, top = safe.top, right = safe.right, bottom = ime)
            pagesBottom(if (ime > 0) 0 else bottom)
            insets.inset(safe.left, safe.top, safe.right, ime)
        }
    }
}

/** A main-screen page that keeps its own controls clear of the navigation bar and gesture area. */
interface DrawsToBottomEdge
