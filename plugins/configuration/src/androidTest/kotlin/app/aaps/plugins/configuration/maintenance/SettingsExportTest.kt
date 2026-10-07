package app.aaps.plugins.configuration.maintenance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.protection.ExportPasswordDataStore
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.crypto.CryptoUtil
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.implementation.protection.SecureEncryptImpl
import app.aaps.implementation.storage.FileStorage
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.cloud.CloudStorageManager
import app.aaps.plugins.configuration.maintenance.cloud.ExportOptionsDialog
import app.aaps.plugins.configuration.maintenance.cloud.ImportSourceDialog
import app.aaps.plugins.configuration.maintenance.formats.EncryptedPrefsFormat
import app.aaps.shared.impl.sharedPreferences.SPImpl
import com.google.common.truth.Truth.assertThat
import io.reactivex.rxjava3.core.Single
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.security.KeyStore
import java.util.UUID

class SettingsExportTestActivity : FragmentActivity()

@RunWith(AndroidJUnit4::class)
class SettingsExportTest {

    @Test
    fun manualCachedPasswordFailureShowsFailureAndPreservesExistingBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "settings-export-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$name.json")
        val logger = mock<AAPSLogger>()
        val crypto = CryptoUtil(logger)
        val secureEncrypt = SecureEncryptImpl(logger, crypto)
        val wrappedPassword = secureEncrypt.encrypt("synthetic password", name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        val rh = mock<ResourceHelper>()
        whenever(rh.gs(any())).thenAnswer { context.getString(it.arguments[0] as Int) }
        val preferences = mock<Preferences>()
        whenever(preferences.getIfExists(StringKey.ProtectionMasterPassword)).thenReturn("synthetic password hash")
        whenever(preferences.get(StringKey.GeneralPatientName)).thenReturn("synthetic patient")
        whenever(preferences.getIfExists(StringKey.AapsDirectoryUri)).thenReturn("synthetic directory")
        val config = mock<Config>()
        whenever(config.VERSION_NAME).thenReturn("test")
        whenever(config.FLAVOR).thenReturn("full")
        whenever(config.currentDeviceModelString).thenReturn("synthetic device")
        val dateUtil = mock<DateUtil>()
        whenever(dateUtil.toISOString(any())).thenReturn("2026-01-01T00:00:00Z")
        val cache = mock<ExportPasswordDataStore>()
        whenever(cache.getPasswordFromDataStore(any())).thenReturn(Triple(wrappedPassword, false, false))
        val fileList = mock<FileListProvider>()
        whenever(fileList.newPreferenceFile()).thenReturn(DocumentFile.fromFile(file))
        val persistence = mock<PersistenceLayer>()
        whenever(persistence.insertPumpTherapyEventIfNewByTimestamp(any(), any(), any(), any(), any(), any())).thenReturn(Single.never())
        val sp = SPImpl(context.getSharedPreferences(name, Context.MODE_PRIVATE), context)
        val cloud = CloudStorageManager(logger, sp, emptySet())
        val options = ExportOptionsDialog(logger, rh, sp, cloud)
        val format = EncryptedPrefsFormat(rh, crypto, FileStorage(), context).apply { this.secureEncrypt = secureEncrypt }
        val settingsImport = SettingsImport(sp, preferences, format, fileList, config, rh, logger)
        val exporter = ImportExportPrefsImpl(
            logger, rh, preferences, config, persistence, mock<RxBus>(), mock(), cache, format, fileList,
            dateUtil, mock(), context, DataWorkerStorage(context), mock(), mock(), cloud, options,
            ImportSourceDialog(logger, rh, options, cloud), settingsImport
        )
        file.writeText("existing encrypted backup")
        try {
            ActivityScenario.launch(SettingsExportTestActivity::class.java).use { scenario ->
                lateinit var decorView: View
                scenario.onActivity { activity ->
                    decorView = activity.window.decorView
                    val fragment = Fragment()
                    activity.supportFragmentManager.beginTransaction().add(fragment, "export").commitNow()
                    exporter.exportSharedPreferences(fragment)
                }
                onView(withText(R.string.exported_failed))
                    .inRoot(withDecorView(not(`is`(decorView))))
                    .check(matches(isDisplayed()))
                onView(withId(android.R.id.icon))
                    .inRoot(withDecorView(not(`is`(decorView))))
                    .check { view, error ->
                        if (error != null) throw error
                        val icon = view as ImageView
                        val expected = AppCompatResources.getDrawable(icon.context, app.aaps.core.ui.R.drawable.ic_toast_error)!!
                        assertThat(render(icon.drawable).sameAs(render(expected))).isTrue()
                    }
                assertThat(file.readText()).isEqualTo("existing encrypted backup")
            }
        } finally {
            file.delete()
            context.deleteSharedPreferences(name)
        }
    }

    private fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, it.width, it.height)
        drawable.draw(Canvas(it))
    }
}
