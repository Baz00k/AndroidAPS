package app.aaps.plugins.constraints.signatureVerifier

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.AssetManager
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.plugins.constraints.ConstraintsCheckerImpl
import app.aaps.plugins.constraints.R
import app.aaps.plugins.constraints.signatureVerifier.keys.SignatureVerifierLongKey
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertFailsWith

@Suppress("DEPRECATION")
class SignatureVerifierCodecTest : TestBase() {

    @TempDir lateinit var directory: File
    private val context = mock<Context>()
    private val packageManager = mock<PackageManager>()
    private val assets = mock<AssetManager>()
    private val rh = mock<ResourceHelper>()
    private val preferences = mock<Preferences>()
    private val ui = mock<UiInteraction>()
    private lateinit var plugin: SignatureVerifierPlugin
    private lateinit var cache: File
    // Independently frozen SHA-256 of UTF-8 "synthetic signing certificate".
    private val fingerprintHex = "A5:CC:A7:21:BB:3D:DB:2A:3C:55:67:E0:01:1B:A6:2E:FD:62:10:D8:FE:0E:80:57:E3:75:C8:E1:9A:B9:89:39"
    private val fingerprint = fingerprintHex.split(":").map { it.toInt(16).toByte() }.toByteArray()

    @BeforeEach
    fun setUp() {
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("synthetic.app")
        whenever(context.assets).thenReturn(assets)
        val signature = mock<Signature>()
        whenever(signature.toByteArray()).thenReturn("synthetic signing certificate".toByteArray())
        whenever(packageManager.getPackageInfo("synthetic.app", PackageManager.GET_SIGNATURES)).thenReturn(PackageInfo().apply { signatures = arrayOf(signature) })
        whenever(preferences.get(SignatureVerifierLongKey.LastRevokedCertCheck)).thenReturn(System.currentTimeMillis())
        whenever(rh.gs(R.string.running_invalid_version)).thenReturn("invalid version")
        plugin = SignatureVerifierPlugin(aapsLogger, rh, preferences, context, ui)
        cache = File(directory, "revoked_certs.txt")
        setField("revokedCertsFile", cache)
    }

    // These parser expectations were exercised against Spongy Castle 1.58.0.0.
    // Only a '#' in column zero starts a comment; blank lines are empty byte arrays.
    @Test
    fun parsesCaseSeparatorsCrlfBlankLinesAndColumnZeroComments() {
        val parsed = parse("# comment\r\nAB:cd ef\r\n a\tb : C\td \r\n\r\n")
        assertThat(parsed.map { it.toList() }).containsExactly(
            listOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte()),
            listOf(0xab.toByte(), 0xcd.toByte()), emptyList<Byte>(), emptyList<Byte>()
        ).inOrder()
        assertThat(parse("").single()).isEmpty()
    }

    @Test
    fun rejectsInvalidDigitsOddLengthsUnsupportedWhitespaceAndIndentedOrInlineComments() {
        for (line in listOf("0g", "zz", "abc", "ab:c", "ab\u000ccd", "ab\u00a0cd", "ＡＢ", " # comment", "ab # comment")) {
            assertFailsWith<InvocationTargetException> { parse("ab\n$line\ncd") }
        }
    }

    @Test
    fun matchingCachedFingerprintInhibitsLoopAndNotifies() {
        cache.writeText("# synthetic revoked certificate\r\n$fingerprintHex\r\n")
        invoke("loadLocalRevokedCerts")
        assertThat(loopConstraint().value()).isFalse()
        verify(ui).addNotification(Notification.INVALID_VERSION, "invalid version", Notification.URGENT)
    }

    @Test
    fun nonmatchingAssetFingerprintDoesNotInhibitLoopOrRelaxAnotherConstraint() {
        whenever(assets.open("revoked_certs.txt")).thenReturn(("00:".repeat(31) + "00\n").byteInputStream())
        invoke("loadLocalRevokedCerts")
        assertThat(loopConstraint().value()).isTrue()
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(false, aapsLogger)).value()).isFalse()
        verify(ui, never()).addNotification(any(), any(), any())
    }

    @Test
    fun failedCachedListCannotInstallPartiallyParsedReplacement() {
        cache.writeText(fingerprintHex)
        invoke("loadLocalRevokedCerts")
        cache.writeText("00\nnot-hex\n$fingerprintHex")
        assertFailsWith<InvocationTargetException> { invoke("loadLocalRevokedCerts") }
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
        assertThat(installed().single().contentEquals(fingerprint)).isTrue()
    }

    @Test
    fun downloadedListIsParsedAndFailedDownloadCannotInstallPartialReplacement() {
        val download = File(directory, "download.txt")
        setField("REVOKED_CERTS_URL", download.toURI().toURL().toString())
        download.writeText(fingerprintHex)
        invoke("downloadAndSaveRevokedCerts")
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
        download.writeText("00\nabc\n$fingerprintHex")
        assertFailsWith<InvocationTargetException> { invoke("downloadAndSaveRevokedCerts") }
        assertThat(installed().single().contentEquals(fingerprint)).isTrue()
        assertThat(plugin.isLoopInvocationAllowed(ConstraintObject(true, aapsLogger)).value()).isFalse()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): List<ByteArray> =
        SignatureVerifierPlugin::class.java.getDeclaredMethod("parseRevokedCertsFile", String::class.java).apply { isAccessible = true }.invoke(plugin, text) as List<ByteArray>

    private fun invoke(name: String) = SignatureVerifierPlugin::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(plugin)

    private fun loopConstraint() = ConstraintsCheckerImpl(
        mock<ActivePlugin>().also {
            whenever(it.getSpecificPluginsListByInterface(PluginConstraints::class.java)).thenReturn(arrayListOf(plugin))
        },
        aapsLogger
    ).isLoopInvocationAllowed()

    private fun setField(name: String, value: Any) = SignatureVerifierPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(plugin, value)

    @Suppress("UNCHECKED_CAST")
    private fun installed(): List<ByteArray> = SignatureVerifierPlugin::class.java.getDeclaredField("revokedCerts").apply { isAccessible = true }.get(plugin) as List<ByteArray>
}
