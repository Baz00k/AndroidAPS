package app.aaps.plugins.main.general.themes

import android.os.Looper
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
import app.aaps.core.compose.theme.AapsAppearances
import app.aaps.core.compose.theme.AapsSkin
import app.aaps.core.compose.theme.AapsSkins
import app.aaps.core.compose.theme.AapsSkinState
import app.aaps.core.compose.theme.AapsUiMode
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventThemeSwitch
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeSwitcherPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val skinStore: SkinStore,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .neverVisible(true)
        .alwaysEnabled(true)
        .showInList { false }
        .pluginName(R.string.theme_switcher),
    aapsLogger, rh
) {

    private val disposable = CompositeDisposable()

    override fun onStart() {
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .subscribe {
                if (it.isChanged(StringKey.GeneralSkin.key)) {
                    val recreateNeeded = applyAppearance()
                    // Only the XML screens need tearing down, and only when their night mode actually
                    // moved. The Compose half repaints from snapshot state, so a palette-only change
                    // (Dark -> Midnight) must NOT blink the app.
                    if (recreateNeeded) rxBus.send(EventThemeSwitch())
                }
            }
    }

    /**
     * Read installed skin files, without publishing them.
     *
     * Touches storage, so it belongs off the main thread. Deliberately reads nothing but the files:
     * the registry assignment is [publishInstalledSkins], which has to happen on the main thread.
     * Never throws: a corrupt file on disk must not stop the app from drawing, and the built-in
     * skins are always there to fall back on.
     */
    fun readInstalledSkins(): List<AapsSkin> = try {
        skinStore.installed()
    } catch (e: Exception) {
        aapsLogger.error(LTag.UI, "Could not read installed skins: ${e.message}")
        emptyList()
    }

    /**
     * Hand a list from [readInstalledSkins] to the UI.
     *
     * **Main thread only.** [AapsSkins.installed] is Compose snapshot state, and the UI reads it
     * while holding a render snapshot: neither the state object nor its first record may be created
     * after that snapshot was taken, or the next read from it throws "Reading a state that was
     * created after the snapshot was taken" and kills the process. Startup is a cold-start race —
     * whether a worker or the UI thread gets there first is up to the scheduler — so the storage read
     * and this assignment are separate by design. Do not fold them back together.
     */
    @MainThread
    fun publishInstalledSkins(skins: List<AapsSkin>) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Installed skins must be published on the main thread" }
        AapsSkins.installed = skins
    }

    /**
     * Re-read and publish the installed skins in one step, for callers already on the main thread —
     * `SkinManagerActivity`, after installing or deleting a skin.
     *
     * Startup does not use this: `MainApp` reads on its doInit worker and only calls
     * [publishInstalledSkins] on the main thread, so the snapshot state is still only ever created
     * there.
     */
    @MainThread
    fun reloadInstalledSkins() {
        publishInstalledSkins(readInstalledSkins())
    }

    /**
     * Applies the chosen appearance to BOTH halves of the app: [AapsSkinState] for the redesigned
     * Compose screens, and [AppCompatDelegate] for the XML ones, which have no concept of a skin and
     * can only be told light or dark.
     *
     * `GeneralDarkMode` is kept in sync rather than read: it is no longer a setting the user sees,
     * but it is an upstream key that survives preference export/import, so leaving it contradicting
     * the visible theme would be a trap for anyone reading it later.
     *
     * @return true if the XML night mode changed, i.e. the activities have to be recreated.
     */
    @MainThread
    fun applyAppearance(): Boolean {
        val appearance = AapsAppearances.byId(migratedAppearanceId())
        AapsSkinState.appearanceId = appearance.id

        val nightMode = when (appearance.mode) {
            AapsUiMode.LIGHT  -> MODE_NIGHT_NO
            AapsUiMode.DARK   -> MODE_NIGHT_YES
            AapsUiMode.SYSTEM -> MODE_NIGHT_FOLLOW_SYSTEM
        }
        val changed = AppCompatDelegate.getDefaultNightMode() != nightMode
        AppCompatDelegate.setDefaultNightMode(nightMode)
        preferences.put(StringKey.GeneralDarkMode, appearance.mode.stringValue)
        return changed
    }

    /**
     * The stored value, upgraded in place if it predates the flattened picker.
     *
     * `skin` has meant three different things on a real device: a layout-skin class name (retired), a
     * palette id paired with a separate dark-mode setting (briefly), and now an appearance id. The
     * middle case is the one worth translating rather than resetting — a user on Midnight should stay
     * on Midnight — so a palette id is combined with the old `use_dark_mode` value to pick the
     * equivalent appearance.
     */
    private fun migratedAppearanceId(): String {
        val stored = try {
            preferences.get(StringKey.GeneralSkin)
        } catch (ignored: Exception) {
            ""
        }
        if (AapsAppearances.all.any { it.id == stored }) return stored

        val oldMode = try {
            AapsUiMode.fromString(preferences.get(StringKey.GeneralDarkMode))
        } catch (ignored: Exception) {
            AapsUiMode.DARK
        }
        // If the stored value names a skin, keep that skin and pick the entry matching the old mode —
        // a single-look skin has only one entry, so it lands there regardless. Otherwise (an empty
        // value, or a retired layout-skin class name) fall back on the old mode alone.
        val forStoredSkin = AapsAppearances.all.filter { it.skin.id == stored }
        val migrated = forStoredSkin.firstOrNull { it.mode == oldMode }
            ?: forStoredSkin.firstOrNull()
            ?: when (oldMode) {
                AapsUiMode.LIGHT  -> AapsAppearances.Light
                AapsUiMode.SYSTEM -> AapsAppearances.FollowSystem
                AapsUiMode.DARK   -> AapsAppearances.Dark
            }

        // Only WRITE for a value that is genuinely from the old scheme. An id that simply does not
        // resolve right now usually means an installed skin has not been read yet — storage was slow,
        // the load failed once, the app restarted oddly — and persisting the fallback would turn a
        // momentary hiccup into permanently losing the theme the user chose. Resolve for this run and
        // leave the preference alone, so the skin comes back when it loads.
        val isLegacyValue = stored.isBlank() || forStoredSkin.isNotEmpty() || stored.contains('.')
        if (isLegacyValue) preferences.put(StringKey.GeneralSkin, migrated.id)
        return migrated.id
    }

    override fun onStop() {
        disposable.dispose()
    }
}
