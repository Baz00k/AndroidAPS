package app.aaps.ui.dialogs.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.AmountStepper
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.aapsSwitchColors
import app.aaps.core.compose.components.Choice
import app.aaps.core.compose.components.ChoiceRow
import app.aaps.core.compose.components.EntryCard
import app.aaps.core.compose.components.EntryTime
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

/** Longest extended-carbs spread the Calculator offers. */
private const val MAX_ABSORPTION_H = 8

/**
 * The Calculator: inputs, then Review, in one sheet. Stateless with respect to the dose math: [compute]
 * runs the existing BolusWizard for the current [WizardInputs]; [onCommit] rebuilds it from current
 * data and commits only if it still matches what was reviewed, calling back when it did not. The
 * result is recomputed every [REFRESH_MS], so the numbers on screen follow new readings and decaying
 * insulin.
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
    val haptics = LocalHapticFeedback.current
    // Inputs survive recreation; the review step does not, so a restored screen always needs a fresh review.
    var inputs by rememberSaveable(stateSaver = WizardInputs.Saver) { mutableStateOf(initialInputs.copy(carbs = carbControls.clamp(initialInputs.carbs))) }
    var reviewing by remember { mutableStateOf(false) }
    var mealDetailsExpanded by rememberSaveable { mutableStateOf(false) }
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
    // What Review was opened on; the live result is compared against it.
    var reviewed by remember { mutableStateOf<WizardResult?>(null) }
    BackHandler(enabled = reviewing) { reviewing = false }
    fun onInputs(changed: WizardInputs) {
        inputs = changed.copy(carbs = carbControls.clamp(changed.carbs))
    }

    SheetSurface(
        title = if (reviewing) "Review" else "Calculator",
        onClose = onCancel,
        onBack = if (reviewing) ({ reviewing = false }) else null,
        footer = {
            if (!reviewing) InputFooter(result, colors) {
                reviewed = result
                reviewing = true
            }
            else ReviewFooter(inputs, result.outcome) {
                // Not sent: data moved under the review. Re-read now so the new numbers are what is shown.
                onCommit(inputs, result.outcome) {
                    refresh++
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
            }
        }
    ) {
        AnimatedContent(
            targetState = reviewing,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "calculator-step"
        ) { onReview ->
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
                if (!onReview) InputCards(inputs, result, carbControls, colors, mealDetailsExpanded, { mealDetailsExpanded = !mealDetailsExpanded }, ::onInputs)
                else ReviewContent(inputs, result, reviewed ?: result, colors)
            }
        }
    }
}

@Composable
private fun InputCards(
    inputs: WizardInputs,
    result: WizardResult,
    carbControls: WizardCarbControls,
    colors: AapsColors,
    mealDetailsExpanded: Boolean,
    onToggleMealDetails: () -> Unit,
    onInputs: (WizardInputs) -> Unit
) {
    GlucoseCard(inputs, result, colors, onInputs)
    CarbsCard(inputs, carbControls, result.advisorAvailable, mealDetailsExpanded, onToggleMealDetails, onInputs)
    AapsCard {
        Column {
            SectionLabel("INCLUDED", colors, Modifier.padding(bottom = 4.dp))
            FactorRow("Carbs", "${inputs.carbs} g", result.carbsInsulin, colors)
            FactorRow(
                "BG correction", null, result.bgInsulin, colors,
                on = inputs.useBg && result.bgCorrectionAvailable, enabled = result.bgCorrectionAvailable,
                onToggle = { onInputs(inputs.copy(useBg = it)) }
            )
            // Carbs on board is dosed only against active insulin, so it holds that switch on.
            val cobCounted = inputs.useCob && result.cobAvailable
            FactorRow(
                "Carbs on board", result.cob ?: "Not available", result.cobInsulin, colors,
                on = cobCounted, enabled = result.cobAvailable,
                onToggle = { onInputs(inputs.copy(useCob = it, useIob = it || inputs.useIob)) }
            )
            FactorRow(
                "Active insulin", if (cobCounted) "Counted with carbs on board" else null, result.iobInsulin, colors,
                on = inputs.useIob || cobCounted, locked = cobCounted, iob = true, onToggle = { onInputs(inputs.copy(useIob = it)) }
            )
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

/** The result as it stands, and the way to Review it. */
@Composable
private fun InputFooter(result: WizardResult, colors: AapsColors, onReview: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutcomeSummary(result, colors, Modifier.weight(1f))
        PrimaryButton("Review", onReview, Modifier.weight(1f), enabled = result.outcome.commit != CalculatorOutcome.Commit.NONE)
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
private fun CarbsCard(
    inputs: WizardInputs,
    carbControls: WizardCarbControls,
    advisorAvailable: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onInputs: (WizardInputs) -> Unit
) {
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
                    enabled = if (increment > 0) inputs.carbs < carbControls.maxCarbs else inputs.carbs > 0,
                    clickLabel = if (increment >= 0) "Add $increment grams of carbs" else "Subtract ${-increment} grams of carbs"
                ) { onInputs(inputs.copy(carbs = carbControls.addIncrement(inputs.carbs, increment))) }
            }
        }
        // Only a collapsed header advertises the advisor; once open, the advisor's own row is in view.
        val advisorHint = advisorAvailable && !inputs.eatLater && !detailsExpanded
        Row(
            Modifier
                .fillMaxWidth()
                .clip(AapsTheme.shape.cardSmall)
                .clickable(role = Role.Button, onClickLabel = if (detailsExpanded) "Hide meal details" else "Edit meal details", onClick = onToggleDetails)
                .semantics {
                    stateDescription = when {
                        detailsExpanded -> "Expanded"
                        advisorHint     -> "Collapsed, bolus advisor available"
                        else            -> "Collapsed"
                    }
                }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Meal details", style = AapsTheme.type.listTitle, color = AapsTheme.colors.textOnSurfaceStrong)
                val summary = if (inputs.eatLater) "Carbs not logged · eat once glucose falls" else listOfNotNull(
                    "Carbs ${EntryTime.relative(inputs.carbTime).replaceFirstChar { it.lowercase() }}",
                    if (inputs.carbDurationHours == 0) "Normal absorption" else "Absorption ${inputs.carbDurationHours} h",
                    "Eat reminder".takeIf { inputs.remindToEat && inputs.carbTime > 0 }
                ).joinToString(" · ")
                Text(summary,
                     style = if (inputs.eatLater) AapsTheme.type.caption.copy(fontWeight = FontWeight.SemiBold) else AapsTheme.type.caption,
                     color = if (inputs.eatLater) AapsTheme.colors.accent else AapsTheme.colors.textSecondary,
                     maxLines = if (inputs.eatLater) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            // A horizontal marker cannot grow the sheet if the advisor becomes available on a carb tap.
            if (advisorHint) {
                Text("Advisor", style = AapsTheme.type.label, color = AapsTheme.colors.accent, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Icon(
                AapsIcons.ExpandLess,
                contentDescription = null, tint = AapsTheme.colors.textSecondary,
                modifier = Modifier.size(24.dp).rotate(if (detailsExpanded) 0f else 180f)
            )
        }
        // The accordion body shares the carb card's surface; only its header toggles expansion.
        // Groups are separated by space alone: a small label above each control, a larger gap between groups.
        if (detailsExpanded) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Eating later means the carbs are not logged now: timing and absorption are decided then.
            if (!inputs.eatLater) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MealDetailLabel("When")
                    TimeStepper(
                        offsetMin = inputs.carbTime,
                        onOffset = { onInputs(inputs.copy(carbTime = it)) },
                        minOffsetMin = -60, maxOffsetMin = 60
                    )
                    if (inputs.carbTime > 0)
                        ToggleRow("Remind me to eat", inputs.remindToEat, { onInputs(inputs.copy(remindToEat = it)) })
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MealDetailLabel("Absorption")
                    AmountStepper(
                        value = inputs.carbDurationHours.toDouble(), onValue = { onInputs(inputs.copy(carbDurationHours = it.toInt())) },
                        step = 1.0, min = 0.0, max = MAX_ABSORPTION_H.toDouble(), decimals = 0,
                        unit = "h", name = "of carb absorption", zeroLabel = "Normal"
                    )
                }
            }
            // Put data-driven advice last so it does not push the timing controls when it refreshes.
            if (advisorAvailable) {
                ToggleRow(
                    "Eat once glucose falls", inputs.eatLater, { onInputs(inputs.copy(eatLater = it, carbTime = 0)) },
                    sub = "Glucose is high. Bolus now and log the carbs when the reminder comes."
                )
            }
        }
    }
}

@Composable
private fun MealDetailLabel(text: String) =
    Text(text, style = AapsTheme.type.caption, color = AapsTheme.colors.textTertiary)

/**
 * Review: exactly what the hold (or the carbs button) will commit. The numbers stay live; when they
 * move — while reviewing, or because the commit found newer data — the value Review opened on is
 * struck through and the factor that changed is highlighted, and the button always carries the
 * current amount.
 */
@Composable
private fun ReviewContent(inputs: WizardInputs, result: WizardResult, baseline: WizardResult, colors: AapsColors) {
    val outcome = result.outcome
    val changed = baseline.outcome.insulin != outcome.insulin || baseline.outcome.carbs != outcome.carbs
    val delivers = outcome.commit == CalculatorOutcome.Commit.DELIVER
    val showsInsulin = delivers || outcome.commit == CalculatorOutcome.Commit.NONE
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (changed)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Struck(if (showsInsulin) units(baseline.outcome.insulin) else "${baseline.outcome.carbs} g", colors)
                Tag("Updated", colors.accent)
            }
        Text(if (showsInsulin) units(outcome.insulin) else "${outcome.carbs} g", style = AapsTheme.type.hero, color = colors.textPrimary)
        outcome.uncappedInsulin?.let { uncapped ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Struck(units(uncapped), colors)
                Tag("Max bolus", colors.high)
            }
        }
        when {
            delivers && inputs.eatLater   -> IconLine(Icons.Rounded.Notifications, "Carbs not logged · reminder to eat", colors.accent)
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
            if (inputs.useCob && result.cobAvailable)
                BreakdownRow("Carbs on board", result.cobInsulin, colors, changed = baseline.cobInsulin != result.cobInsulin)
            if (inputs.useIob || (inputs.useCob && result.cobAvailable))
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
        val timing = if (inputs.carbTime != 0) "Eating ${EntryTime.relative(inputs.carbTime)}" else null
        val absorption = if (inputs.carbDurationHours > 0) "Absorption ${inputs.carbDurationHours} h" else null
        val reminder = if (inputs.remindToEat && inputs.carbTime > 0) "Reminder" else null
        listOfNotNull(timing, absorption, reminder).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = AapsTheme.type.body, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
    if (result.siteWarning.isNotBlank())
        Text(result.siteWarning, style = AapsTheme.type.caption, color = colors.high, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReviewFooter(inputs: WizardInputs, outcome: CalculatorOutcome, onCommit: () -> Unit) {
    when (outcome.commit) {
        CalculatorOutcome.Commit.DELIVER   -> HoldToConfirmButton(label = "Deliver ${units(outcome.insulin)}", onConfirm = onCommit, confirms = outcome to inputs.eatLater)
        CalculatorOutcome.Commit.LOG_CARBS -> PrimaryButton(label = "Log ${outcome.carbs} g", onClick = onCommit)
        CalculatorOutcome.Commit.NONE      -> PrimaryButton(label = "Nothing to confirm", onClick = {}, enabled = false)
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
    /** Counted and cannot be switched off here (the sub says why). */
    locked: Boolean = false,
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
            Switch(checked = on, onCheckedChange = onToggle, enabled = enabled && !locked, colors = aapsSwitchColors())
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
