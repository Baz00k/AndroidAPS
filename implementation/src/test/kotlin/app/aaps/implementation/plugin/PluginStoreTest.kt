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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
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

    @ParameterizedTest
    @EnumSource(value = PluginType::class, names = ["APS", "INSULIN", "SENSITIVITY", "SMOOTHING", "PROFILE", "BGSOURCE", "PUMP"])
    fun `registration rejects invalid selected default and disabled plugins before changing selection`(type: PluginType) {
        for ((enabled, isDefault) in listOf(true to false, false to true, false to false)) {
            val store = storeWith(plugin(PluginType.APS, APS::class.java, "aps", isDefault = true))
            store.verifySelectionInCategories()
            val registered = store.plugins
            val selectedPump = store.activePump
            val selectedAps = store.activeAPS
            val invalid = plugin(type, WearLike::class.java, "invalid", enabled = enabled, isDefault = isDefault)
            registered.forEach { clearInvocations(it) }

            val error = assertThrows(IllegalArgumentException::class.java) {
                store.plugins = registered + invalid
            }

            assertTrue(error.message.orEmpty().contains(type.name))
            assertTrue(error.message.orEmpty().contains("must implement"))
            assertSame(registered, store.plugins)
            assertSame(selectedPump, store.activePump)
            assertSame(selectedAps, store.activeAPS)
            registered.forEach {
                verify(it).getType()
                verifyNoMoreInteractions(it)
            }
            verify(invalid, never()).setPluginEnabled(type, true)
            verify(invalid, never()).setPluginEnabled(type, false)
        }
    }

    @Test
    fun `registration snapshots the list so later additions cannot bypass validation`() {
        val registered = storeWith(plugin(PluginType.APS, APS::class.java, "aps", isDefault = true)).plugins.toMutableList()
        val store = PluginStore(aapsLogger).apply { plugins = registered }
        registered.add(plugin(PluginType.PUMP, WearLike::class.java, "invalid", enabled = true))

        store.verifySelectionInCategories()

        assertEquals(registered.dropLast(1), store.plugins)
    }

    @Test
    fun `valid category defaults and initialization lookups are preserved`() {
        val defaults = listOf(
            plugin(PluginType.APS, APS::class.java, "aps", isDefault = true),
            plugin(PluginType.INSULIN, Insulin::class.java, "insulin", isDefault = true),
            plugin(PluginType.SENSITIVITY, Sensitivity::class.java, "sensitivity", isDefault = true),
            plugin(PluginType.SMOOTHING, Smoothing::class.java, "smoothing", isDefault = true),
            plugin(PluginType.PROFILE, ProfileSource::class.java, "profile", isDefault = true),
            plugin(PluginType.BGSOURCE, BgSource::class.java, "bg", isDefault = true),
            plugin(PluginType.PUMP, Pump::class.java, "pump", isDefault = true)
        )
        val store = PluginStore(aapsLogger).apply { plugins = defaults }
        assertSame(defaults[1], store.activeInsulin)
        assertThrows(IllegalStateException::class.java) { store.activePump }

        store.verifySelectionInCategories()

        assertEquals(
            defaults,
            listOf(store.activeAPS, store.activeInsulin, store.activeSensitivity, store.activeSmoothing,
                   store.activeProfileSource, store.activeBgSource, store.activePump)
        )
        defaults.forEach {
            val type = it.getType()
            verify(it).setPluginEnabled(type, true)
        }
    }

    @Test
    fun `enabled pump is available during initialization without enabling a default`() {
        val selected = plugin(PluginType.PUMP, Pump::class.java, "selected", enabled = true)
        val fallback = plugin(PluginType.PUMP, Pump::class.java, "default", isDefault = true)
        val store = PluginStore(aapsLogger).apply { plugins = listOf(fallback, selected) }

        assertSame(selected, store.activePump)
        verify(fallback, never()).setPluginEnabled(PluginType.PUMP, true)
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
