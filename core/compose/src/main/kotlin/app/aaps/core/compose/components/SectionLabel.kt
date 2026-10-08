package app.aaps.core.compose.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.aaps.core.compose.theme.AapsTheme
import java.util.Locale

/**
 * The small uppercase title over a group of controls or a card ("Therapy", "Suspend loop"). It is a
 * heading for screen readers, so TalkBack can jump between sections. Pass the text in normal case;
 * the label uppercases it.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(
        text.uppercase(Locale.getDefault()),
        style = AapsTheme.type.label,
        color = AapsTheme.colors.textSecondary,
        modifier = modifier.semantics { heading() }
    )

@ComponentPreviews
@Composable
private fun SectionLabelPreview() = PreviewSurface {
    SectionLabel("Therapy")
    SectionLabel("Log an event")
}
