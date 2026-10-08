package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.text.HtmlCompat
import androidx.fragment.app.FragmentActivity
import app.aaps.core.compose.components.AlertFrame
import app.aaps.core.compose.components.HoldToConfirmButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.ui.dialogs.ComposeDialogHost

/**
 * Confirmation for an action that will actually move insulin.
 *
 * Deliberately a drop-in for `OKDialog.showConfirmation(activity, title, message, ok, cancel)`: same
 * host, frame and itemised summary (including the constraint warnings the callers build), but the
 * positive action is a press-and-hold rather than a tap. The Calculator gates delivery behind a hold; this is
 * what lets every OTHER delivery route — manual bolus, insulin, extended bolus, prime/fill — use the
 * same gesture, so "how do I commit insulin" has exactly one answer in this app.
 *
 * Callers should keep using plain `OKDialog` when nothing is delivered (carbs-only, record-only), so
 * the hold stays meaningful rather than becoming a reflex.
 */
object HoldConfirmDialog {

    fun show(activity: FragmentActivity, title: String, message: CharSequence, ok: Runnable?, cancel: Runnable? = null, action: String = "Confirm") {
        // Only the first answer counts: a second confirm must never start a second delivery.
        var answered = false
        ComposeDialogHost.show(activity) { dismiss ->
            HoldConfirmContent(
                title = title,
                message = message.toPlainText(),
                action = action,
                onConfirm = {
                    if (!answered) {
                        answered = true
                        dismiss()
                        ok?.run()
                    }
                },
                onCancel = {
                    if (!answered) {
                        answered = true
                        dismiss()
                        cancel?.run()
                    }
                }
            )
        }
    }

    /**
     * Callers hand us HTML built with `formatColor(...)` for the legacy AlertDialog. The colours are
     * theme attributes from the old palette, so we take the text and let the redesign colour it.
     */
    private fun CharSequence.toPlainText(): String =
        // A Spanned is already parsed and keeps its line breaks; parsing it again would fold them into spaces.
        (if (this is android.text.Spanned) toString() else HtmlCompat.fromHtml(toString(), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()).trim()
}

@Composable
internal fun HoldConfirmContent(title: String, message: String, action: String, onConfirm: () -> Unit, onCancel: () -> Unit) =
    AlertFrame(title, message) {
        HoldToConfirmButton(label = action, onConfirm = onConfirm, modifier = Modifier.fillMaxWidth())
        SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
    }
