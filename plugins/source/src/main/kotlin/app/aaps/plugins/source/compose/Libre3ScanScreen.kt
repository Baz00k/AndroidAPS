package app.aaps.plugins.source.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.libre3.Libre3ScanFlow

/**
 * Tier 3 — the new/replace-sensor flow. The only place NFC is exposed.
 *
 * Sequencing and its guards live in [Libre3ScanFlow] (unit tested); this draws them. The scan
 * button is reachable only from the confirm step, and the confirm step states what will actually
 * happen rather than asking "are you sure?".
 */
@Composable
fun Libre3ScanScreen(
    step: Libre3ScanFlow.Step?,
    onConfirm: () -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = AapsSpacing.screenH, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (step) {
            is Libre3ScanFlow.Step.Confirm -> {
                Text(
                    if (step.operation == Libre3ScanFlow.Operation.ACTIVATE) "Start a new sensor"
                    else "Take over a running sensor",
                    style = AapsTheme.type.title, color = colors.textPrimary
                )
                AapsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(step.consequence, style = AapsTheme.type.body, color = colors.textPrimary)
                        if (step.operation == Libre3ScanFlow.Operation.ACTIVATE) {
                            // Say it plainly. This is the one action in the app that cannot be
                            // undone and that costs a physical sensor to get wrong.
                            Text(
                                "Activation happens once and cannot be undone. A sensor activated " +
                                    "by mistake cannot be reused.",
                                style = AapsTheme.type.caption, color = colors.high
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                PrimaryButton(
                    label = if (step.operation == Libre3ScanFlow.Operation.ACTIVATE) "Continue to scan"
                    else "Continue to take over",
                    onClick = onConfirm, modifier = Modifier.fillMaxWidth()
                )
                SecondaryButton(label = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }

            is Libre3ScanFlow.Step.Scan -> {
                Text("Hold the phone against the sensor", style = AapsTheme.type.title, color = colors.textPrimary)
                AapsCard {
                    Text(
                        "Press the top of the phone flat against the sensor and hold for a few " +
                            "seconds.\n\n" +
                            if (step.operation == Libre3ScanFlow.Operation.ACTIVATE)
                                "This will ACTIVATE the sensor."
                            else "This will TAKE OVER a sensor that is already running.",
                        style = AapsTheme.type.body, color = colors.textPrimary
                    )
                }
                PrimaryButton(label = "Scan now", onClick = onScan, modifier = Modifier.fillMaxWidth())
                SecondaryButton(label = "Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }

            is Libre3ScanFlow.Step.WarmUp -> {
                Text("Warming up", style = AapsTheme.type.title, color = colors.textPrimary)
                AapsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${step.minutesRemaining} minutes until the first reading.",
                            style = AapsTheme.type.body, color = colors.textPrimary
                        )
                        LinearProgressIndicator(
                            progress = { ((60 - step.minutesRemaining) / 60f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (step.oldSensorStillFeeding)
                                "Your previous sensor is still feeding the loop, so there is no gap."
                            else "The loop has no CGM data until this finishes.",
                            style = AapsTheme.type.caption,
                            color = if (step.oldSensorStillFeeding) colors.textSecondary else colors.high
                        )
                        Text(
                            "You can leave this screen — it will carry on in the background.",
                            style = AapsTheme.type.caption, color = colors.textSecondary
                        )
                    }
                }
            }

            is Libre3ScanFlow.Step.Handover -> {
                Text("Sensor ready", style = AapsTheme.type.title, color = colors.inRange)
                AapsCard {
                    Text(
                        "The new sensor is delivering readings and the loop has switched to it.",
                        style = AapsTheme.type.body, color = colors.textPrimary
                    )
                }
                PrimaryButton(label = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
            }

            is Libre3ScanFlow.Step.Failed -> {
                Text("Could not start the sensor", style = AapsTheme.type.title, color = colors.low)
                AapsCard {
                    Text(step.reason, style = AapsTheme.type.body, color = colors.textPrimary)
                }
                SecondaryButton(label = "Close", onClick = onCancel, modifier = Modifier.fillMaxWidth())
            }

            null -> Unit
        }
    }
}
