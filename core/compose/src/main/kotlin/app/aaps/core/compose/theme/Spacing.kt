package app.aaps.core.compose.theme

import androidx.compose.ui.unit.dp

/** Spacing tokens from the handoff. */
object AapsSpacing {

    // The two horizontal insets add up (screen margin + card padding before any content), so both stay
    // small: a wide plot or a long value should not lose a third of the screen to padding.
    val screenH = 12.dp        // screen horizontal padding
    val cardPad = 14.dp        // card padding (12–18)
    val cardPadSmall = 12.dp
    val rowGap = 10.dp         // row gaps (8–12)
    val rowGapSmall = 8.dp
    val sectionGap = 12.dp
    val minTap = 44.dp         // minimum tap target
    val hairlineWidth = 1.dp
}
