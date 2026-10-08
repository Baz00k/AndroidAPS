package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/** One button in an [AlertContent] footer. */
data class AlertAction(
    val label: String,
    val onClick: () -> Unit,
    /** Filled accent (the affirmative). Exactly one action should normally be primary. */
    val primary: Boolean = false,
    /** Tinted with the "low" red — destructive / stop. */
    val destructive: Boolean = false
)

/**
 * The redesign's alert body: [AlertFrame] with a stacked list of tap actions. This is what every
 * confirmation in the app now looks like — `OKDialog` hosts it in a plain `Dialog` so all ~46 existing
 * call sites move over without touching their code.
 *
 * Actions stack vertically rather than sitting in a Material button row: the messages here are
 * itemised therapy summaries, and a full-width target is both easier to hit and harder to mis-tap
 * than two small text buttons in a corner. A non-primary action (cancel, no) is a [SecondaryButton].
 */
@Composable
fun AlertContent(
    title: String,
    message: String,
    actions: List<AlertAction>,
    /** Accent rule + title tint. Defaults to the theme accent; callers pass a semantic color for alarms. */
    tint: Color = Color.Unspecified
) = AlertFrame(title, message, tint) {
    actions.forEach { a ->
        when {
            a.primary     -> PrimaryButton(a.label, a.onClick, Modifier.fillMaxWidth())
            a.destructive -> DangerButton(a.label, a.onClick, Modifier.fillMaxWidth())
            else          -> SecondaryButton(a.label, a.onClick, Modifier.fillMaxWidth())
        }
    }
}

/**
 * The frame every confirmation shares, whether it is confirmed by a tap ([AlertContent]) or by a
 * press-and-hold: a rounded card with the title behind an accent rule, the message, and [actions]
 * stacked under it. A cancel among the actions is a [SecondaryButton].
 *
 * A long message scrolls in its own area, capped in height and never taller than the space the title
 * and actions leave, so the actions stay on screen at any font scale. That needs a bounded height,
 * which the dialog window hosting the frame gives it.
 */
@Composable
fun AlertFrame(
    title: String,
    message: String,
    /** Accent rule + title tint. Defaults to the theme accent; callers pass a semantic color for alarms. */
    tint: Color = Color.Unspecified,
    actions: @Composable ColumnScope.() -> Unit
) {
    val colors = AapsTheme.colors
    val rule = tint.takeIf { it != Color.Unspecified } ?: colors.accent
    Box(Modifier.fillMaxWidth().padding(AapsSpacing.screenH)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AapsTheme.shape.hero)
                .background(colors.surface)
                .padding(AapsSpacing.cardPad),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)
        ) {
            if (title.isNotBlank())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 20.dp)
                            .clip(AapsTheme.shape.pill)
                            .background(rule)
                    )
                    Text(title, style = AapsTheme.type.title, color = colors.textPrimary)
                }
            if (message.isNotBlank())
                Text(
                    message,
                    style = AapsTheme.type.body,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            Column(
                Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGapSmall),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall),
                content = actions
            )
        }
    }
}

@ComponentPreviews
@Composable
private fun AlertContentPreview() = PreviewSurface {
    // A window shorter than the message: the summary scrolls and both actions stay on screen.
    Box(Modifier.height(360.dp)) {
        AlertContent(
            title = "Confirmation",
            message = List(12) { "Line ${it + 1} of an itemised summary" }.joinToString("\n"),
            actions = listOf(AlertAction("OK", {}, primary = true), AlertAction("Cancel", {}))
        )
    }
}
