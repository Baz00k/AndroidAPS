package app.aaps.plugins.constraints.signatureVerifier

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.constraints.ConstraintsCheckerImpl
import app.aaps.plugins.constraints.R
import app.aaps.plugins.constraints.signatureVerifier.keys.SignatureVerifierLongKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertFailsWith

@Suppress("DEPRECATION")
class SignatureVerifierCodecTest : TestBase() {

    @TempDir lateinit var directory: File
    private val ui = mock<UiInteraction>()
    private lateinit var plugin: SignatureVerifierPlugin
    private lateinit var checker: ConstraintsCheckerImpl
    // Independently frozen SHA-256 of UTF-8 "synthetic signing certificate".
    private val fingerprint = "A5:CC:A7:21:BB:3D:DB:2A:3C:55:67:E0:01:1B:A6:2E:FD:62:10:D8:FE:0E:80:57:E3:75:C8:E1:9A:B9:89:39"

    @BeforeEach
    fun setUp() {
        val context = mock<Context>()
        val packageManager = mock<PackageManager>()
        val signature = mock<Signature>()
        whenever(signature.toByteArray()).thenReturn("synthetic signing certificate".toByteArray())
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("synthetic.app")
        whenever(packageManager.getPackageInfo("synthetic.app", PackageManager.GET_SIGNATURES)).thenReturn(PackageInfo().apply { signatures = arrayOf(signature) })
        val preferences = mock<Preferences>()
        whenever(preferences.get(SignatureVerifierLongKey.LastRevokedCertCheck)).thenReturn(System.currentTimeMillis())
        val rh = mock<ResourceHelper>()
        whenever(rh.gs(R.string.running_invalid_version)).thenReturn("invalid version")
        plugin = SignatureVerifierPlugin(aapsLogger, rh, preferences, context, ui)
        val active = mock<ActivePlugin>()
        whenever(active.getSpecificPluginsListByInterface(PluginConstraints::class.java)).thenReturn(arrayListOf(plugin))
        checker = ConstraintsCheckerImpl(active, aapsLogger)
    }

    @Test
    fun certificateParserAcceptsListSyntaxAndRejectsMalformedEntries() {
        // Characterized against Spongy Castle: column-zero comments, mixed case,
        // separators/ASCII whitespace, CRLF and blank lines (empty byte arrays).
        val parsed = invoke("parseRevokedCertsFile", "# comment\r\n aB:c\td \r\n\r\n") as List<*>
        assertThat(parsed.map { (it as ByteArray).toList() }).containsExactly(
            listOf(0xab.toByte(), 0xcd.toByte()), emptyList<Byte>(), emptyList<Byte>()
        ).inOrder()
        for (line in listOf("0g", "abc", "ab\u000ccd", "ab\u00a0cd", "ＡＢ", " # comment", "ab # comment"))
            assertFailsWith<InvocationTargetException> { invoke("parseRevokedCertsFile", "ab\n$line") }
    }

    @Test
    fun cachedAndDownloadedListsEnforceLoopConstraintWithoutPartialReplacement() {
        val cache = File(directory, "cache.txt")
        val download = File(directory, "download.txt")
        setField("revokedCertsFile", cache)
        setField("REVOKED_CERTS_URL", download.toURI().toURL().toString())
        cache.writeText("00")
        invoke("loadLocalRevokedCerts")
        assertThat(checker.isLoopInvocationAllowed().value()).isTrue()
        for ((source, loader) in listOf(cache to "loadLocalRevokedCerts", download to "downloadAndSaveRevokedCerts")) {
            source.writeText("# revoked\r\n$fingerprint\r\n")
            invoke(loader)
            assertThat(checker.isLoopInvocationAllowed().value()).isFalse()
            for (invalid in listOf("0g", "abc")) {
                source.writeText("00\n$invalid\n$fingerprint")
                assertFailsWith<InvocationTargetException> { invoke(loader) }
                assertThat(checker.isLoopInvocationAllowed().value()).isFalse()
            }
        }
        verify(ui, atLeastOnce()).addNotification(Notification.INVALID_VERSION, "invalid version", Notification.URGENT)
    }

    private fun invoke(name: String, text: String? = null): Any? = SignatureVerifierPlugin::class.java
        .getDeclaredMethod(name, *if (text == null) emptyArray() else arrayOf(String::class.java))
        .apply { isAccessible = true }.invoke(plugin, *if (text == null) emptyArray() else arrayOf(text))

    private fun setField(name: String, value: Any) = SignatureVerifierPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)
}
