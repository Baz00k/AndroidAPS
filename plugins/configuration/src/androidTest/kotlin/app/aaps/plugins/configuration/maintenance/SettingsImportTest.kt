package app.aaps.plugins.configuration.maintenance

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.PrefMetadata
import app.aaps.core.interfaces.maintenance.PrefsMetadataKey
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.implementation.profile.ProfileUtilImpl
import app.aaps.implementation.protection.SecureEncryptImpl
import app.aaps.implementation.protection.ExportPasswordDataStoreImpl
import app.aaps.implementation.sharedPreferences.PreferencesImpl
import app.aaps.implementation.storage.FileStorage
import app.aaps.implementation.utils.DecimalFormatterImpl
import app.aaps.plugins.configuration.maintenance.data.Prefs
import app.aaps.plugins.configuration.maintenance.data.PrefIOError
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import app.aaps.plugins.configuration.maintenance.formats.EncryptedPrefsFormat
import app.aaps.shared.impl.sharedPreferences.SPImpl
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsImportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferencesName = "settings-import-${UUID.randomUUID()}"
    private val password = "synthetic-settings-password"
    private lateinit var stored: SharedPreferences
    private lateinit var preferences: PreferencesImpl
    private lateinit var config: Config
    private lateinit var format: EncryptedPrefsFormat
    private lateinit var crypto: CryptoUtil
    private lateinit var secureEncrypt: SecureEncryptImpl
    private lateinit var settingsImport: SettingsImport
    private lateinit var profileUtil: ProfileUtilImpl
    private lateinit var file: File
    private var importCommitAttempts = 0
    private val blockedPreferenceFiles = mutableListOf<File>()

    private enum class PluginBooleanKey : BooleanComposedNonPreferenceKey {
        NestedWidgetFlag;

        override val key = "appwidget_nested_flag_"
        override val format = "%d"
        override val defaultValue = false
        override val exportable = true
    }

    private enum class PrivateKey(override val key: String) : BooleanNonPreferenceKey {
        LocalOnly("log_private");

        override val defaultValue = false
        override val exportable = false
    }

    private enum class PrivateWidgetKey : BooleanComposedNonPreferenceKey {
        Token;

        override val key = "appwidget_token_"
        override val format = "%d"
        override val defaultValue = false
        override val exportable = false
    }

    @Before
    fun setUp() {
        stored = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val delegate = SPImpl(stored, context)
        val sp = object : SP by delegate {
            override fun commit(block: SP.Editor.() -> Unit): Boolean {
                importCommitAttempts++
                return delegate.commit(block)
            }
        }
        val rh = mock<ResourceHelper>()
        whenever(rh.gs(any())).thenAnswer { context.getString(it.arguments[0] as Int) }
        whenever(rh.gs(any(), any<String>())).thenAnswer { context.getString(it.arguments[0] as Int, it.arguments[1]) }
        config = mock()
        preferences = PreferencesImpl(sp, Lazy { profileUtil }, Lazy { mock() }, Lazy { mock() }, mock(), config, mock())
        preferences.registerPreferences(PrivateKey::class.java)
        profileUtil = ProfileUtilImpl(preferences, DecimalFormatterImpl(rh))
        val logger = mock<AAPSLogger>()
        crypto = CryptoUtil(logger)
        format = EncryptedPrefsFormat(rh, crypto, FileStorage(), context)
        secureEncrypt = SecureEncryptImpl(logger, crypto)
        format.secureEncrypt = secureEncrypt
        val fileList = mock<FileListProvider>()
        whenever(fileList.checkMetadata(any())).thenAnswer { it.arguments[0] }
        settingsImport = SettingsImport(sp, preferences, format, fileList, config, rh, logger)
        file = File(context.cacheDir, "$preferencesName.json")
    }

    @After
    fun tearDown() {
        // Remove only the UUID-named paths created by the disk-failure tests.
        blockedPreferenceFiles.forEach { blocked ->
            File(blocked, "blocker").delete()
            blocked.delete()
        }
        context.deleteSharedPreferences(preferencesName)
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(preferencesName) }
    }

    @Test
    fun frozenMgdlBackupRestoresUnitsAndTypedSafetyLimits() {
        seedExistingSettings()
        preferences.put(StringKey.GeneralUnits, "mmol")
        preferences.put(UnitDoubleKey.OverviewHighMark, 10.0)
        importFixture("settings-mgdl.json")

        assertThat(stored.all).containsEntry(StringKey.GeneralUnits.key, "mg/dl")
        assertThat(stored.all).containsEntry(UnitDoubleKey.OverviewHighMark.key, "180")
        assertThat(profileUtil.units).isEqualTo(GlucoseUnit.MGDL)
        assertThat(preferences.get(UnitDoubleKey.OverviewLowMark)).isWithin(0.000001).of(70.0)
        assertThat(preferences.get(UnitDoubleKey.OverviewHighMark)).isWithin(0.000001).of(180.0)
        assertThat(preferences.get(UnitDoubleKey.ApsLgsThreshold)).isWithin(0.000001).of(70.0)
        assertThat(preferences.get(DoubleKey.SafetyMaxBolus)).isWithin(0.000001).of(2.5)
        assertThat(preferences.get(IntKey.SafetyMaxCarbs)).isEqualTo(60)
        assertThat(preferences.get(StringKey.SafetyAge)).isEqualTo("child")
        assertThat(preferences.get(BooleanKey.GeneralSimpleMode)).isFalse()
    }

    @Test
    fun frozenMmolBackupReadsLocalAndPreviouslyMgdlValuesInMmol() {
        importFixture("settings-mmol.json")

        assertThat(profileUtil.units).isEqualTo(GlucoseUnit.MMOL)
        assertThat(preferences.get(UnitDoubleKey.OverviewLowMark)).isWithin(0.000001).of(3.9)
        assertThat(preferences.get(UnitDoubleKey.OverviewHighMark)).isWithin(0.000001).of(10.0)
        assertThat(preferences.get(UnitDoubleKey.ApsLgsThreshold)).isWithin(0.000001).of(3.8)
        // The backup retains 99 mg/dL after a units switch: 99 / 18 = 5.5 mmol/L.
        assertThat(preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)).isWithin(0.000001).of(5.5)
    }

    @Test
    fun cachedPasswordExportOverwritesAndRoundTripsRegisteredTypes() {
        preferences.put(BooleanKey.GeneralSimpleMode, false)
        preferences.put(BooleanComposedKey.Log, "CORE", value = true)
        preferences.put(DoubleKey.SafetyMaxBolus, 2.5)
        preferences.put(IntKey.SafetyMaxCarbs, 60)
        preferences.put(LongNonKey.LocalProfileLastChange, 1_700_000_000_123L)
        stored.edit().putString("unknown_source_key", "not exported").putBoolean(PrivateKey.LocalOnly.key, true).commit()
        val wrappedPassword = secureEncrypt.encrypt(password, preferencesName)
        assertThat(secureEncrypt.isValidDataString(wrappedPassword)).isTrue()
        encrypt(mapOf(StringKey.GeneralPatientName.key to "previous synthetic backup".repeat(100)), wrappedPassword)

        for (name in listOf("true", "false")) {
            preferences.put(StringKey.GeneralPatientName, name)
            val values = settingsImport.exportValues()
            assertThat(values).doesNotContainKey("unknown_source_key")
            assertThat(values).doesNotContainKey(PrivateKey.LocalOnly.key)
            val checked = settingsImport.check(encrypt(values, wrappedPassword), password)
            assertWithMessage(checked.prefs.toString()).that(checked.importOk).isTrue()
            settingsImport.apply(checked)

            assertThat(preferences.get(StringKey.GeneralPatientName)).isEqualTo(name)
            assertThat(stored.all[StringKey.GeneralPatientName.key]).isInstanceOf(String::class.java)
            assertThat(preferences.get(BooleanComposedKey.Log, "CORE")).isTrue()
            assertThat(preferences.get(DoubleKey.SafetyMaxBolus)).isWithin(0.000001).of(2.5)
            assertThat(preferences.get(IntKey.SafetyMaxCarbs)).isEqualTo(60)
            assertThat(preferences.get(LongNonKey.LocalProfileLastChange)).isEqualTo(1_700_000_000_123L)
        }
    }

    @Test
    fun wrongPasswordCannotApplyOrChangeExistingSettings() {
        assertRejected(fixture("settings-mgdl.json"), "wrong-password")
    }

    @Test
    fun tamperedCiphertextCannotApplyOrChangeExistingSettings() {
        val content = JSONObject(fixture("settings-mgdl.json"))
        val encrypted = content.getString("content")
        content.put("content", encrypted.replaceRange(20, 21, if (encrypted[20] == 'A') "B" else "A"))
        // The public file hash remains valid: rejection must come from encrypted-content authentication.
        content.getJSONObject("security").put("file_hash", "--to-be-calculated--")
        val unsigned = content.toString()
        val hash = crypto.hmac256(unsigned, "if you remove/change this, please make sure you know the consequences!")
        val tampered = unsigned.replace("--to-be-calculated--", hash)
        val loaded = format.loadPreferences(tampered, password)
        assertThat(loaded.values).isEmpty()
        assertThat(loaded.metadata[PrefsMetadataKeyImpl.ENCRYPTION]?.info)
            .doesNotContain(context.getString(app.aaps.plugins.configuration.R.string.prefdecrypt_issue_modified))
        assertRejected(tampered)
    }

    @Test
    fun modifiedMetadataCannotApplyEvenWhenContentDecrypts() {
        val content = fixture("settings-mgdl.json").replace("Synthetic fixture", "Modified metadata")
        assertThat(format.loadPreferences(content, password).values).isNotEmpty()
        assertRejected(content)
    }

    @Test
    fun malformedFileIsRejectedWithoutChangingSettings() {
        assertRejected(fixture("settings-mgdl.json").take(80))
    }

    @Test
    fun malformedSaltIsRejectedWithoutChangingSettings() {
        val content = JSONObject(fixture("settings-mgdl.json"))
        content.getJSONObject("security").put("salt", "abc")
        assertRejected(content.toString())
    }

    @Test
    fun emptyBackupCannotClearExistingSettings() {
        assertRejected(encrypt(emptyMap()))
    }

    @Test
    fun replacementResetsOmittedSafetySettingsToDeclaredDefaults() {
        seedExistingSettings()
        val checked = settingsImport.check(encrypt(mapOf(StringKey.GeneralPatientName.key to "replacement")), password)
        assertThat(settingsImport.apply(checked)).isTrue()

        assertThat(preferences.get(StringKey.GeneralPatientName)).isEqualTo("replacement")
        assertThat(stored.contains(DoubleKey.SafetyMaxBolus.key)).isFalse()
        assertThat(stored.contains(StringKey.SafetyAge.key)).isFalse()
        assertThat(preferences.get(DoubleKey.SafetyMaxBolus)).isWithin(0.000001).of(3.0)
        assertThat(preferences.get(StringKey.SafetyAge)).isEqualTo("adult")
        assertThat(preferences.get(BooleanKey.GeneralSimpleMode)).isTrue()
    }

    @Test
    fun failedImportExitsWithoutSuccessHooksHousekeepingOrRetry() {
        val checked = prepareFailedWrite()
        val activePlugin = mock<ActivePlugin>()
        val configBuilder = mock<ConfigBuilder>()
        val rxBus = mock<RxBus>()
        val activity = mock<FragmentActivity>()
        val rh = mock<ResourceHelper>()
        whenever(rh.gs(any())).thenAnswer { context.getString(it.arguments[0] as Int) }
        val importer = ImportExportPrefsImpl(
            aapsLogger = mock(), rh = rh, preferences = preferences, config = config,
            persistenceLayer = mock(), rxBus = rxBus, passwordCheck = mock(), exportPasswordDataStore = mock(),
            encryptedPrefsFormat = format, prefFileList = mock(), dateUtil = mock(), uiInteraction = mock(),
            context = context, dataWorkerStorage = mock(), activePlugin = activePlugin, configBuilder = configBuilder,
            cloudStorageManager = mock(), exportOptionsDialog = mock(), importSourceDialog = mock(), settingsImport = settingsImport
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            importer.applyImportedSettings(activity, checked)
        }

        inOrder(activePlugin, activity, configBuilder) {
            verify(activePlugin).beforeImport()
            verify(activity).finish()
            verify(configBuilder).exitApp("Import persistence failure", Sources.Maintenance, false)
        }
        verify(activePlugin, never()).afterImport()
        verify(rh, never()).gs(app.aaps.plugins.configuration.R.string.setting_imported)
        verify(rh).gs(app.aaps.plugins.configuration.R.string.preferences_import_persistence_failed)
        verifyNoInteractions(rxBus)
        assertThat(stored.contains(BooleanKey.GeneralSetupWizardProcessed.key)).isFalse()
        assertUnpersistedReplacementIsActive()
        assertThat(importCommitAttempts).isEqualTo(1)
    }

    @Test
    fun unknownAndNonExportableKeysAreNotRestored() {
        val checked = settingsImport.check(encrypt(mapOf(
            StringKey.GeneralPatientName.key to "known",
            "unknown_import_key" to "true",
            PrivateKey.LocalOnly.key to "true"
        )), password)
        settingsImport.apply(checked)

        assertThat(preferences.get(StringKey.GeneralPatientName)).isEqualTo("known")
        assertThat(stored.all).doesNotContainKey("unknown_import_key")
        assertThat(stored.all).doesNotContainKey(PrivateKey.LocalOnly.key)
    }

    @Test
    fun unknownOnlyBackupCannotClearExistingSettings() {
        assertRejected(encrypt(mapOf("unknown_import_key" to "true")))
    }

    @Test
    fun invalidBooleanCannotBeAppliedEvenInEngineeringMode() {
        whenever(config.isEngineeringMode()).thenReturn(true)
        assertRejected(encrypt(mapOf(BooleanKey.GeneralSimpleMode.key to "not-a-boolean")))
    }

    @Test
    fun missingKeystoreKeyCannotOverwriteAnExistingBackup() {
        val existing = encrypt(mapOf(StringKey.GeneralPatientName.key to "previous backup"))
        val wrappedPassword = secureEncrypt.encrypt(password, preferencesName)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(preferencesName) }

        assertThrows(PrefIOError::class.java) {
            encrypt(mapOf(StringKey.GeneralPatientName.key to "new backup"), wrappedPassword)
        }
        assertThat(file.readText()).isEqualTo(existing)
    }

    @Test
    fun lostCachedPasswordKeyClearsCacheAndAllowsPasswordRenewal() {
        preferences.put(BooleanKey.MaintenanceEnableExportSettingsAutomation, true)
        val cache = ExportPasswordDataStoreImpl(mock(), preferences, config).apply {
            dateUtil = mock<DateUtil>().also { whenever(it.now()).thenReturn(1_800_000_000_000L) }
            secureEncrypt = this@SettingsImportTest.secureEncrypt
        }
        val alias = ExportPasswordDataStoreImpl.KEYSTORE_ALIAS
        val keyName = ExportPasswordDataStoreImpl.PASSWORD_PREFERENCE_NAME
        try {
            val wrapped = cache.putPasswordToDataStore(context, password)
            assertThat(cache.getPasswordFromDataStore(context)).isEqualTo(Triple(wrapped, false, false))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }

            assertThat(cache.getPasswordFromDataStore(context)).isEqualTo(Triple("", true, true))
            val persisted = runBlocking {
                context.preferencesDataStoreFile(ExportPasswordDataStoreImpl.DATASTORE_NAME).source().buffer().use {
                    PreferencesSerializer.readFrom(it)
                }
            }
            assertThat(persisted[stringPreferencesKey("$keyName.key")]).isEmpty()
            assertThat(persisted[stringPreferencesKey("$keyName.ts")]).isEqualTo("0")

            val renewed = cache.putPasswordToDataStore(context, password)
            assertThat(cache.getPasswordFromDataStore(context)).isEqualTo(Triple(renewed, false, false))
            assertThat(secureEncrypt.decrypt(renewed)).isEqualTo(password)
        } finally {
            cache.clearPasswordDataStore(context)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test
    fun nonExportableNestedPrefixCannotFallBackToAnExportableParent() {
        preferences.registerPreferences(PrivateWidgetKey::class.java)
        val key = PrivateWidgetKey.Token.composeKey(7)
        preferences.put(PrivateWidgetKey.Token, 7, value = true)
        assertThat(settingsImport.exportValues()).doesNotContainKey(key)
        assertRejected(encrypt(mapOf(key to "true")))
    }

    @Test
    fun invalidBooleanSummaryListsEveryKeyAndPreservesTheFormatRow() {
        val content = encrypt(mapOf(
            BooleanKey.GeneralSimpleMode.key to "invalid",
            BooleanComposedKey.Log.composeKey("CORE") to "invalid"
        ))
        val checked = settingsImport.check(content, password)
        assertThat(checked.prefs.metadata[PrefsMetadataKeyImpl.FILE_FORMAT]?.status).isEqualTo(PrefsStatusImpl.OK)
        val error = checked.prefs.metadata[PrefsMetadataKeyImpl.SETTINGS]
        assertThat(error?.status).isEqualTo(PrefsStatusImpl.ERROR)
        assertThat(error?.value).contains(BooleanKey.GeneralSimpleMode.key)
        assertThat(error?.value).contains(BooleanComposedKey.Log.composeKey("CORE"))
        assertThat(checked.importPossible).isFalse()
    }

    @Test
    fun fileMetadataCannotSupplyLocallyComputedValidationRows() {
        val content = JSONObject(fixture("settings-mgdl.json"))
        content.getJSONObject("metadata").put("settings", "File claims settings are valid").put("encryption", "File claims encryption is valid")

        val metadata = format.loadMetadata(content.toString())
        assertThat(metadata).doesNotContainKey(PrefsMetadataKeyImpl.SETTINGS)
        assertThat(metadata).doesNotContainKey(PrefsMetadataKeyImpl.ENCRYPTION)
    }

    @Test
    fun longestRegisteredPrefixDeterminesTheImportedType() {
        preferences.registerPreferences(PluginBooleanKey::class.java)
        val key = PluginBooleanKey.NestedWidgetFlag.composeKey(7)
        val checked = settingsImport.check(encrypt(mapOf(key to "true")), password)
        settingsImport.apply(checked)

        assertThat(preferences.get(PluginBooleanKey.NestedWidgetFlag, 7)).isTrue()
        assertThat(stored.all[key]).isEqualTo(true)
    }

    private fun seedExistingSettings() {
        preferences.put(BooleanKey.GeneralSimpleMode, false)
        preferences.put(StringKey.GeneralPatientName, "unchanged")
        preferences.put(StringKey.SafetyAge, "child")
        preferences.put(DoubleKey.SafetyMaxBolus, 1.0)
        preferences.put(IntKey.SafetyMaxCarbs, 20)
        preferences.put(LongNonKey.LocalProfileLastChange, 1_600_000_000_321L)
    }

    private fun prepareFailedWrite(): SettingsImport.Check {
        seedExistingSettings()
        assertThat(stored.edit().commit()).isTrue() // Flush outstanding apply() writes before blocking disk access.
        val checked = settingsImport.check(encrypt(mapOf(StringKey.GeneralPatientName.key to "unpersisted replacement")), password)
        assertThat(checked.importPossible).isTrue()
        // Real Android commitToMemory still runs, but FileOutputStream cannot write
        // to the non-empty directories at the XML and backup paths.
        for (suffix in listOf(".xml", ".xml.bak")) {
            val blocked = File(context.applicationInfo.dataDir, "shared_prefs/$preferencesName$suffix")
            if (blocked.exists()) assertThat(blocked.delete()).isTrue()
            assertThat(blocked.mkdir()).isTrue()
            blockedPreferenceFiles.add(blocked)
            File(blocked, "blocker").writeText("synthetic write failure")
        }
        return checked
    }

    private fun assertUnpersistedReplacementIsActive() {
        assertThat(preferences.get(StringKey.GeneralPatientName)).isEqualTo("unpersisted replacement")
        assertThat(stored.contains(DoubleKey.SafetyMaxBolus.key)).isFalse()
        assertThat(preferences.get(DoubleKey.SafetyMaxBolus)).isWithin(0.000001).of(3.0)
        assertThat(preferences.get(StringKey.SafetyAge)).isEqualTo("adult")
    }

    private fun assertRejected(content: String, importPassword: String = password) {
        seedExistingSettings()
        val before = stored.all.toMap()
        val checked = settingsImport.check(content, importPassword)

        assertThat(checked.importOk).isFalse()
        assertThat(checked.importPossible).isFalse()
        assertThat(checked.prefs.metadata.values.any { it.status == PrefsStatusImpl.ERROR }).isTrue()
        assertThrows(IllegalArgumentException::class.java) { settingsImport.apply(checked) }
        assertThat(stored.all).containsExactlyEntriesIn(before)
    }

    private fun importFixture(name: String) {
        val checked = settingsImport.check(fixture(name), password)
        assertWithMessage(checked.prefs.toString()).that(checked.importOk).isTrue()
        assertThat(checked.importPossible).isTrue()
        assertThat(settingsImport.apply(checked)).isTrue()
    }

    // Frozen synthetic v1 backups guard compatibility independently of the current exporter.
    private fun fixture(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }

    private fun encrypt(values: Map<String, String>, exportPassword: String = password): String {
        val metadata = mapOf<PrefsMetadataKey, PrefMetadata>(PrefsMetadataKeyImpl.ENCRYPTION to PrefMetadata("Enabled", PrefsStatusImpl.OK))
        format.savePreferences(DocumentFile.fromFile(file), Prefs(values, metadata), exportPassword)
        return file.readText()
    }
}
