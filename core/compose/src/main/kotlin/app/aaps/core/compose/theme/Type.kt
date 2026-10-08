package app.aaps.core.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.R

/**
 * Google Sans Flex — the typeface of current Android system apps — vendored as a variable font
 * (`res/font/google_sans_flex.ttf`) cut down to its `wght` axis; see `LICENSE-GoogleSansFlex-OFL.txt`.
 * We derive each static weight via [FontVariation] — requires API 26+, below every module minSdk.
 * Scripts it does not cover fall back to the system font glyph by glyph.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun googleSansFlex(weight: FontWeight) =
    Font(
        R.font.google_sans_flex,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
    )

val GoogleSansFlex = FontFamily(
    googleSansFlex(FontWeight.Normal),     // 400
    googleSansFlex(FontWeight.Medium),     // 500
    googleSansFlex(FontWeight.SemiBold),   // 600
    googleSansFlex(FontWeight.Bold)        // 700
)

/** Tabular figures for every numeric readout, so digits don't jitter as values change. */
val TabularNums = "tnum"

/**
 * The app type scale. Not all roles map onto M3's [Typography] names, so the app-specific roles
 * (hero BG value, big value, card value, …) live here, with Material's weights: regular for reading,
 * medium for titles and labels, and medium for the readouts so they carry emphasis without the
 * heavy black weights Material reserves for nothing.
 *
 * Read it through [AapsTheme.type], never as a constant: a skin supplies its own scale, and a style
 * captured outside composition would pin the screen to whatever font happened to be loaded first.
 */
@Immutable
data class AapsTextStyles(
    val hero: TextStyle,
    val bigValue: TextStyle,
    val cardValue: TextStyle,
    val title: TextStyle,
    val listTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle
)

/**
 * Build the scale for a family.
 *
 * [singleWeight] is for skins whose font ships one weight — a pixel font, typically. Asking Compose
 * for ExtraBold from a one-weight family gets synthetic bold, which smears exactly the sharp edges
 * such a font exists to provide, so those skins flatten every role to [FontWeight.Normal] and lean
 * on size for hierarchy instead.
 */
fun aapsTextStyles(
    family: FontFamily = GoogleSansFlex,
    scale: Float = 1f,
    singleWeight: Boolean = false
): AapsTextStyles {
    fun w(weight: FontWeight) = if (singleWeight) FontWeight.Normal else weight
    return AapsTextStyles(
        // Huge hero BG value, colored by glucose range at the call site.
        hero = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Medium),
            fontSize = (78 * scale).sp, lineHeight = (78 * scale).sp, letterSpacing = (-0.02).em,
            fontFeatureSettings = TabularNums
        ),
        // Big value (dose, %).
        bigValue = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Medium),
            fontSize = (48 * scale).sp, lineHeight = (52 * scale).sp, letterSpacing = (-0.01).em,
            fontFeatureSettings = TabularNums
        ),
        cardValue = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Normal),
            fontSize = (24 * scale).sp, lineHeight = (30 * scale).sp,
            fontFeatureSettings = TabularNums
        ),
        // Screen / sheet title — Material "title medium".
        title = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Medium),
            fontSize = (16 * scale).sp, lineHeight = (24 * scale).sp, letterSpacing = 0.01.em
        ),
        // "Title small".
        listTitle = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Medium),
            fontSize = (14 * scale).sp, lineHeight = (20 * scale).sp, letterSpacing = 0.007.em
        ),
        // "Body medium".
        body = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Normal),
            fontSize = (14 * scale).sp, lineHeight = (20 * scale).sp, letterSpacing = 0.018.em
        ),
        // "Label small".
        label = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Medium),
            fontSize = (11 * scale).sp, lineHeight = (16 * scale).sp, letterSpacing = 0.045.em
        ),
        // "Body small".
        caption = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Normal),
            fontSize = (12 * scale).sp, lineHeight = (16 * scale).sp, letterSpacing = 0.033.em
        )
    )
}

/** The scale of the built-in skins. */
val DefaultAapsTextStyles = aapsTextStyles()

/**
 * Material's own [Typography] in the skin's font, so any Material component (dialogs, switches,
 * menus) matches the app text rather than staying on the platform default.
 */
fun aapsM3Typography(family: FontFamily, singleWeight: Boolean = false): Typography {
    fun TextStyle.inFamily() = copy(fontFamily = family, fontWeight = if (singleWeight) FontWeight.Normal else fontWeight)
    return Typography().run {
        copy(
            displayLarge = displayLarge.inFamily(),
            displayMedium = displayMedium.inFamily(),
            displaySmall = displaySmall.inFamily(),
            headlineLarge = headlineLarge.inFamily(),
            headlineMedium = headlineMedium.inFamily(),
            headlineSmall = headlineSmall.inFamily(),
            titleLarge = titleLarge.inFamily(),
            titleMedium = titleMedium.inFamily(),
            titleSmall = titleSmall.inFamily(),
            bodyLarge = bodyLarge.inFamily(),
            bodyMedium = bodyMedium.inFamily(),
            bodySmall = bodySmall.inFamily(),
            labelLarge = labelLarge.inFamily(),
            labelMedium = labelMedium.inFamily(),
            labelSmall = labelSmall.inFamily()
        )
    }
}
