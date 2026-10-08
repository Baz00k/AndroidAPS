package app.aaps.plugins.configuration.configBuilder.compose

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.aaps.core.compose.theme.AapsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class ConfigScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val pumps = ConfigCategory(
        title = "Pump", description = "", multiple = false,
        options = listOf(
            ConfigOption(index = 0, name = "Virtual Pump", description = "", selected = true, fixed = false),
            ConfigOption(index = 1, name = "Medtrum", description = "", selected = false, fixed = false)
        )
    )

    @Test
    fun choosingTheActivePumpAgainDoesNotSwitchToItAgain() {
        // Switching to a pump reconnects it, so re-selecting the active one must not reach the plugin.
        val selections = mutableListOf<Pair<Int, Boolean>>()
        compose.setContent {
            AapsTheme { ConfigScreen(ConfigUiState(categories = listOf(pumps)), { _, _ -> }, {}, { i, on -> selections += i to on }) }
        }

        compose.onNodeWithText("Virtual Pump").performClick()
        compose.onNodeWithText("Medtrum").performClick()

        assertThat(selections).containsExactly(1 to true)
    }
}
