package app.aaps.ui.dialogs.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.EntryTime
import app.aaps.core.compose.components.StepperRow
import app.aaps.core.compose.components.StepperValue
import app.aaps.core.compose.components.TimeStepper
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.components.HoldToConfirmButton
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.icons.AapsIcons
import app.aaps.core.compose.theme.AapsColors
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import kotlinx.coroutines.delay
import java.util.Locale

/** How often the Calculator re-reads glucose, IOB and targets while it is open. */
private const val REFRESH_MS = 10_000L

/**
 * The Calculator: inputs, then Review. Stateless with respect to the dose math: [compute] runs the
 * existing BolusWizard for the current [WizardInputs]; [onCommit] rebuilds it from current data and
 * commits only if it still matches what was reviewed, calling back when it did not. The result is
 * recomputed every [REFRESH_MS], so the numbers on screen follow new readings and decaying insulin.
 */
@Composable
fun WizardScreen(
    compute: (WizardInputs) -> WizardResult,
    onCommit: (inputs: WizardInputs, reviewed: CalculatorOutcome, onChanged: () -> Unit) -> Unit,
    onCancel: () -> Unit,
    initialInputs: WizardInputs = WizardInputs(),
    carbControls: WizardCarbControls
) {
    val colors = AapsTheme.colors
    // Inputs survive recreation; the review step does not, so a restored screen always needs a fresh review.
    var inputs by rememberSaveable(stateSaver = WizardInputs.Saver) { mutableStateOf(initialInputs.copy(carbs = carbControls.clamp(initialInputs.carbs))) }
    var reviewing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            refresh++
        }
    }
    val result = remember(inputs, refresh) { compute(inputs) }
    // "Eat later" only exists while the advisor applies; drop it rather than act on a stale choice.
    LaunchedEffect(result.advisorAvailable) {
        if (!result.advisorAvailable && inputs.eatLater) inputs = inputs.copy(eatLater = false)
    }

    // fillMaxSize: bounding the root is what lets weight(1f) below reserve space for the action bar.
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(if (reviewing) Icons.Rounded.ArrowBack else Icons.Rounded.Close, if (reviewing) "Back" else "Close") {
                if (reviewing) reviewing = false else onCancel()
            }
            Text(
                if (reviewing) "Review" else "Calculator",
                style = AapsTheme.type.title, color = colors.textPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
        }

        AnimatedContent(
            targetState = reviewing,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "calculator-step",
            modifier = Modifier.weight(1f)
        ) { onReview ->
            if (!onReview) InputStep(
                inputs, result, carbControls, colors,
                onInputs = { inputs = it.copy(carbs = carbControls.clamp(it.carbs)) },
                onReview = { reviewing = true }
            )
            else ReviewStep(
                inputs, result, colors,
                // Not sent: data moved under the review. Re-read now so the new numbers are what is shown.
                onCommit = { reviewed, onChanged -> onCommit(inputs, reviewed) { refresh++; onChanged() } },
                onCancel = { reviewing = false }
            )
        }
    }
}

@Composable
private fun InputStep(
    inputs: WizardInputs,
    result: WizardResult,
    carbControls: WizardCarbControls,
    colors: AapsColors,
    onInputs: (WizardInputs) -> Unit,
    onReview: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // weight(1f): the cards scroll in whatever space the action bar leaves, so Review stays on screen.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AapsSpacing.screenH)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
        ) {
            GlucoseCard(inputs, result, colors, onInputs)
            CarbsCard(inputs, carbControls, onInputs)
            if (inputs.carbs > 0) {
                EatingCard(inputs, result.advisorAvailable, onInputs)
                AbsorptionCard(inputs, onInputs)
            }
            AapsCard {
                Column {
                    SectionLabel("INCLUDED", colors, Modifier.padding(bottom = 4.dp))
                    FactorRow("Carbs", "${inputs.carbs} g", result.carbsInsulin, colors)
                    FactorRow(
                        "BG correction", null, result.bgInsulin, colors,
                        on = inputs.useBg && result.bgCorrectionAvailable, enabled = result.bgCorrectionAvailable,
                        onToggle = { onInputs(inputs.copy(useBg = it)) }
                    )
                    FactorRow("Active insulin", null, result.iobInsulin, colors, on = inputs.useIob, iob = true, onToggle = { onInputs(inputs.copy(useIob = it)) })
                    FactorRow(
                        "15-min trend", null, result.trendInsulin, colors,
                        on = inputs.useTrend && result.trendAvailable, enabled = result.trendAvailable,
                        onToggle = { onInputs(inputs.copy(useTrend = it)) }
                    )
                    if (result.superBolusAvailable)
                        FactorRow("Superbolus", null, result.superBolusInsulin, colors, on = inputs.useSuperBolus, onToggle = { onInputs(inputs.copy(useSuperBolus = it)) })
                    result.scaledPercent?.let { FactorRow("Scaled", null, "$it%", colors) }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.bar)
                .padding(horizontal = AapsSpacing.screenH, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutcomeSummary(result, colors, Modifier.weight(1f))
            val enabled = result.outcome.commit != CalculatorOutcome.Commit.NONE
            Text(
                "Review",
                style = AapsTheme.type.title,
                color = if (enabled) colors.onAccent else colors.textTertiary,
                modifier = Modifier
                    .clip(AapsTheme.shape.button)
                    .background(if (enabled) colors.accent else colors.controlFill)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onReview)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }
}

/** The result as it stands: what Review would commit, including a cap or a carb equivalent. */
@Composable
private fun OutcomeSummary(result: WizardResult, colors: AapsColors, modifier: Modifier) {
    val outcome = result.outcome
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (result.available) units(outcome.insulin) else "--", style = AapsTheme.type.cardValue, color = colors.textPrimary)
        when {
            outcome.uncappedInsulin != null                                    -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Struck(units(outcome.uncappedInsulin), colors)
                Tag("Max bolus", colors.high)
            }
            outcome.commit == CalculatorOutcome.Commit.LOG_CARBS               -> Text("${outcome.carbs} g", style = AapsTheme.type.caption, color = colors.textSecondary)
            outcome.carbEquivalent != null                                     -> Text("Carb equivalent ${outcome.carbEquivalent}\u00A0g", style = AapsTheme.type.caption, color = colors.textSecondary)
        }
    }
}

/**
 * Where the glucose comes from and whether it counts. A stale reading is struck through (the Home
 * screen's visual for the same thing) and switches BG correction off; the pencil enters a fingerstick.
 */
@Composable
private fun GlucoseCard(inputs: WizardInputs, result: WizardResult, colors: AapsColors, onInputs: (WizardInputs) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val manual = result.glucoseSource == GlucoseSource.MANUAL
    val valueColor = when {
        result.glucoseSource == GlucoseSource.STALE -> colors.textSecondary
        else                                        -> when (result.glucoseTone) {
            GlucoseTone.LOW      -> colors.low
            GlucoseTone.HIGH     -> colors.high
            GlucoseTone.IN_RANGE -> colors.inRange
            GlucoseTone.NONE     -> colors.textSecondary
        }
    }
    AapsCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            result.glucoseText,
                            style = AapsTheme.type.cardValue.copy(
                                textDecoration = if (result.glucoseSource == GlucoseSource.STALE) TextDecoration.LineThrough else null
                            ),
                            color = valueColor
                        )
                        if (result.trendArrow.isNotBlank())
                            Text(result.trendArrow, style = AapsTheme.type.listTitle, color = valueColor, modifier = Modifier.padding(bottom = 2.dp))
                        Text(result.bgUnitsLabel, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            if (manual) AapsIcons.Bloodtype else AapsIcons.Sensors, contentDescription = null,
                            tint = colors.textTertiary, modifier = Modifier.size(16.dp)
                        )
                        when (result.glucoseSource) {
                            GlucoseSource.SENSOR -> Text(result.glucoseAge, style = AapsTheme.type.caption, color = colors.textTertiary)
                            GlucoseSource.STALE  -> {
                                Text(result.glucoseAge, style = AapsTheme.type.caption, color = colors.textTertiary)
                                Tag("Stale", colors.high)
                            }
                            GlucoseSource.MANUAL -> Text("Manual", style = AapsTheme.type.caption, color = colors.textTertiary)
                            GlucoseSource.NONE   -> Tag("No reading", colors.textSecondary)
                        }
                    }
                }
                if (manual)
                    IconBtn(Icons.Rounded.Close, "Use sensor glucose") {
                        onInputs(inputs.copy(manualBg = null))
                        editing = false
                    }
                else
                    IconBtn(Icons.Rounded.Edit, "Enter glucose") { editing = !editing }
            }
            if (editing || manual)
                NumberField(
                    label = "Glucose",
                    value = inputs.manualBg ?: result.glucoseText.replace(',', '.').toDoubleOrNull()?.coerceIn(result.bgEntryMin, result.bgEntryMax) ?: result.bgEntryMin,
                    onValue = { onInputs(inputs.copy(manualBg = it)) },
                    step = result.bgEntryStep,
                    min = result.bgEntryMin,
                    max = result.bgEntryMax,
                    decimals = result.bgEntryDecimals,
                    unit = result.bgUnitsLabel
                )
        }
    }
}

@Composable
private fun CarbsCard(inputs: WizardInputs, carbControls: WizardCarbControls, onInputs: (WizardInputs) -> Unit) {
    EntryCard("Carbs") {
        AmountStepper(
            value = inputs.carbs.toDouble(),
            onValue = { onInputs(inputs.copy(carbs = carbControls.clamp(it.toInt()))) },
            step = carbControls.step.toDouble(), min = 0.0, max = carbControls.maxCarbs.toDouble(), decimals = 0, unit = "g", name = "of carbs"
        )
        ChoiceRow {
            carbControls.quickIncrements.forEach { increment ->
                Choice(
                    if (increment > 0) "+$increment g" else "$increment g", selected = false,
                    clickLabel = if (increment >= 0) "Add $increment grams of carbs" else "Subtract ${-increment} grams of carbs"
                ) { onInputs(inputs.copy(carbs = carbControls.addIncrement(inputs.carbs, increment))) }
            }
        }
    }
}

/**
 * When the carbs are eaten. The bolus always goes in now; this tells the loop when the carbs land
 * (a pre-bolus, or carbs already eaten). With glucose high, "Later" is the bolus advisor: bolus now,
 * log nothing yet, and be reminded to eat.
 */
@Composable
private fun EatingCard(inputs: WizardInputs, advisorAvailable: Boolean, onInputs: (WizardInputs) -> Unit) {
    EntryCard("When") {
        TimeStepper(
            offsetMin = inputs.carbTime,
            onOffset = { onInputs(inputs.copy(carbTime = it, eatLater = false)) },
            minOffsetMin = -60, maxOffsetMin = 60,
            presets = listOf(0, 15, 30),
            selected = !inputs.eatLater
        ) {
            if (advisorAvailable)
                Choice("Later", selected = inputs.eatLater, icon = Icons.Rounded.Notifications) { onInputs(inputs.copy(eatLater = true, carbTime = 0)) }
        }
        if (inputs.carbTime > 0 && !inputs.eatLater)
            ToggleRow("Remind me to eat", inputs.remindToEat, { onInputs(inputs.copy(remindToEat = it)) })
    }
}

/** Extended carbs: a slow meal declared per-meal, which the loop's single absorption constant cannot describe. */
@Composable
private fun AbsorptionCard(inputs: WizardInputs, onInputs: (WizardInputs) -> Unit) {
    fun over(hours: Int) = onInputs(inputs.copy(carbDurationHours = hours.coerceIn(0, 8)))
    EntryCard("Absorption") {
        StepperRow(
            decreaseLabel = "Shorten carb absorption by 1 hour", onDecrease = { over(inputs.carbDurationHours - 1) },
            increaseLabel = "Lengthen carb absorption by 1 hour", onIncrease = { over(inputs.carbDurationHours + 1) }
        ) { StepperValue(if (inputs.carbDurationHours == 0) "fast" else "${inputs.carbDurationHours}", if (inputs.carbDurationHours == 0) "" else "h") }
        ChoiceRow {
            listOf(0, 2, 3, 4).forEach { h -> Choice(if (h == 0) "Fast" else "$h h", selected = inputs.carbDurationHours == h) { over(h) } }
        }
    }
}

/**
 * Review: exactly what the hold (or the carbs button) will commit. The numbers stay live; when they
 * move — while reviewing, or because the commit found newer data — the previous value is struck
 * through and the factor that changed is highlighted, and the button always carries the current amount.
 */
@Composable
private fun ReviewStep(
    inputs: WizardInputs,
    result: WizardResult,
    colors: AapsColors,
    onCommit: (reviewed: CalculatorOutcome, onChanged: () -> Unit) -> Unit,
    onCancel: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val baseline = remember { result }
    val outcome = result.outcome
    val changed = baseline.outcome.insulin != outcome.insulin || baseline.outcome.carbs != outcome.carbs
    fun commit() = onCommit(outcome) { haptics.performHapticFeedback(HapticFeedbackType.Reject) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
    ) {
        val delivers = outcome.commit == CalculatorOutcome.Commit.DELIVER
        val headline = if (delivers || outcome.commit == CalculatorOutcome.Commit.NONE) units(outcome.insulin) else "${outcome.carbs} g"
        val previous = if (delivers || outcome.commit == CalculatorOutcome.Commit.NONE) units(baseline.outcome.insulin) else "${baseline.outcome.carbs} g"
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (changed)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Struck(previous, colors)
                    Tag("Updated", colors.accent)
                }
            Text(headline, style = AapsTheme.type.hero, color = colors.textPrimary)
            outcome.uncappedInsulin?.let { uncapped ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Struck(units(uncapped), colors)
                    Tag("Max bolus", colors.high)
                }
            }
            when {
                delivers && inputs.eatLater -> IconLine(Icons.Rounded.Notifications, "Remind to eat", colors.accent)
                delivers && outcome.carbs > 0 -> Text("+ ${outcome.carbs} g", style = AapsTheme.type.listTitle, color = colors.textSecondary)
            }
        }

        AapsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BreakdownRow("Carbs", result.carbsInsulin, colors, changed = baseline.carbsInsulin != result.carbsInsulin)
                if (inputs.useBg && result.bgCorrectionAvailable)
                    BreakdownRow("BG correction", result.bgInsulin, colors, changed = baseline.bgInsulin != result.bgInsulin)
                if (inputs.useTrend && result.trendAvailable)
                    BreakdownRow("15-min trend", result.trendInsulin, colors, changed = baseline.trendInsulin != result.trendInsulin)
                if (inputs.useIob)
                    BreakdownRow("Active insulin", result.iobInsulin, colors, iob = true, changed = baseline.iobInsulin != result.iobInsulin)
                if (result.superBolusAvailable && inputs.useSuperBolus) BreakdownRow("Superbolus", result.superBolusInsulin, colors)
                result.scaledPercent?.let { BreakdownRow("Scaled", "$it%", colors) }
                outcome.carbEquivalent?.let { BreakdownRow("Carb equivalent", "$it g", colors) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(colors.hairline)
                        .size(1.dp)
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("Total", style = AapsTheme.type.listTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(units(outcome.insulin), style = AapsTheme.type.listTitle, color = colors.textPrimary, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        if (outcome.carbs > 0 && !inputs.eatLater) {
            val timing = when {
                inputs.carbTime != 0 -> "Eating ${EntryTime.relative(inputs.carbTime)}"
                else                -> null
            }
            val absorption = if (inputs.carbDurationHours > 0) "Absorbing over ${inputs.carbDurationHours} h" else null
            val reminder = if (inputs.remindToEat && inputs.carbTime > 0) "Reminder" else null
            listOfNotNull(timing, absorption, reminder).takeIf { it.isNotEmpty() }?.let {
                Text(it.joinToString(" · "), style = AapsTheme.type.body, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
        }
        if (result.siteWarning.isNotBlank())
            Text(result.siteWarning, style = AapsTheme.type.caption, color = colors.high, textAlign = TextAlign.Center)

        when (outcome.commit) {
            CalculatorOutcome.Commit.DELIVER   -> HoldToConfirmButton(label = "Hold to deliver ${units(outcome.insulin)}", onConfirm = ::commit, confirms = outcome to inputs.eatLater)
            CalculatorOutcome.Commit.LOG_CARBS -> PrimaryButton(label = "Log ${outcome.carbs} g", onClick = ::commit, modifier = Modifier.fillMaxWidth())
            CalculatorOutcome.Commit.NONE      -> PrimaryButton(label = "Nothing to confirm", onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "Cancel", style = AapsTheme.type.body, color = colors.textSecondary,
            modifier = Modifier
                .clip(AapsTheme.shape.pill)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 24.dp, vertical = 10.dp)
        )
    }
}

private fun units(v: Double) = String.format(Locale.getDefault(), "%.2f U", v)

@Composable
private fun SectionLabel(text: String, colors: AapsColors, modifier: Modifier = Modifier) =
    Text(text, style = AapsTheme.type.label, color = colors.textSecondary, modifier = modifier)

/** A short status word in a tinted pill — "Stale", "Updated", "Max bolus". */
@Composable
private fun Tag(text: String, tint: Color) =
    Text(
        text,
        style = AapsTheme.type.label,
        color = tint,
        modifier = Modifier
            .clip(AapsTheme.shape.pill)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )

@Composable
private fun Struck(text: String, colors: AapsColors) =
    Text(text, style = AapsTheme.type.body.copy(textDecoration = TextDecoration.LineThrough), color = colors.textTertiary)

@Composable
private fun IconLine(icon: ImageVector, text: String, tint: Color) =
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = AapsTheme.type.listTitle, color = tint)
    }

@Composable
private fun FactorRow(
    name: String,
    sub: String?,
    contribution: String,
    colors: AapsColors,
    on: Boolean = true,
    enabled: Boolean = true,
    iob: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null
) {
    val nameColor = if (enabled) colors.textOnSurfaceStrong else colors.textTertiary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = AapsTheme.type.listTitle, color = nameColor)
            if (sub != null) Text(sub, style = AapsTheme.type.caption, color = colors.textTertiary)
        }
        Text(
            if (enabled) contribution else "—",
            style = AapsTheme.type.listTitle,
            color = when {
                !enabled || !on -> colors.textTertiary
                iob             -> colors.iob
                else            -> colors.textPrimary
            },
            modifier = Modifier.padding(end = if (onToggle != null) 12.dp else 0.dp)
        )
        if (onToggle != null)
            Switch(
                checked = on, onCheckedChange = onToggle, enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedTrackColor = colors.controlFill,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedBorderColor = colors.hairline
                )
            )
    }
}

@Composable
private fun BreakdownRow(name: String, value: String, colors: AapsColors, iob: Boolean = false, changed: Boolean = false) {
    val bg by animateColorAsState(if (changed) colors.accentTint else Color.Transparent, label = "changed-row")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.cardSmall)
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 5.dp)
    ) {
        Text(name, style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AapsTheme.type.body, color = if (iob) colors.iob else colors.textPrimary)
    }
}

@Composable
private fun IconBtn(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = cd, tint = AapsTheme.colors.textSecondary)
    }
}
