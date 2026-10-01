package app.aaps.core.compose.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * A press-and-hold confirmation, for commands that cannot be undone (insulin). [label] names the
 * action ("Deliver 2.40 U"); the button itself says how long to hold, and while held a solid bar
 * runs along its bottom edge for [holdMillis]. Completing the hold fires [onConfirm] with a haptic
 * tick; releasing early cancels. This is a UI affordance ONLY — the caller must still run the same
 * constraint + confirmation + delivery path.
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
    var holding by remember(confirms) { mutableStateOf(false) }
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    // progress animates to 1f while holding, back to 0f on release
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) holdMillis else 180, easing = LinearEasing),
        label = "hold-progress"
    )
    // fire once the fill reaches the end
    LaunchedEffect(progress, holding) {
        if (holding && progress >= 1f) {
            holding = false
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            currentOnConfirm()
        }
    }
    val instruction = "Hold ${holdSeconds(holdMillis)} s"
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .disabledAlpha(enabled)
            .clip(AapsTheme.shape.button)
            .background(colors.accent)
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
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val released = try {
                                tryAwaitRelease()
                            } finally {
                                holding = false
                            }
                            released
                        }
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // The hold's progress: a light wash over the held part, and a solid bar in the label's own
        // ink along the bottom, which reads at full contrast in both themes.
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(colors.onAccent.copy(alpha = 0.16f))
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .background(colors.onAccent)
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(20.dp).padding(end = 6.dp)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FittedText(label, AapsTheme.type.title, colors.onAccent)
                Text(if (holding) "Keep holding" else instruction, style = AapsTheme.type.caption, color = colors.onAccent)
            }
        }
    }
}

private fun holdSeconds(millis: Int): String =
    if (millis % 1000 == 0) "${millis / 1000}" else String.format(java.util.Locale.getDefault(), "%.1f", millis / 1000.0)
