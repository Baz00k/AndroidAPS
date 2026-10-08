package app.aaps.core.compose.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii, from Material 3's shape scale.
 *
 * Read through [AapsTheme.shape], never as a constant — a skin supplies its own set, and a square
 * skin that still drew round cards would look broken rather than deliberate.
 */
@Immutable
data class AapsShapes(
    val hero: CornerBasedShape,        // hero / large card
    val card: CornerBasedShape,
    val cardSmall: CornerBasedShape,
    val pill: CornerBasedShape,        // chip / pill
    val button: CornerBasedShape,
    val iconButton: CornerBasedShape,
    val sheet: CornerBasedShape,       // bottom sheet, top corners only
    val extraSmall: CornerBasedShape
)

/** The seed at which [aapsShapes] reproduces Material's own scale. */
val DefaultCornerRadius: Dp = 16.dp

/**
 * Derive the whole set from one radius seed.
 *
 * One value is the entire shape language: `aapsShapes(0.dp)` squares off every corner in the app,
 * including the pills, which is what a hard-edged skin needs and what a per-token file would make
 * needlessly laborious to express. At [DefaultCornerRadius] the steps land on Material's scale.
 */
fun aapsShapes(radius: Dp = DefaultCornerRadius): AapsShapes {
    val r = radius.coerceAtLeast(0.dp)
    fun step(factor: Float) = RoundedCornerShape(r * factor)
    // A pill is fully rounded by definition — unless the skin has no curves at all, in which case a
    // stadium shape would be the one thing breaking the language. Material buttons are pills too.
    val pill = if (r <= 0.dp) RoundedCornerShape(0.dp) else RoundedCornerShape(50)
    return AapsShapes(
        hero = step(1.5f),         // 24
        card = step(1f),           // 16, Material "large"
        cardSmall = step(0.75f),   // 12, "medium"
        pill = pill,
        button = pill,
        iconButton = pill,
        sheet = RoundedCornerShape(topStart = r * 1.75f, topEnd = r * 1.75f),  // 28, "extra large"
        extraSmall = step(0.5f)    // 8, "small"
    )
}

/** The shape set of the built-in skins. */
val DefaultAapsShapes = aapsShapes()

/** M3 [Shapes] from the same seed, so Material components keep the skin's corners. */
fun aapsM3Shapes(radius: Dp): Shapes {
    val r = radius.coerceAtLeast(0.dp)
    return Shapes(
        extraSmall = RoundedCornerShape(r * 0.25f),
        small = RoundedCornerShape(r * 0.5f),
        medium = RoundedCornerShape(r * 0.75f),
        large = RoundedCornerShape(r),
        extraLarge = RoundedCornerShape(r * 1.75f)
    )
}
