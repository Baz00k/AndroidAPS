package app.aaps.pump.ypsopump

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import app.aaps.core.compose.components.AapsCard
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
import javax.inject.Inject

internal enum class VerificationPresentation { IDLE, CHECKING, SUCCEEDED, FAILED, CANCELLED }

internal enum class ProvisioningFeedbackTone { NEUTRAL, SUCCESS, ERROR }

internal data class ProvisioningFeedback(@StringRes val message: Int, val tone: ProvisioningFeedbackTone)

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

internal fun provisioningFeedback(
    verification: VerificationPresentation,
    presentation: PumpSetupPresentation,
): ProvisioningFeedback = when (verification) {
    VerificationPresentation.CHECKING -> ProvisioningFeedback(R.string.ypsopump_verifying, ProvisioningFeedbackTone.NEUTRAL)
    VerificationPresentation.FAILED -> when (presentation) {
        PumpSetupPresentation.KEY_MAY_NEED_UPDATING -> ProvisioningFeedback(R.string.ypsopump_cause_rekey, ProvisioningFeedbackTone.ERROR)
        else -> ProvisioningFeedback(R.string.ypsopump_verification_failed_generic, ProvisioningFeedbackTone.ERROR)
    }
    VerificationPresentation.CANCELLED -> ProvisioningFeedback(R.string.ypsopump_verification_cancelled, ProvisioningFeedbackTone.NEUTRAL)
    VerificationPresentation.SUCCEEDED, VerificationPresentation.IDLE -> when (presentation) {
        PumpSetupPresentation.SETUP_REQUIRED -> ProvisioningFeedback(R.string.ypsopump_cause_unconfigured, ProvisioningFeedbackTone.NEUTRAL)
        PumpSetupPresentation.DETAILS_NEED_VERIFICATION -> ProvisioningFeedback(R.string.ypsopump_configured_unverified, ProvisioningFeedbackTone.NEUTRAL)
        PumpSetupPresentation.READY -> ProvisioningFeedback(R.string.ypsopump_setup_complete, ProvisioningFeedbackTone.SUCCESS)
        else -> ProvisioningFeedback(presentation.message, ProvisioningFeedbackTone.ERROR)
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
        importError = null
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
        LaunchedEffect(installed?.serial, installed?.mac) {
            installed?.let {
                if (it.serial != serial) serial = it.serial
                if (it.mac != mac) mac = it.mac
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
        val busyMessage = when {
            importingDocument -> R.string.ypsopump_importing
            installing -> R.string.ypsopump_saving
            verification == VerificationPresentation.CHECKING -> R.string.ypsopump_verifying
            else -> null
        }
        val busy = busyMessage != null
        val failure = generalError ?: importError
        val statusText = when {
            busyMessage != null -> getString(busyMessage)
            failure != null -> failure
            else -> getString(feedback.message)
        }
        val statusIsError = !busy && (failure != null || feedback.tone == ProvisioningFeedbackTone.ERROR)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (installed == null) Text(
                getString(R.string.ypsopump_setup_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            AapsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (statusIsError) MaterialTheme.colorScheme.error else colors.textPrimary,
                        modifier = Modifier.semantics {
                            liveRegion = if (statusIsError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        }
                    )
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (verification == VerificationPresentation.CHECKING) {
                        TextButton(onClick = {
                            service.cancelCandidate()
                            installed = service.installed()
                            pending = service.pending()
                            verificationState = service.verificationState()
                        }) { Text(getString(R.string.ypsopump_cancel_verification)) }
                    }
                }
            }
            OutlinedTextField(
                serial, { serial = it; serialError = null }, label = { Text(getString(R.string.ypsopump_real_serial)) },
                modifier = Modifier.fillMaxWidth().focusRequester(serialFocus), singleLine = true, isError = serialError != null,
                supportingText = serialError?.let { value -> { Text(value) } },
                enabled = !busy,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { macFocus.requestFocus() })
            )
            OutlinedTextField(
                mac, { mac = it; macError = null }, label = { Text(getString(R.string.ypsopump_ble_mac)) },
                modifier = Modifier.fillMaxWidth().focusRequester(macFocus), singleLine = true, isError = macError != null,
                supportingText = macError?.let { value -> { Text(value) } },
                enabled = !busy,
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
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }, enabled = !busy) {
                        Text(getString(if (showKey) R.string.ypsopump_hide_key else R.string.ypsopump_show_key))
                    }
                },
                enabled = !busy,
                colors = fieldColors
            )
            Button(onClick = {
                generalError = null
                importError = null
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
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.ypsopump_save_verify)) }
            OutlinedButton(
                onClick = {
                    generalError = null
                    picker.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(getString(R.string.ypsopump_import_session_file)) }
            if (selected == null) Text(
                getString(R.string.ypsopump_private_transfer_warning),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            selected?.let { document ->
                AapsCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            getString(R.string.ypsopump_import_review, document.serial, document.mac),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        Button(onClick = {
                            val operationDocument = detachDocumentForInstallation(document)
                            selectedDocument = null
                            generalError = null
                            importError = null
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
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.ypsopump_apply_import)) }
                    }
                }
            }
        }
    }
}
