package app.aaps.implementation.plugin

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.Sensitivity
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileSource
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.interfaces.source.BgSource
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.withSettings
import org.mockito.Mockito.`when`

class PluginStoreTest : TestBase() {

    @Test
    fun `missing algorithm selection falls back to the registered default`() {
        val defaultAps = plugin(PluginType.APS, APS::class.java, "default", isDefault = true)
        val otherAps = plugin(PluginType.APS, APS::class.java, "other")
        val store = storeWith(defaultAps, otherAps)

        store.verifySelectionInCategories()

        assertSame(defaultAps, store.activeAPS)
        verify(defaultAps).setPluginEnabled(PluginType.APS, true)
        verify(otherAps, never()).setPluginEnabled(PluginType.APS, true)
    }

    @Test
    fun `existing enabled algorithm is preserved`() {
        val defaultAps = plugin(PluginType.APS, APS::class.java, "default", isDefault = true)
        val selectedAps = plugin(PluginType.APS, APS::class.java, "selected", enabled = true)
        val store = storeWith(defaultAps, selectedAps)

        store.verifySelectionInCategories()

        assertSame(selectedAps, store.activeAPS)
        verify(defaultAps, never()).setPluginEnabled(PluginType.APS, true)
        verify(selectedAps, never()).setPluginEnabled(PluginType.APS, false)
    }

    private fun storeWith(vararg algorithms: PluginBase) = PluginStore(aapsLogger).apply {
        plugins = algorithms.toList() + listOf(
            plugin(PluginType.INSULIN, Insulin::class.java, "insulin", enabled = true),
            plugin(PluginType.SENSITIVITY, Sensitivity::class.java, "sensitivity", enabled = true),
            plugin(PluginType.SMOOTHING, Smoothing::class.java, "smoothing", enabled = true),
            plugin(PluginType.PROFILE, ProfileSource::class.java, "profile", enabled = true),
            plugin(PluginType.BGSOURCE, BgSource::class.java, "bgSource", enabled = true),
            plugin(PluginType.PUMP, Pump::class.java, "pump", enabled = true)
        )
    }

    private fun plugin(
        type: PluginType,
        pluginInterface: Class<*>,
        name: String,
        isDefault: Boolean = false,
        enabled: Boolean = false
    ): PluginBase = mock(PluginBase::class.java, withSettings().extraInterfaces(pluginInterface)).also {
        `when`(it.getType()).thenReturn(type)
        `when`(it.name).thenReturn(name)
        `when`(it.isDefault()).thenReturn(isDefault)
        `when`(it.isEnabled(type)).thenReturn(enabled)
    }
}
