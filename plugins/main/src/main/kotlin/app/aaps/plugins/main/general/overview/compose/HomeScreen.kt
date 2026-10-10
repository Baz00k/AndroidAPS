package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import app.aaps.core.compose.components.disabledAlpha
import app.aaps.core.compose.components.LocalSheetDraggable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.ActionBarButton
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.components.IconButtonTone
import app.aaps.core.compose.components.RoundIconButton
import app.aaps.core.compose.components.FittedText
import app.aaps.core.compose.components.MeasurementText
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.StatusPill
import androidx.compose.foundation.layout.PaddingValues
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.color

/**
 * The redesigned Home (Overview) screen. Stateless — driven by [state] + [actions]. The glucose
 * graph is injected via [graph] so the fragment can host the existing GraphView (AndroidView).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    graph: @Composable () -> Unit,
    additionalGraphs: @Composable () -> Unit = {}
) {
    val colors = AapsTheme.colors
    var showCarbs by remember { mutableStateOf(false) }
    var showInsulin by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            // No Compose top bar here — the app's own toolbar/tab strip already sits above this fragment.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AapsSpacing.screenH)
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
            ) {
                // Alerts are the reason the loop may not be doing what the hero says, so they sit above it.
                AlertsStack(state.notifications, actions.onDismissAlert)
                HeroCard(state, actions, onCobClick = { showCarbs = true }, onIobClick = { showInsulin = true })
                if (state.supplies.isNotEmpty()) SuppliesStrip(state.supplies)
                GraphCard(state.graphRangeHours, actions.onRange, graph)
                additionalGraphs()
                Box(Modifier.padding(bottom = 4.dp))
            }
            ActionBar(state.actions, state.calculatorEnabled, actions)
        }
        if (showCarbs) CarbsUndoSheet(state.recentCarbs, actions.onDeleteCarb, onClose = { showCarbs = false })
        if (showInsulin) InsulinUndoSheet(state, actions.onDeleteInsulin, onClose = { showInsulin = false })
    }
}

@Composable
private fun HeroCard(state: HomeUiState, actions: HomeActions, onCobClick: () -> Unit, onIobClick: () -> Unit) {
    val colors = AapsTheme.colors
    val bgColor = if (state.bgStale) colors.textSecondary else state.bgTone?.color() ?: colors.textPrimary
    // The loop pill is drawn 32 dp tall inside a 48 dp touch target; the card gives up that slack above it
    // so the pill sits as far from the top edge as from the left one.
    AapsCard(shape = AapsTheme.shape.hero, contentPadding = PaddingValues(start = AapsSpacing.cardPad, end = AapsSpacing.cardPad, top = AapsSpacing.cardPad - 8.dp, bottom = AapsSpacing.cardPad)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // row 1 — loop pill (tap → Loop mode chooser) + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = buildString {
                        append(state.loopStateLabel.ifBlank { "Loop" })
                        if (state.loopSubLabel.isNotBlank()) append("  ${state.loopSubLabel}")
                    },
                    dotColor = state.loopTone?.color() ?: colors.inRange,
                    glow = state.looping,
                    labelColor = colors.textPrimary,
                    onClick = actions.onLoop
                )
                Text(state.timeAgo, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            // row 2 — BG + inline trend (left) · eventual (right)
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        state.bg,
                        style = AapsTheme.type.bigValue.copy(fontSize = 56.sp, lineHeight = 56.sp),
                        color = bgColor,
                        modifier = Modifier.drawWithContent {
                            drawContent()
                            if (state.bgStale) {
                                drawLine(
                                    color = colors.textPrimary,
                                    start = Offset(size.width * 0.05f, size.height * 0.8f),
                                    end = Offset(size.width * 0.95f, size.height * 0.2f),
                                    strokeWidth = 3.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    )
                    if (state.trendArrow.isNotBlank() || state.delta.isNotBlank())
                        Text(
                            "${state.trendArrow} ${state.delta}".trim(),
                            style = AapsTheme.type.listTitle.copy(fontWeight = FontWeight.ExtraBold),
                            color = bgColor,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                }
                if (state.eventualBg.isNotBlank())
                    Column(horizontalAlignment = Alignment.End) {
                        Text(state.eventualBg, style = AapsTheme.type.title, color = colors.textPrimary)
                        Text("EVENTUAL", style = AapsTheme.type.label, color = colors.textTertiary)
                    }
            }
            // One wrapping status line in the existing target position, not a second banner.
            // tempTarget already contains the effective range and remaining time; do not repeat it.
            val targetDescription = state.tempTarget?.let { "Temporary target · $it" } ?: state.targetRange
            if (state.stateLine.isNotBlank() || targetDescription.isNotBlank()) {
                val activeTarget = state.tempTarget != null
                // The target shown here is the control for changing it.
                Box(
                    modifier = Modifier.fillMaxWidth().then(
                        if (state.targetEditable) Modifier
                            .clip(AapsTheme.shape.cardSmall)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = if (activeTarget) "Edit or cancel temporary target" else "Set temporary target",
                                onClick = actions.onTempTarget
                            )
                        else Modifier
                    ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = bgColor)) { append(state.stateLine) }
                            if (state.stateLine.isNotBlank() && targetDescription.isNotBlank()) append(" · ")
                            withStyle(SpanStyle(color = if (activeTarget) colors.accent else colors.textSecondary)) {
                                append(targetDescription)
                            }
                        },
                        style = AapsTheme.type.caption.copy(fontWeight = FontWeight.Bold),
                        color = colors.textSecondary
                    )
                }
            }
            // divider + stat row (IOB / COB / Basal)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(colors.divider)
                    .height(1.dp)
            )
            // Shifted left by exactly the stat's own inset (see HeroStat) so the labels still line up
            // with the BG value above while their tap/ripple area keeps clear of the rounded corners.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).offset(x = -HeroStatInset)) {
                HeroStat("IOB", state.iob.ifBlank { "--" }, Modifier.weight(1f), onClick = onIobClick)
                HeroStat("COB", state.cob.ifBlank { "--" }, Modifier.weight(1f), onClick = onCobClick)
                HeroStat("BASAL", state.basal.ifBlank { "--" }, Modifier.weight(1f), valueColor = colors.accent, sub = state.basalSub, onClick = actions.onBasal)
            }
        }
    }
}

/**
 * Inset between a [HeroStat]'s clipped/clickable bounds and its content.
 *
 * `cardSmall` is a 14.dp radius, so at the top of the first line the corner curve cuts ~7.dp into the
 * row — enough to shave the top-left off the label's first glyph ("IOB" rendered as "iOB"). Padding
 * inside the clip keeps the text clear of the curve; the Row above cancels the indent with a matching
 * negative offset.
 */
private val HeroStatInset = 8.dp

@Composable
private fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = AapsTheme.colors.textPrimary,
    sub: String = "",
    onClick: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    Column(
        (if (onClick != null) modifier.clip(AapsTheme.shape.cardSmall).clickable(onClick = onClick) else modifier)
            .padding(horizontal = HeroStatInset, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, style = AapsTheme.type.label, color = colors.textSecondary)
        MeasurementText(value, AapsTheme.type.cardValue.copy(fontSize = 18.sp, lineHeight = 20.sp), valueColor)
        // sub kept readable (secondary color, real caption size) — was too small/dark to see before
        if (sub.isNotBlank()) Text(sub, style = AapsTheme.type.caption, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun SuppliesStrip(supplies: List<HomeUiState.Supply>) {
    // Each pill is a 2-line tile (dot/ring + label on top, value below) so all of them — up to 4 after a
    // cannula change (Cannula + Sensor + Reservoir + Battery) — fit on ONE row without squashing.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        supplies.forEach { s -> SupplyCell(s, Modifier.weight(1f)) }
    }
}

/**
 * A supply as a compact 2-line tile: line 1 = indicator (a depleting COUNTDOWN ring when the supply
 * carries a life [HomeUiState.Supply.fraction], e.g. the sensor; else a plain dot) + label; line 2 =
 * value. Equal-width (weight) so 3–4 supplies share the row cleanly.
 */
@Composable
private fun SupplyCell(s: HomeUiState.Supply, modifier: Modifier) {
    val colors = AapsTheme.colors
    Column(
        modifier.clip(AapsTheme.shape.cardSmall).background(colors.controlFill).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val dot = s.dotTone.color()
            if (s.fraction != null) CountdownRing(s.fraction, dot, size = 12.dp) else Dot(dot, size = 8.dp)
            FittedText(s.label, AapsTheme.type.caption, colors.textSecondary)
        }
        MeasurementText(s.value, AapsTheme.type.listTitle, colors.textPrimary)
    }
}

@Composable
private fun CountdownRing(fraction: Float, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val track = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f)
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.20f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, -90f, 360f, false, topLeft = topLeft, size = arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(
            color, -90f, 360f * fraction.coerceIn(0f, 1f), false,
            topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
}

@Composable
private fun GraphCard(rangeHours: Int, onRange: (Int) -> Unit, graph: @Composable () -> Unit) {
    val colors = AapsTheme.colors
    val ranges = listOf(6, 12, 24)
    val selected = ranges.indexOfFirst { it >= rangeHours }.let { if (it < 0) ranges.lastIndex else it }
    // Match the additional cards so all plots share exactly the same horizontal time mapping.
    // The range picker is drawn 40 dp tall inside a 48 dp touch target, so the card gives up that slack
    // above it: its drawn edge, not its touch target, then sits one padding from the card's edge.
    AapsCard(contentPadding = PaddingValues(start = CHART_CARD_PADDING_H, end = CHART_CARD_PADDING_H, top = 12.dp, bottom = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The plot beneath runs wider than the card's content so its time axis lines up with the other
            // graphs; the header keeps the card's own keyline.
            Row(Modifier.padding(horizontal = AapsSpacing.cardPad - CHART_CARD_PADDING_H), verticalAlignment = Alignment.CenterVertically) {
                Text("Glucose", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                SegmentedControl(
                    options = ranges.map { "${it}h" },
                    selectedIndex = selected,
                    onSelect = { onRange(ranges[it]) }
                )
            }
            graph()
        }
    }
}

@Composable
private fun CarbsUndoSheet(
    carbs: List<HomeUiState.CarbEntry>,
    onDelete: (HomeUiState.CarbEntry) -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    HomeSheet(onClose) { close ->
        SheetSurface(title = "Recent carbs", onClose = { close {} }) {
            if (carbs.isEmpty()) {
                Text("No carb entries in the last few hours.", style = AapsTheme.type.body, color = colors.textSecondary)
            } else {
                Text("Remove a mistaken or duplicate entry.", style = AapsTheme.type.caption, color = colors.textTertiary)
                AapsCard(Modifier.fillMaxWidth()) {
                    Column {
                        carbs.forEachIndexed { i, c ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.grams, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                                    Text(c.time, style = AapsTheme.type.caption, color = colors.textTertiary)
                                }
                                RoundIconButton(Icons.Rounded.Delete, "Remove ${c.grams}", onClick = { onDelete(c) }, tone = IconButtonTone.Danger)
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(8.dp))
        }
    }
}

/**
 * IOB detail + undo. Tapping IOB used to open a plain text dialog; it now also lists the boluses
 * behind that number so a dose the pump never actually delivered can be taken back out. That is the
 * only way to repair IOB from inside the app — the redesigned History is read-only — and it matters
 * because an unconfirmed bolus is deliberately recorded as delivered (over-stating IOB is the safe
 * side of that guess, but it still has to be correctable when the pump turns out to have been empty).
 */
@Composable
private fun InsulinUndoSheet(
    state: HomeUiState,
    onDelete: (HomeUiState.InsulinEntry) -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    HomeSheet(onClose) { close ->
        SheetSurface(title = "Insulin on board", onClose = { close {} }) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
            ) {
                AapsCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailRow("Total", state.iob.ifBlank { "--" })
                        DetailRow("From boluses", state.iobBolus.ifBlank { "--" })
                        DetailRow("From basal", state.iobBasal.ifBlank { "--" })
                    }
                }
                if (state.recentInsulin.isEmpty()) {
                    Text("No boluses in the last few hours.", style = AapsTheme.type.body, color = colors.textSecondary)
                } else {
                    Text(
                        "Remove a dose the pump did not actually deliver.",
                        style = AapsTheme.type.caption, color = colors.textTertiary
                    )
                    AapsCard(Modifier.fillMaxWidth()) {
                        Column {
                            state.recentInsulin.forEachIndexed { i, e ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.units, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                                        Text(
                                            if (e.kind.isBlank()) e.time else "${e.time} · ${e.kind}",
                                            style = AapsTheme.type.caption, color = colors.textTertiary
                                        )
                                    }
                                    if (e.removable)
                                        RoundIconButton(Icons.Rounded.Delete, "Remove ${e.units}", onClick = { onDelete(e) }, tone = IconButtonTone.Danger)
                                    else
                                        Text(
                                            "Cancel first", style = AapsTheme.type.caption, color = colors.textTertiary,
                                            modifier = Modifier.widthIn(max = 72.dp)
                                        )
                                }
                            }
                        }
                    }
                }
                Box(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = AapsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AapsTheme.type.listTitle, color = colors.textPrimary)
    }
}

/**
 * The treatment bar: the primary actions the user keeps on Home, with the Calculator as the widest,
 * filled button, and "+" for everything else. The bar never reflows on availability, only on the
 * user's own shortcut choices.
 */
@Composable
private fun ActionBar(layout: HomeActionLayout, calculatorEnabled: Boolean, actions: HomeActions) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bar)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        layout.bar.forEach { shortcut ->
            when (shortcut) {
                HomeShortcut.CALCULATOR -> ActionBarButton(
                    shortcutLabel(shortcut), shortcutIcon(shortcut), actions.onCalculator, Modifier.weight(1.5f),
                    emphasized = true, enabled = calculatorEnabled
                )
                else                    -> ActionBarButton(shortcutLabel(shortcut), shortcutIcon(shortcut), { actions.onShortcut(shortcut) }, Modifier.weight(1f))
            }
        }
        if (layout.menu.isNotEmpty()) MoreMenu(layout.menu, calculatorEnabled, actions)
    }
}

private fun shortcutLabel(shortcut: HomeShortcut) = when (shortcut) {
    HomeShortcut.CALCULATOR -> "Calculator"
    HomeShortcut.CARBS      -> "Carbs"
    HomeShortcut.INSULIN    -> "Insulin"
}

private fun shortcutIcon(shortcut: HomeShortcut) = when (shortcut) {
    HomeShortcut.CALCULATOR -> AapsIcons.Calculate
    HomeShortcut.CARBS      -> AapsIcons.Restaurant
    HomeShortcut.INSULIN    -> AapsIcons.Vaccines
}

/**
 * "+": the treatment actions that are not on the bar, as a native modal bottom sheet. A hidden
 * primary shortcut comes first, separated from the secondary actions below it.
 */
@Composable
private fun MoreMenu(items: List<HomeMenuItem>, calculatorEnabled: Boolean, actions: HomeActions) {
    var open by remember { mutableStateOf(false) }
    // As tall as the action bar it ends.
    RoundIconButton(Icons.Rounded.Add, "More actions", onClick = { open = true }, size = 58.dp)
    if (open) HomeSheet(onClose = { open = false }) { close ->
        fun run(action: () -> Unit) = close { action() }
        MenuSurface {
            items.forEachIndexed { i, item ->
                if (i > 0 && items[i - 1] is HomeMenuItem.Shortcut && item !is HomeMenuItem.Shortcut)
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(AapsTheme.colors.divider))
                when (item) {
                    is HomeMenuItem.Shortcut      -> MenuRow(
                        shortcutLabel(item.shortcut), shortcutIcon(item.shortcut),
                        enabled = item.shortcut != HomeShortcut.CALCULATOR || calculatorEnabled
                    ) { run { actions.onShortcut(item.shortcut) } }
                    is HomeMenuItem.TempTarget    -> MenuRow("Temporary target", AapsIcons.GpsFixed, status = item.status) { run(actions.onTempTarget) }
                    is HomeMenuItem.ExtendedBolus -> MenuRow("Extended bolus", AapsIcons.Timelapse, status = item.status, enabled = item.enabled) {
                        run(if (item.status != null) actions.onCancelExtendedBolus else actions.onExtendedBolus)
                    }
                    HomeMenuItem.Calibration      -> MenuRow("Calibrate CGM", AapsIcons.Bloodtype) { run(actions.onCalibration) }
                }
            }
        }
    }
}

/** A list sheet: the grabber and rows, no title — the rows are the content. */
@Composable
private fun MenuSurface(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.sheet)
            .background(AapsTheme.colors.surface3)
            .padding(bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).clip(AapsTheme.shape.pill).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f)))
        }
        content()
    }
}

/** A menu entry; [status] is the live state of a running action (a target, an extended bolus). */
@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, status: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .disabledAlpha(enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = AapsSpacing.screenH, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = AapsTheme.type.listTitle, color = colors.textPrimary)
            if (status != null) Text(status, style = AapsTheme.type.caption, color = colors.accent)
        }
    }
}

/**
 * The Home screen's sheets, as Material modal bottom sheets: they slide up, follow a drag and are
 * dismissed by dragging down, the scrim or Back. [content] receives `close`, which slides the sheet
 * away before running what comes next (so an action's own dialog does not open under a closing sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSheet(onClose: () -> Unit, content: @Composable (close: (after: () -> Unit) -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // One close per sheet. The action runs only after the sheet is really gone; if the close is
    // cancelled (the screen left), so is the action.
    val close: (() -> Unit) -> Unit = { after ->
        if (!closing) {
            closing = true
            scope.launch {
                sheetState.hide()
                onClose()
                after()
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = AapsTheme.colors.surface3,
        shape = AapsTheme.shape.sheet,
        scrimColor = AapsTheme.colors.scrim,
        dragHandle = null
    ) {
        CompositionLocalProvider(LocalSheetDraggable provides true) { content(close) }
    }
}
