package app.aaps.ui.dialogs.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.HoldToConfirmButton
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.Tag
import app.aaps.core.compose.components.formatNumeric
import app.aaps.core.compose.theme.AapsColors
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Inputs, then Review, in one sheet, for every screen that commits a dose. The back arrow and the Back
 * key return from Review to the inputs; the footer is the step's own action.
 */
@Composable
internal fun ReviewStepSheet(
    title: String,
    reviewing: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable (reviewing: Boolean) -> Unit
) {
    BackHandler(enabled = reviewing, onBack = onBack)
    SheetSurface(
        title = if (reviewing) "Review" else title,
        onClose = onClose,
        onBack = if (reviewing) onBack else null,
        footer = footer
    ) {
        AnimatedContent(
            targetState = reviewing,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "review-step"
        ) { onReview ->
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) { content(onReview) }
        }
    }
}

/** What Review commits, large; the value Review opened on is struck through once it has changed. */
@Composable
internal fun ReviewAmount(value: String, previous: String?, details: @Composable ColumnScope.() -> Unit = {}) {
    val colors = AapsTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (previous != null)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Struck(previous, colors)
                Tag("Updated", tint = colors.accent)
            }
        Text(value, style = AapsTheme.type.hero, color = colors.textPrimary)
        details()
    }
}

/** Review's commit: a press-and-hold when insulin goes to the pump, a tap for anything else. */
@Composable
internal fun ReviewCommitButton(label: String, delivers: Boolean, confirms: Any?, onCommit: () -> Unit) =
    if (delivers) HoldToConfirmButton(label = label, onConfirm = onCommit, confirms = confirms)
    else PrimaryButton(label = label, onClick = onCommit)

internal fun units(v: Double) = "${formatNumeric(v, 2)} U"

@Composable
internal fun Struck(text: String, colors: AapsColors) =
    Text(text, style = AapsTheme.type.body.copy(textDecoration = TextDecoration.LineThrough), color = colors.textTertiary)
