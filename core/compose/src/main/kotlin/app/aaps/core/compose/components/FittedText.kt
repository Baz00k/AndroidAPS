package app.aaps.core.compose.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * A single-line readout that shrinks to fit rather than losing its tail.
 *
 * The layouts were drawn against a proportional font, so a fixed size plus `maxLines = 1` silently
 * clips whatever runs past the edge. That is tolerable for a label and not tolerable for a value:
 * with a skin font about twice as wide per character, "0.45 U/h" rendered as "0.45" and "1.19 U" as
 * "1.19" — the units dropped off a dose readout while still looking like a complete number, which is
 * the worst way for text to fail on a screen someone doses from.
 *
 * Shrinking is the right trade here. A slightly smaller number is still the number; a truncated one
 * is a different number. [minScale] stops it shrinking into illegibility — past that point the text
 * clips as before, because unreadably small is no better than cut off.
 *
 * The floor is a FRACTION of the style rather than an absolute size, which matters more than it
 * looks: a skin can scale the whole type ramp down, and an absolute floor then sits above the text's
 * own size — an inverted range, and the autosizing silently does nothing. That is exactly how the
 * supply pills kept clipping after this was first written.
 */
@Composable
fun FittedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.62f,
    /** More than one lets a label that cannot fit one line even at [minScale] wrap rather than clip. */
    maxLines: Int = 1
) {
    val max = style.fontSize
    if (maxLines > 1 && !max.isUnspecified) {
        // Shrinking keeps a label on one line and is tried first; wrapping is the last resort, so
        // "−30 min" shrinks while "Eating soon" at a large font scale breaks onto two lines.
        BoxWithConstraints(modifier) {
            val measurer = rememberTextMeasurer()
            val smallest = measurer.measure(text, style.copy(fontSize = max * minScale), maxLines = 1, softWrap = false)
            AutoSizedText(text, style, color, Modifier, minScale, lines = if (smallest.size.width <= constraints.maxWidth) 1 else maxLines)
        }
        return
    }
    AutoSizedText(text, style, color, modifier, minScale, maxLines)
}

@Composable
private fun AutoSizedText(text: String, style: TextStyle, color: Color, modifier: Modifier, minScale: Float, lines: Int) {
    val max = style.fontSize
    // Not `== TextUnit.Unspecified`: that sentinel is NaN-backed, so equality is never true.
    if (max.isUnspecified) {
        // Nothing to shrink towards; render as-is rather than guessing a size.
        BasicText(text = text, modifier = modifier, style = style.copy(color = color), maxLines = lines)
        return
    }
    // A line height in sp stays put while the font shrinks, so two shrunk lines drift apart. As a
    // multiple of the font size it shrinks with it.
    val lineHeight = style.lineHeight
    val scaledStyle = if (lineHeight.isSp && max.isSp) style.copy(lineHeight = (lineHeight.value / max.value).em) else style
    BasicText(
        text = text,
        modifier = modifier,
        style = scaledStyle.copy(color = color),
        maxLines = lines,
        autoSize = TextAutoSize.StepBased(
            minFontSize = max * minScale,
            maxFontSize = max,
            stepSize = 0.5.sp
        )
    )
}
