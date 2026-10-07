package app.aaps.configuration.maintenance

import android.content.ContentResolver
import android.content.Context
import androidx.documentfile.provider.DocumentFile
import app.aaps.configuration.maintenance.formats.SingleStringStorage
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.plugins.configuration.maintenance.ImportExportPrefsImpl
import app.aaps.plugins.configuration.maintenance.SettingsImport
import app.aaps.plugins.configuration.maintenance.cloud.CloudStorageManager
import app.aaps.plugins.configuration.maintenance.cloud.ExportOptionsDialog
import app.aaps.plugins.configuration.maintenance.formats.EncryptedPrefsFormat
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SettingsExportTest : TestBase() {

    @Test
    fun cloudOnlyEncryptionFailureReportsFailureBeforeStartingUpload() {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        val rh = mock<ResourceHelper>()
        whenever(rh.gs(any())).thenReturn("synthetic translation")
        val preferences = mock<Preferences>()
        whenever(preferences.get(StringKey.GeneralPatientName)).thenReturn("synthetic patient")
        val config = mock<Config>()
        whenever(config.VERSION_NAME).thenReturn("test")
        whenever(config.FLAVOR).thenReturn("full")
        whenever(config.currentDeviceModelString).thenReturn("synthetic device")
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.toISOString(any())).thenReturn("2026-01-01T00:00:00Z")
        val crypto = mock<CryptoUtil>()
        whenever(crypto.mineSalt()).thenReturn(ByteArray(32))
        whenever(crypto.encrypt(any(), any(), any())).thenReturn(null)
        val storage = spy(SingleStringStorage("existing encrypted backup"))
        val format = EncryptedPrefsFormat(rh, crypto, storage, context).apply { secureEncrypt = mock() }
        val settingsImport = mock<SettingsImport>()
        whenever(settingsImport.exportValues()).thenReturn(mapOf("exportable_secret" to "synthetic secret"))
        val file = mock<DocumentFile>()
        val tempDir = mock<DocumentFile>()
        whenever(tempDir.createFile(any(), any())).thenReturn(file)
        val files = mock<FileListProvider>()
        whenever(files.ensureTempDirExists()).thenReturn(tempDir)
        val options = mock<ExportOptionsDialog>()
        whenever(options.isSettingsLocalEnabled()).thenReturn(false)
        whenever(options.isSettingsCloudEnabled()).thenReturn(true)
        val cloudManager = mock<CloudStorageManager>()
        whenever(cloudManager.isCloudStorageActive()).thenReturn(true)
        val exporter = ImportExportPrefsImpl(
            aapsLogger, rh, preferences, config, mock(), rxBus, mock(), mock(), format, files,
            dateUtil, mock(), context, mock(), mock(), mock(), cloudManager, options, mock(), settingsImport
        )

        assertThat(exporter.exportSharedPreferencesNonInteractive(context, "synthetic password")).isFalse()

        verify(storage, never()).putFileContents(any<ContentResolver>(), any<DocumentFile>(), any())
        verify(cloudManager, never()).getActiveProvider()
        verify(file).delete()
    }
}
