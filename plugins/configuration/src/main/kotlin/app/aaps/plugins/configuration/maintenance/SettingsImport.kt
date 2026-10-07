package app.aaps.plugins.configuration.maintenance

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.PrefMetadata
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.data.PrefFormatError
import app.aaps.plugins.configuration.maintenance.data.Prefs
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import app.aaps.plugins.configuration.maintenance.formats.EncryptedPrefsFormat
import dagger.Reusable
import javax.inject.Inject

@Reusable
class SettingsImport @Inject constructor(
    private val sp: SP,
    private val preferences: Preferences,
    private val format: EncryptedPrefsFormat,
    private val fileList: FileListProvider,
    private val config: Config,
    private val rh: ResourceHelper,
    private val logger: AAPSLogger
) {

    class Check internal constructor(
        val prefs: Prefs,
        val importOk: Boolean,
        val importPossible: Boolean,
        internal val values: Map<String, Any>
    )

    fun exportValues(): Map<String, String> =
        sp.getAll().filterKeys { preferences.exportableKey(it) != null }.mapValues { it.value.toString() }

    fun check(content: String, password: String): Check {
        val prefs = try {
            format.loadPreferences(content, password)
        } catch (_: PrefFormatError) {
            Prefs(emptyMap(), mapOf(PrefsMetadataKeyImpl.FILE_FORMAT to PrefMetadata(rh.gs(R.string.prefdecrypt_wrong_json), PrefsStatusImpl.ERROR)))
        }
        prefs.metadata = fileList.checkMetadata(prefs.metadata)
        val values = mutableMapOf<String, Any>()
        val invalidBooleans = mutableListOf<String>()
        for ((key, value) in prefs.values) {
            val registered = preferences.exportableKey(key)
            when {
                registered == null -> logger.warn(LTag.CORE, "Skipping unknown or non-exportable settings key: $key")
                registered is BooleanNonPreferenceKey || registered is BooleanComposedNonPreferenceKey -> {
                    val boolean = value.toBooleanStrictOrNull()
                    if (boolean == null) invalidBooleans.add(key) else values[key] = boolean
                }
                else -> values[key] = value
            }
        }
        val error = when {
            invalidBooleans.isNotEmpty() -> rh.gs(R.string.preferences_import_invalid_boolean, invalidBooleans.joinToString(", "))
            values.isEmpty() && prefs.metadata.values.none { it.status == PrefsStatusImpl.ERROR } -> rh.gs(R.string.preferences_import_no_settings)
            else -> null
        }
        if (error != null) {
            prefs.metadata = prefs.metadata + (PrefsMetadataKeyImpl.SETTINGS to PrefMetadata(error, PrefsStatusImpl.ERROR))
        }
        val importOk = values.isNotEmpty() && prefs.metadata.values.none { it.status == PrefsStatusImpl.ERROR }
        val importPossible = values.isNotEmpty() && invalidBooleans.isEmpty() && (importOk || config.isEngineeringMode())
        return Check(prefs, importOk, importPossible, values.toMap())
    }

    fun apply(check: Check): Boolean {
        require(check.importPossible) { "Settings import was rejected" }
        // One write prevents observers from seeing a cleared or partially restored preference set.
        return sp.commit {
            clear()
            for ((key, value) in check.values) {
                if (value is Boolean) putBoolean(key, value)
                else putString(key, value as String)
            }
        }
    }
}
