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

    @Test
    fun `sync lookups skip sync-category plugins that are not a Sync`() {
        // Wear sits in the SYNC category without implementing Sync. Casting the whole category to
        // Sync crashed the Objectives screen as soon as the lookup reached it.
        val wear = plugin(PluginType.SYNC, WearLike::class.java, "wear", enabled = true)
        val offline = syncPlugin("offline", connected = false)
        val online = syncPlugin("online", connected = true)
        val store = storeWith(plugin(PluginType.APS, APS::class.java, "aps", isDefault = true)).apply {
            plugins = listOf(wear, offline, online) + plugins
        }

        assertSame(online, store.firstActiveSync)
        assertEquals(listOf(offline, online), store.activeSyncs)
    }

    @Test
    fun `no connected sync is reported as none rather than failing`() {
        val store = storeWith(plugin(PluginType.APS, APS::class.java, "aps", isDefault = true)).apply {
            plugins = listOf(plugin(PluginType.SYNC, WearLike::class.java, "wear", enabled = true), syncPlugin("offline", connected = false)) + plugins
        }

        assertNull(store.firstActiveSync)
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
