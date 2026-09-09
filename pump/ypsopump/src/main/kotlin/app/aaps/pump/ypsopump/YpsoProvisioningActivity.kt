package app.aaps.pump.ypsopump

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.core.interfaces.queue.CommandQueue
import java.time.ZoneId
import java.time.Duration
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class YpsoProvisioningActivity : TranslatedDaggerAppCompatActivity() {
    @Inject lateinit var provisioning: YpsoProvisioningService
    @Inject lateinit var commandQueue: CommandQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.ypsopump_connection_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { AapsTheme { ProvisioningScreen(provisioning, ::openDocument) } }
        })
    }

    private var selectedDocument by mutableStateOf<YpsoSessionDocument?>(null)
    private var importError by mutableStateOf<String?>(null)

    private fun openDocument(uri: Uri?) {
        if (uri == null) return
        selectedDocument?.sharedKey?.fill(0)
        selectedDocument = null
        runCatching {
            contentResolver.openInputStream(uri)?.use(provisioning::reviewDocument)
                ?: throw IllegalArgumentException()
        }.onSuccess { selectedDocument = it; importError = null }
            .onFailure { selectedDocument = null; importError = getString(R.string.ypsopump_import_invalid) }
    }

    override fun onDestroy() {
        selectedDocument?.sharedKey?.fill(0)
        selectedDocument = null
        super.onDestroy()
    }

    @Composable
    private fun ProvisioningScreen(service: YpsoProvisioningService, onPicked: (Uri?) -> Unit) {
        var installed by remember { mutableStateOf(service.installed()) }
        var verifying by remember { mutableStateOf(false) }
        var serial by remember { mutableStateOf(installed?.serial.orEmpty()) }
        var mac by remember { mutableStateOf(installed?.mac.orEmpty()) }
        var key by remember { mutableStateOf("") }
        var showKey by remember { mutableStateOf(false) }
        var serialError by remember { mutableStateOf<String?>(null) }
        var macError by remember { mutableStateOf<String?>(null) }
        var keyError by remember { mutableStateOf<String?>(null) }
        var generalError by remember { mutableStateOf<String?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), onPicked)
        val selected = selectedDocument
        LaunchedEffect(verifying) {
            if (!verifying) return@LaunchedEffect
            repeat(40) {
                kotlinx.coroutines.delay(1_000)
                installed = service.installed()
                if (installed?.verifiedAt != null) {
                    verifying = false
                    return@LaunchedEffect
                }
            }
            verifying = false
            installed = service.installed()
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(getString(R.string.ypsopump_setup_explanation), style = MaterialTheme.typography.bodyMedium)
            installed?.let {
                Text(getString(R.string.ypsopump_installed_summary, it.serial, it.mac, it.keyFingerprint))
                Text(
                    if (verifying) getString(R.string.ypsopump_verifying)
                    else it.verifiedAt?.let { verified -> getString(R.string.ypsopump_verified_at, format(verified)) }
                        ?: getString(R.string.ypsopump_configured_unverified)
                )
                Text(
                    it.createdAt?.let { created -> getString(R.string.ypsopump_key_age, age(created)) }
                        ?: getString(R.string.ypsopump_key_age_unknown)
                )
                it.importedAt?.let { imported -> Text(getString(R.string.ypsopump_imported_at, format(imported))) }
                if (!verifying && it.availability.causes.any { cause -> cause != app.aaps.pump.ypsopump.crypto.PumpSession.AvailabilityCause.COUNTER_UNCERTAIN }) {
                    Text(
                        getString(R.string.ypsopump_verification_failed, it.availability.causes.localizedSummary { cause -> getString(cause) }),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedTextField(
                serial, { serial = it; serialError = null }, label = { Text(getString(R.string.ypsopump_real_serial)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = serialError != null,
                supportingText = serialError?.let { value -> { Text(value) } }
            )
            OutlinedTextField(
                mac, { mac = it; macError = null }, label = { Text(getString(R.string.ypsopump_ble_mac)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = macError != null,
                supportingText = macError?.let { value -> { Text(value) } }
            )
            OutlinedTextField(
                key,
                { key = it; keyError = null },
                label = { Text(if (installed == null) getString(R.string.ypsopump_session_key) else getString(R.string.ypsopump_replacement_key_optional)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                isError = keyError != null,
                supportingText = keyError?.let { value -> { Text(value) } }
            )
            Row { Checkbox(showKey, { showKey = it }); Text(getString(R.string.ypsopump_show_key), modifier = Modifier.padding(top = 12.dp)) }
            Button(onClick = {
                runCatching { service.installManual(YpsoProvisioningService.ManualDraft(serial, mac, key.takeIf(String::isNotBlank))) }
                    .onSuccess {
                        serialError = null; macError = null; keyError = null; generalError = null
                        key = ""; installed = service.installed(); verifying = true; verify()
                    }
                    .onFailure {
                        when (it) {
                            is YpsoProvisioningService.ManualValidationException -> when (it.field) {
                                YpsoProvisioningService.ManualField.SERIAL -> serialError = getString(R.string.ypsopump_invalid_serial)
                                YpsoProvisioningService.ManualField.MAC -> macError = getString(R.string.ypsopump_invalid_mac)
                                YpsoProvisioningService.ManualField.KEY -> keyError = getString(R.string.ypsopump_invalid_key)
                            }
                            else -> generalError = getString(R.string.ypsopump_save_failed)
                        }
                    }
            }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.ypsopump_save_verify)) }
            Text(getString(R.string.ypsopump_import_heading), style = MaterialTheme.typography.titleMedium)
            Text(getString(R.string.ypsopump_private_transfer_warning))
            Button(onClick = { picker.launch(arrayOf("application/json", "text/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) {
                Text(getString(R.string.ypsopump_import_session_file))
            }
            selected?.let { document ->
                val source = document.source.entries.joinToString { getString(R.string.ypsopump_source_entry, it.key, it.value) }
                Text(getString(R.string.ypsopump_import_review, document.serial, document.mac, document.fingerprint, source))
                Text(getString(R.string.ypsopump_key_created_at, format(document.createdAt)))
                Text(getString(R.string.ypsopump_key_age, age(document.createdAt)))
                Button(onClick = {
                    runCatching { service.installDocument(document) }
                        .onSuccess { selectedDocument = null; generalError = null; installed = service.installed(); verifying = true; verify() }
                        .onFailure { selectedDocument = null; generalError = getString(R.string.ypsopump_save_failed) }
                }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.ypsopump_apply_import)) }
            }
            (generalError ?: importError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    private fun format(instant: java.time.Instant): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault()).format(instant)

    private fun age(instant: java.time.Instant): String {
        val days = Duration.between(instant, java.time.Instant.now()).toDays().coerceAtLeast(0)
        return resources.getQuantityString(R.plurals.ypsopump_key_age_days, days.toInt(), days)
    }

    private fun verify() {
        provisioning.requestVerificationAttempt()
        commandQueue.readStatus(getString(R.string.ypsopump_provisioning_verify_reason), null)
    }
}
