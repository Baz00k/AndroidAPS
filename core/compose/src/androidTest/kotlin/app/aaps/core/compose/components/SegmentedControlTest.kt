package app.aaps.core.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class SegmentedControlTest {

    @get:Rule val compose = createComposeRule()

    @Test fun theTrackLeavesItsInsetAroundALabelThatWrapsAtALargeFont() {
        compose.setContent {
            // Narrow and at the largest font scale, so "Target" wraps onto two lines.
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                AapsTheme {
                    Column(Modifier.width(220.dp)) {
                        SegmentedControl(listOf("Basal", "IC", "ISF", "Target"), selectedIndex = 3, onSelect = {}, fillWidth = true, modifier = Modifier.testTag("track"))
                    }
                }
            }
        }
        val track = compose.onNodeWithTag("track").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Target", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dp = compose.density.density
        // A wrapped label is taller than a touch target; the track must still hold it with room to spare,
        // rather than the selected segment spilling past the track's edges.
        assertThat(label.height).isGreaterThan(48 * dp)
        assertThat(label.top - track.top).isAtLeast(4 * dp - 1)
        assertThat(track.bottom - label.bottom).isAtLeast(4 * dp - 1)
    }
}
