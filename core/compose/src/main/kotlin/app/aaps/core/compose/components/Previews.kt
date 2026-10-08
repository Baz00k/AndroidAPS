package app.aaps.core.compose.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsUiMode

/** The appearances every component is checked in: light, dark and a 200% font. */
@Preview(name = "Light", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "200% font", widthDp = 360, fontScale = 2f)
internal annotation class ComponentPreviews

/** A component preview on the sheet surface, following the preview's night mode, with the states stacked. */
@Composable
internal fun PreviewSurface(content: @Composable ColumnScope.() -> Unit) = AapsTheme(mode = AapsUiMode.SYSTEM) {
    Column(
        Modifier
            .background(AapsTheme.colors.surface3)
            .padding(AapsSpacing.screenH),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap),
        content = content
    )
}
