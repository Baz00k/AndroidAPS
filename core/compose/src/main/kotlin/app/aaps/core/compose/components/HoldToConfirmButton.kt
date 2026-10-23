package app.aaps.core.compose.components

import android.animation.ValueAnimator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import kotlinx.coroutines.launch

/**
 * A press-and-hold confirmation, for commands that cannot be undone (insulin). [label] names the
 * action ("Deliver 2.40 U") and the line above says how long to hold. The button rests tonal, so it
 * never reads as an ordinary tap button; held, it fills with solid accent from the leading edge
 * over [holdMillis], the label switching ink as the fill passes, so progress has full contrast in
 * either theme. Completing the hold fires [onConfirm] with a haptic tick; releasing early cancels.
 * The gesture alone times the hold; the fill only shows it, and is left out when the system has
 * animations removed.
 * This is a UI affordance ONLY — the caller must still run the same constraint + confirmation +
 * delivery path.
 *
 * A hold confirms what the button showed when it began. Whenever [confirms] changes (the amount on
 * the label, say), a hold in progress is dropped and must start again.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1000,
    enabled: Boolean = true,
    confirms: Any? = label
) {
    val colors = AapsTheme.colors
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var holding by remember(confirms) { mutableStateOf(false) }
    var progress by remember(confirms) { mutableFloatStateOf(0f) }
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val scope = rememberCoroutineScope()
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    LaunchedEffect(holding) {
        progress = 0f
        if (!holding || !animate) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) progress = withFrameMillis { ((it - start).toFloat() / holdMillis).coerceAtMost(1f) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .disabledAlpha(enabled),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Above the button, where the thumb holding it does not cover it.
        Text(if (holding && !animate) "Keep holding" else "Press and hold for ${holdSeconds(holdMillis)} s", style = AapsTheme.type.caption, color = colors.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(AapsTheme.shape.button)
                .background(colors.accentTintStrong)
                .border(1.5.dp, colors.accent, AapsTheme.shape.button)
                // Touch users hold the button; TalkBack / Switch Access get the same deliberate gesture as a
                // long-click action. There is intentionally no semantic onClick: a plain double-tap must not
                // deliver insulin.
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    if (enabled) {
                        stateDescription = "Press and hold"
                        onLongClick(label = label) {
                            currentOnConfirm()
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .then(
                    // Keyed on [confirms]: a change cancels the gesture under the finger, not just the fill.
                    if (enabled) Modifier.pointerInput(confirms) {
                        awaitEachGesture {
                            awaitFirstDown()
                            holding = true
                            try {
                                // Confirms only if the finger is still down, inside the button, when the time is up.
                                val ended = withTimeoutOrNull(holdMillis.toLong()) { waitForUpOrCancellation(); true }
                                if (ended == null) {
                                    holding = false
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    // Outside pointer dispatch: confirming can close the sheet, which cancels this gesture.
                                    scope.launch { currentOnConfirm() }
                                    waitForUpOrCancellation()
                                }
                            } finally {
                                holding = false
                            }
                        }
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            HoldLabel(label, colors.accentOnLight)
            // The held part: the same label on solid accent, cut to the progress.
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {}
                    .drawWithContent {
                        val filled = size.width * progress
                        if (rtl) clipRect(left = size.width - filled) { this@drawWithContent.drawContent() }
                        else clipRect(right = filled) { this@drawWithContent.drawContent() }
                    }
                    .background(colors.accent),
                contentAlignment = Alignment.Center
            ) { HoldLabel(label, colors.onAccent) }
        }
    }
}

@Composable
private fun HoldLabel(label: String, ink: Color) =
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = ink, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        FittedText(label, AapsTheme.type.title, ink)
    }

private fun holdSeconds(millis: Int): String =
    if (millis % 1000 == 0) "${millis / 1000}" else String.format(java.util.Locale.getDefault(), "%.1f", millis / 1000.0)
