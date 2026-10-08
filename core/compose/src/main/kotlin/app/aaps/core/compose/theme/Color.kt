package app.aaps.core.compose.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * AAPS colour tokens.
 *
 * Three families, kept strictly separate — this separation is the core UX fix for
 * "unclear what's interactive":
 *  - Chrome (surfaces / text)    — neutral, non-semantic.
 *  - Semantic (glucose / health) — greens/ambers/reds, RESERVED for glucose & status. Never a control.
 *  - Accent (interactive)        — the "is-tappable" signal. Anything the user can tap.
 *
 * The built-in appearances take chrome and accent from Material 3 colour roles, so the app looks like
 * the platform around it: the device's dynamic (wallpaper) scheme on Android 12+, Material's baseline
 * scheme below that. Semantic colours never come from the wallpaper — personalisation must not change
 * what a glucose colour means.
 */

/** Semantic glucose / status colors for a dark ground — RESERVED. Never use for generic controls. */
object AapsSemantic {

    val inRange = Color(0xFF3ED598)    // green — in-range / good
    val high = Color(0xFFFFB84D)       // amber — high
    val low = Color(0xFFFF5C6C)        // red — low / urgent
    val veryLow = Color(0xFFD8452A)    // deep red — 4.0:1 on `surface`; #B0341F was 2.8:1
    val veryHigh = Color(0xFFD98200)   // deep amber
    val iob = Color(0xFFFF9AA2)        // soft red — IOB / insulin-reducing
}

/**
 * Light counterpart of [AapsSemantic]. Not a tint of the dark set: amber and green are the hardest
 * colours to keep legible on white, so this family is noticeably deeper. Contrast targets: >=4.5:1
 * for anything that carries clinical meaning at small sizes, >=3:1 for the hero readouts.
 */
object AapsLightSemantic {

    val inRange = Color(0xFF0F7A55)
    val high = Color(0xFFA96400)
    val low = Color(0xFFC62633)
    val veryLow = Color(0xFF6B0F07)
    val veryHigh = Color(0xFF5C3200)
    val iob = Color(0xFFB5485A)
}

/** Material's baseline schemes: what a built-in appearance renders where dynamic colour is unavailable. */
internal val BaselineDarkScheme = darkColorScheme()
internal val BaselineLightScheme = lightColorScheme()

/** How a built-in skin derives its colours from a Material scheme. */
enum class MaterialGround {

    /** The scheme as Material defines it. */
    Tonal,

    /**
     * For OLED panels, where an unlit pixel draws no power and is genuinely black: on the dark ground,
     * everything the size of the screen — the screen itself, bars, sheets — becomes #000. Cards stay
     * filled, one tone below the tonal dark ground's, because outlines alone turn a screen into a
     * wireframe; nested panels, dialogs, menus and controls keep their tones so they stay findable.
     */
    TrueBlack
}

/** The Material scheme for one ground: dynamic where the device offers it, else the baseline. */
fun materialScheme(context: Context?, dark: Boolean, ground: MaterialGround = MaterialGround.Tonal): ColorScheme {
    val scheme = when {
        context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark                                                               -> BaselineDarkScheme
        else                                                               -> BaselineLightScheme
    }
    return if (dark && ground == MaterialGround.TrueBlack) scheme.withBlackGrounds() else scheme
}

private fun ColorScheme.withBlackGrounds(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black
)

/**
 * Map Material roles onto the app's tokens.
 *
 * Cards are brighter than the screen on both grounds, as in current Android system apps: white cards on
 * a grey screen in light, lifted cards on a near-black screen in dark. Accent text uses `primary`
 * rather than an on-container ink because it also marks tappable text on plain cards, where a
 * neutral ink would stop reading as a control.
 */
fun ColorScheme.toAapsColors(dark: Boolean, ground: MaterialGround = MaterialGround.Tonal): AapsColors {
    val colors = tonalColors(dark)
    // Material has no role for "a card on black", so the true-black ground picks one: the container
    // just above the black ones, which a scheme from [materialScheme] leaves tonal.
    return if (dark && ground == MaterialGround.TrueBlack)
        colors.copy(background = Color.Black, surface = surfaceContainerLow, surface3 = Color.Black, bar = Color.Black)
    else colors
}

private fun ColorScheme.tonalColors(dark: Boolean): AapsColors = AapsColors(
    background = if (dark) surface else surfaceContainer,
    surface = if (dark) surfaceContainer else surfaceContainerLowest,
    surface2 = surfaceContainerHigh,
    surface3 = surfaceContainerLow,
    bar = surfaceContainer,
    scrim = scrim.copy(alpha = 0.32f),
    hairline = outlineVariant,
    divider = outlineVariant,
    controlFill = surfaceContainerHighest,
    switchTrackOff = surfaceContainerHighest,
    switchKnobOff = outline,
    textPrimary = onSurface,
    textSecondary = onSurfaceVariant,
    textTertiary = outline,
    textOnSurfaceStrong = onSurface,
    inRange = if (dark) AapsSemantic.inRange else AapsLightSemantic.inRange,
    high = if (dark) AapsSemantic.high else AapsLightSemantic.high,
    low = if (dark) AapsSemantic.low else AapsLightSemantic.low,
    veryLow = if (dark) AapsSemantic.veryLow else AapsLightSemantic.veryLow,
    veryHigh = if (dark) AapsSemantic.veryHigh else AapsLightSemantic.veryHigh,
    iob = if (dark) AapsSemantic.iob else AapsLightSemantic.iob,
    accent = primary,
    accentOnLight = primary,
    accentTint = secondaryContainer,
    accentTintStrong = secondaryContainer,
    onAccent = onPrimary
)

/**
 * A semantic tone named where the *meaning* is known but the theme is not.
 *
 * Fragments, dialogs and other state builders run outside composition, so they cannot read
 * [LocalAapsColors] — historically they reached for [AapsSemantic] directly and baked one palette's
 * literal [Color] into their UI state, which is what made the design tokens unswappable. Those
 * builders now name a tone; the composable resolves it against the live theme with
 * [app.aaps.core.compose.theme.color].
 */
enum class AapsTone {

    InRange,
    High,
    Low,
    VeryLow,
    VeryHigh,

    /** The interactive / brand accent — for state that is a control, not a glucose reading. */
    Accent,

    /** No status to report (unread, disconnected, not applicable) — renders as tertiary text ink. */
    Neutral
}
