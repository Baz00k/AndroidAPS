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
import app.aaps.core.interfaces.sync.Sync
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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

    @Test
    fun `sync lookups skip non-Sync plugins and return only a connected client`() {
        val wear = plugin(PluginType.SYNC, WearLike::class.java, "wear", enabled = true)
        val client = syncPlugin("client", connected = false)
        val store = PluginStore(aapsLogger).apply { plugins = listOf(wear, client) }

        assertEquals(listOf(client), store.activeSyncs)
        assertNull(store.firstActiveSync)

        `when`((client as Sync).connected).thenReturn(true)
        assertSame(client, store.firstActiveSync)
    }

    @ParameterizedTest
    @EnumSource(value = PluginType::class, names = ["APS", "INSULIN", "SENSITIVITY", "SMOOTHING", "PROFILE", "BGSOURCE", "PUMP"])
    fun `invalid category registration leaves the registry unchanged`(type: PluginType) {
        val store = storeWith(plugin(PluginType.APS, APS::class.java, "aps", isDefault = true))
        val registered = store.plugins
        val invalid = plugin(type, WearLike::class.java, "invalid")

        assertThrows(IllegalArgumentException::class.java) { store.plugins = registered + invalid }
        assertSame(registered, store.plugins)
    }

    @Test
    fun `registration snapshots the list so later additions cannot bypass validation`() {
        val registered = storeWith(plugin(PluginType.APS, APS::class.java, "aps", isDefault = true)).plugins.toMutableList()
        val store = PluginStore(aapsLogger).apply { plugins = registered }
        registered.add(plugin(PluginType.PUMP, WearLike::class.java, "invalid", enabled = true))

        store.verifySelectionInCategories()

        assertEquals(registered.dropLast(1), store.plugins)
    }

    /** A plugin interface unrelated to Sync, standing in for Wear. */
    interface WearLike

    private fun syncPlugin(name: String, connected: Boolean): PluginBase =
        plugin(PluginType.SYNC, Sync::class.java, name, enabled = true).also { `when`((it as Sync).connected).thenReturn(connected) }

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
