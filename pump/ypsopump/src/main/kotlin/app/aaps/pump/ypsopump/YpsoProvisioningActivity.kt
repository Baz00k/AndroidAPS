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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.core.interfaces.queue.CommandQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.time.ZoneId
import java.time.Duration
import java.time.format.DateTimeFormatter
import javax.inject.Inject

internal enum class VerificationPresentation { IDLE, CHECKING, SUCCEEDED, FAILED, CANCELLED }

internal enum class ProvisioningFeedbackTone { NEUTRAL, SUCCESS, ERROR }

internal data class ProvisioningFeedback(@StringRes val message: Int, val tone: ProvisioningFeedbackTone, val wrapsAction: Boolean = false)

/** Copies the reviewed secret for an operation, then makes the screen's copy unusable. */
internal fun detachDocumentForInstallation(document: YpsoSessionDocument): YpsoSessionDocument =
    document.copy(sharedKey = document.sharedKey.copyOf()).also { document.sharedKey.fill(0) }

internal class VerificationStartException : IllegalStateException("Verification status read was not accepted")

/**
 * The durable candidate and its verification read are one non-cancellable transaction. This lets
 * a destroyed activity stop rendering without stranding a candidate between staging and enqueue.
 */
internal class ProvisioningVerificationStarter(
    private val service: YpsoProvisioningService,
    private val commandQueue: CommandQueue,
    private val verificationReason: String,
) {
    suspend fun installManual(draft: YpsoProvisioningService.ManualDraft): PumpSession.Installation = installThenStart {
        service.installManualAndStartVerification(draft) { commandQueue.readStatus(verificationReason, null) }
    }

    suspend fun installDocument(document: YpsoSessionDocument): PumpSession.Installation = installThenStart {
        try {
            service.installDocumentAndStartVerification(document) { commandQueue.readStatus(verificationReason, null) }
        } finally {
            document.sharedKey.fill(0)
        }
    }

    private suspend fun installThenStart(install: () -> PumpSession.Installation): PumpSession.Installation = withContext(NonCancellable + Dispatchers.IO) {
        try {
            install()
        } catch (error: Throwable) {
            throw error
        }
    }
}

internal fun verificationPresentation(
    state: YpsoProvisioningService.VerificationState?,
): VerificationPresentation = when (state?.status) {
    PumpSession.AttemptStatus.PENDING -> VerificationPresentation.CHECKING
    PumpSession.AttemptStatus.SUCCEEDED -> VerificationPresentation.SUCCEEDED
    PumpSession.AttemptStatus.FAILED -> VerificationPresentation.FAILED
    PumpSession.AttemptStatus.CANCELLED -> VerificationPresentation.CANCELLED
    null -> VerificationPresentation.IDLE
}

/** The setup screen's single feedback message, independent of device configuration or Compose. */
internal fun provisioningFeedback(
    verification: VerificationPresentation,
    presentation: PumpSetupPresentation,
): ProvisioningFeedback = when (verification) {
    VerificationPresentation.CHECKING -> ProvisioningFeedback(R.string.ypsopump_verifying, ProvisioningFeedbackTone.NEUTRAL)
    VerificationPresentation.FAILED -> ProvisioningFeedback(R.string.ypsopump_verification_failed_generic, ProvisioningFeedbackTone.ERROR)
    VerificationPresentation.CANCELLED -> ProvisioningFeedback(R.string.ypsopump_verification_cancelled, ProvisioningFeedbackTone.NEUTRAL)
    VerificationPresentation.SUCCEEDED, VerificationPresentation.IDLE -> when (presentation) {
        PumpSetupPresentation.SETUP_REQUIRED -> ProvisioningFeedback(R.string.ypsopump_cause_unconfigured, ProvisioningFeedbackTone.NEUTRAL)
        PumpSetupPresentation.DETAILS_NEED_VERIFICATION -> ProvisioningFeedback(R.string.ypsopump_configured_unverified, ProvisioningFeedbackTone.NEUTRAL)
        PumpSetupPresentation.READY -> ProvisioningFeedback(R.string.ypsopump_verified_at, ProvisioningFeedbackTone.SUCCESS)
        else -> ProvisioningFeedback(presentation.message, ProvisioningFeedbackTone.ERROR, wrapsAction = true)
    }
}

class YpsoProvisioningActivity : TranslatedDaggerAppCompatActivity() {
    @Inject lateinit var provisioning: YpsoProvisioningService
    @Inject lateinit var commandQueue: CommandQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.ypsopump_connection_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                AapsTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ) {
                        ProvisioningScreen(provisioning, importingDocument, ::openDocument)
                    }
                }
            }
        })
    }

    private var selectedDocument by mutableStateOf<YpsoSessionDocument?>(null)
    private var importError by mutableStateOf<String?>(null)
    private var importingDocument by mutableStateOf(false)

    private fun openDocument(uri: Uri?) {
        if (uri == null) return
        selectedDocument?.sharedKey?.fill(0)
        selectedDocument = null
        importingDocument = true
        lifecycleScope.launch {
            var reviewed: Result<YpsoSessionDocument>? = null
            var transferredToScreen = false
            try {
                reviewed = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use(provisioning::reviewDocument)
                        ?: throw IllegalArgumentException()
                    }
                }
                currentCoroutineContext().ensureActive()
                reviewed.onSuccess {
                    selectedDocument = it
                    transferredToScreen = true
                    importError = null
                }.onFailure { importError = getString(R.string.ypsopump_import_invalid) }
            } finally {
                if (!transferredToScreen) reviewed?.getOrNull()?.sharedKey?.fill(0)
                importingDocument = false
            }
        }
    }

    override fun onDestroy() {
        selectedDocument = null
        super.onDestroy()
    }

    @Composable
    private fun ProvisioningScreen(service: YpsoProvisioningService, importingDocument: Boolean, onPicked: (Uri?) -> Unit) {
        var installed by remember { mutableStateOf(service.installed()) }
        var pending by remember { mutableStateOf(service.pending()) }
        var verificationState by remember { mutableStateOf(service.verificationState()) }
        var installing by remember { mutableStateOf(false) }
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
        val verificationStarter = remember(service, commandQueue) {
            ProvisioningVerificationStarter(service, commandQueue, getString(R.string.ypsopump_provisioning_verify_reason))
        }
        val colors = AapsTheme.colors
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            focusedLabelColor = colors.textPrimary,
            unfocusedLabelColor = colors.textSecondary,
            focusedBorderColor = colors.accent,
            // A field boundary must remain perceptible on every supported light and dark skin.
            unfocusedBorderColor = colors.textSecondary,
            errorTextColor = colors.textPrimary,
            errorLabelColor = colors.textPrimary,
            errorBorderColor = MaterialTheme.colorScheme.error,
            errorSupportingTextColor = colors.textSecondary,
            focusedSupportingTextColor = colors.textSecondary,
            unfocusedSupportingTextColor = colors.textSecondary
        )
        val serialFocus = remember { FocusRequester() }
        val macFocus = remember { FocusRequester() }
        val keyFocus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val verification = verificationPresentation(verificationState)
        LaunchedEffect(verificationState?.attemptId, verificationState?.status) {
            val attemptId = verificationState?.attemptId ?: return@LaunchedEffect
            if (verification != VerificationPresentation.CHECKING) return@LaunchedEffect
            while (true) {
                delay(250)
                val updated = service.verificationState()
                verificationState = updated
                installed = service.installed()
                pending = service.pending()
                if (updated?.attemptId != attemptId || verificationPresentation(updated) != VerificationPresentation.CHECKING) return@LaunchedEffect
            }
        }
        val displayedSession = pending ?: installed
        val feedback = provisioningFeedback(
            verification = verification,
            presentation = pumpSetupPresentation(
                causes = displayedSession?.availability?.causes.orEmpty(),
                hasSavedDetails = displayedSession != null,
                verified = installed?.verifiedAt != null,
            ),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                getString(R.string.ypsopump_setup_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary
            )
            installed?.let {
                Text(
                    getString(R.string.ypsopump_installed_summary, it.serial, it.mac, it.keyFingerprint),
                    color = colors.textPrimary
                )
                Text(
                    it.createdAt?.let { created -> getString(R.string.ypsopump_key_age, age(created)) }
                        ?: getString(R.string.ypsopump_key_age_unknown),
                    color = colors.textSecondary
                )
                it.importedAt?.let { imported ->
                    Text(
                        getString(R.string.ypsopump_imported_at, format(imported)),
                        color = colors.textSecondary
                    )
                }
            }
            pending?.let {
                Text(getString(R.string.ypsopump_pending_summary, it.serial, it.mac, it.keyFingerprint), color = colors.textPrimary)
            }
            when {
                verification == VerificationPresentation.CHECKING -> {
                    Text(
                        getString(R.string.ypsopump_verifying),
                        color = colors.textPrimary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            service.cancelCandidate()
                            installed = service.installed()
                            pending = service.pending()
                            verificationState = service.verificationState()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(getString(R.string.ypsopump_cancel_verification)) }
                }
                else -> {
                    val message = if (feedback.message == R.string.ypsopump_verified_at)
                        getString(R.string.ypsopump_verified_at, format(installed!!.verifiedAt!!))
                    else if (feedback.wrapsAction) getString(R.string.ypsopump_verification_failed, getString(feedback.message))
                    else getString(feedback.message)
                    Text(
                        if (feedback.tone == ProvisioningFeedbackTone.ERROR) getString(R.string.ypsopump_setup_error, message) else message,
                        color = colors.textPrimary,
                        modifier = if (feedback.tone == ProvisioningFeedbackTone.ERROR) Modifier.semantics { liveRegion = LiveRegionMode.Assertive } else Modifier
                    )
                }
            }
            OutlinedTextField(
                serial, { serial = it; serialError = null }, label = { Text(getString(R.string.ypsopump_real_serial)) },
                modifier = Modifier.fillMaxWidth().focusRequester(serialFocus), singleLine = true, isError = serialError != null,
                supportingText = serialError?.let { value -> { Text(value) } },
                enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { macFocus.requestFocus() })
            )
            OutlinedTextField(
                mac, { mac = it; macError = null }, label = { Text(getString(R.string.ypsopump_ble_mac)) },
                modifier = Modifier.fillMaxWidth().focusRequester(macFocus), singleLine = true, isError = macError != null,
                supportingText = macError?.let { value -> { Text(value) } },
                enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { keyFocus.requestFocus() })
            )
            OutlinedTextField(
                key,
                { key = it; keyError = null },
                label = { Text(if (installed == null) getString(R.string.ypsopump_session_key) else getString(R.string.ypsopump_replacement_key_optional)) },
                modifier = Modifier.fillMaxWidth().focusRequester(keyFocus),
                singleLine = true,
                visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                isError = keyError != null,
                supportingText = keyError?.let { value -> { Text(value) } },
                enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing,
                colors = fieldColors
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = showKey,
                    role = Role.Checkbox,
                    enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing,
                    onValueChange = { showKey = it }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = showKey,
                    onCheckedChange = null,
                    enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing,
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        checkmarkColor = colors.onAccent,
                        uncheckedColor = colors.textTertiary
                    )
                )
                Text(
                    getString(R.string.ypsopump_show_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Button(onClick = {
                installing = true
                lifecycleScope.launch {
                    val result = runCatching {
                        verificationStarter.installManual(
                            YpsoProvisioningService.ManualDraft(serial, mac, key.takeIf(String::isNotBlank))
                        )
                    }
                    installing = false
                    result.onSuccess {
                        serialError = null; macError = null; keyError = null; generalError = null
                        key = ""; verificationState = service.verificationState()
                    }
                    .onFailure {
                        when (it) {
                            is YpsoProvisioningService.ManualValidationException -> when (it.field) {
                                YpsoProvisioningService.ManualField.SERIAL -> serialError = getString(R.string.ypsopump_invalid_serial)
                                YpsoProvisioningService.ManualField.MAC -> macError = getString(R.string.ypsopump_invalid_mac)
                                YpsoProvisioningService.ManualField.KEY ->
                                    keyError = if (it.message == "Replacement key required after rejection")
                                        getString(R.string.ypsopump_rekey_new_key_required)
                                    else getString(R.string.ypsopump_invalid_key)
                            }
                            is VerificationStartException -> generalError = getString(R.string.ypsopump_verification_start_failed)
                            else -> generalError = getString(R.string.ypsopump_save_failed)
                        }
                    }
                }
            }, enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.ypsopump_save_verify)) }
            if (installing) {
                Text(
                    getString(R.string.ypsopump_saving),
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                getString(R.string.ypsopump_import_heading),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            Text(
                getString(R.string.ypsopump_private_transfer_warning),
                color = colors.textSecondary
            )
            Button(onClick = { picker.launch(arrayOf("application/json", "text/json", "text/plain")) }, enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing, modifier = Modifier.fillMaxWidth()) {
                Text(getString(R.string.ypsopump_import_session_file))
            }
            if (importingDocument) {
                Text(
                    getString(R.string.ypsopump_importing),
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            selected?.let { document ->
                Text(
                    getString(R.string.ypsopump_import_review, document.serial, document.mac, document.fingerprint),
                    color = colors.textPrimary
                )
                Text(getString(R.string.ypsopump_key_created_at, format(document.createdAt)), color = colors.textSecondary)
                Text(getString(R.string.ypsopump_key_age, age(document.createdAt)), color = colors.textSecondary)
                Button(onClick = {
                    val operationDocument = detachDocumentForInstallation(document)
                    selectedDocument = null
                    installing = true
                    lifecycleScope.launch {
                        val result = runCatching {
                            verificationStarter.installDocument(operationDocument)
                        }
                        installing = false
                        result.onSuccess {
                            generalError = null; verificationState = service.verificationState()
                        }
                        .onFailure {
                            selectedDocument = null
                            generalError = if (it.message == "Replacement key required after rejection")
                                getString(R.string.ypsopump_rekey_new_key_required)
                            else if (it is VerificationStartException) getString(R.string.ypsopump_verification_start_failed)
                            else getString(R.string.ypsopump_save_failed)
                        }
                    }
                }, enabled = verification != VerificationPresentation.CHECKING && !importingDocument && !installing, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.ypsopump_apply_import)) }
            }
            (generalError ?: importError)?.let {
                Text(
                    getString(R.string.ypsopump_setup_error, it),
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
        }
    }

    private fun format(instant: java.time.Instant): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault()).format(instant)

    private fun age(instant: java.time.Instant): String {
        val days = Duration.between(instant, java.time.Instant.now()).toDays().coerceAtLeast(0)
        return resources.getQuantityString(R.plurals.ypsopump_key_age_days, days.toInt(), days)
    }

}
