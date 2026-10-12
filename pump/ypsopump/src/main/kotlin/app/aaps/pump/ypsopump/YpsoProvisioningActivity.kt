package app.aaps.pump.ypsopump

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import app.aaps.core.compose.components.GhostButton
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService
import app.aaps.pump.ypsopump.provisioning.SessionDocumentException
import app.aaps.pump.ypsopump.provisioning.YpsoSessionDocument
import app.aaps.pump.ypsopump.crypto.PumpSession
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.queue.CommandQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import javax.inject.Inject
import java.io.File

internal enum class VerificationPresentation { IDLE, CHECKING, SUCCEEDED, FAILED, CANCELLED }

internal enum class ProvisioningFeedbackTone { NEUTRAL, SUCCESS, ERROR }

internal data class ProvisioningFeedback(@StringRes val message: Int, val tone: ProvisioningFeedbackTone)

/** Copies the reviewed secret for an operation, then makes the screen's copy unusable. */
internal fun detachDocumentForInstallation(document: YpsoSessionDocument): YpsoSessionDocument =
    document.copy(sharedKey = document.sharedKey.copyOf()).also { document.sharedKey.fill(0) }

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
        service.installManualAndStartVerification(draft) { commandQueue.ensureStatusReadQueued(verificationReason) }
    }

    suspend fun installDocument(document: YpsoSessionDocument): PumpSession.Installation = installThenStart {
        try {
            service.installDocumentAndStartVerification(document) { commandQueue.ensureStatusReadQueued(verificationReason) }
        } finally {
            document.sharedKey.fill(0)
        }
    }

    private suspend fun installThenStart(install: () -> PumpSession.Installation): PumpSession.Installation = withContext(NonCancellable + Dispatchers.IO) { install() }
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

/** Causes describing the setup being checked; a sticky suspected re-key belongs to the rejected key. */
internal fun displayedCauses(
    verification: VerificationPresentation,
    causes: Set<PumpSession.AvailabilityCause>,
): Set<PumpSession.AvailabilityCause> =
    if (verification == VerificationPresentation.CHECKING) causes - PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED else causes

internal enum class PendingCheck { IN_PROGRESS, RETRYING, STALLED }

/**
 * How a pending check is going. [canProceed] is [YpsoProvisioningService.verificationCanProceed]: without
 * it nothing will check the key until the person acts. A fresh candidate starts with a status cause but
 * no failed attempt, and a failed attempt only matters once no attempt is under way.
 */
internal fun pendingCheck(availability: PumpSession.Availability?, canProceed: Boolean): PendingCheck = when {
    !canProceed -> PendingCheck.STALLED
    availability == null || availability.failures == 0 -> PendingCheck.IN_PROGRESS
    PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in availability.causes -> PendingCheck.IN_PROGRESS
    else -> PendingCheck.RETRYING
}

internal fun provisioningFeedback(
    verification: VerificationPresentation,
    presentation: PumpSetupPresentation,
    pendingCheck: PendingCheck = PendingCheck.RETRYING,
): ProvisioningFeedback = when (verification) {
    // Retryable problems keep the check pending, so say what AAPS is waiting for rather than only "checking",
    // and ask for a new attempt when none will happen on its own.
    VerificationPresentation.CHECKING -> when {
        pendingCheck == PendingCheck.STALLED -> ProvisioningFeedback(R.string.ypsopump_verifying_stalled, ProvisioningFeedbackTone.ERROR)
        pendingCheck == PendingCheck.IN_PROGRESS -> ProvisioningFeedback(R.string.ypsopump_verifying, ProvisioningFeedbackTone.NEUTRAL)
        presentation == PumpSetupPresentation.BLUETOOTH_NEEDS_ATTENTION -> ProvisioningFeedback(presentation.message, ProvisioningFeedbackTone.NEUTRAL)
        presentation == PumpSetupPresentation.CONNECTION_FAILED ||
            presentation == PumpSetupPresentation.AUTHENTICATION_RETRY_REQUIRED ||
            presentation == PumpSetupPresentation.STATUS_NEEDS_CHECKING -> ProvisioningFeedback(R.string.ypsopump_verifying_retry, ProvisioningFeedbackTone.NEUTRAL)
        else -> ProvisioningFeedback(R.string.ypsopump_verifying, ProvisioningFeedbackTone.NEUTRAL)
    }
    VerificationPresentation.FAILED -> when (presentation) {
        PumpSetupPresentation.KEY_MAY_NEED_UPDATING -> ProvisioningFeedback(R.string.ypsopump_verification_key_rejected, ProvisioningFeedbackTone.ERROR)
        PumpSetupPresentation.DETAILS_NEED_CHECKING -> ProvisioningFeedback(presentation.message, ProvisioningFeedbackTone.ERROR)
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

/** Why a picked file cannot be used. Anything but a content problem means the file could not be read. */
@StringRes
internal fun importFailureMessage(error: Throwable): Int = when ((error as? SessionDocumentException)?.problem) {
    SessionDocumentException.Problem.NOT_A_SESSION_FILE -> R.string.ypsopump_import_invalid
    SessionDocumentException.Problem.UNSUPPORTED_PUMP -> R.string.ypsopump_import_unsupported_pump
    SessionDocumentException.Problem.DATED_AFTER_PHONE_CLOCK -> R.string.ypsopump_import_dated_after_clock
    null -> R.string.ypsopump_import_unreadable
}

/**
 * Why saving did not start a check. Only the typed refusals happen before anything is staged; the
 * fallback makes no claim about what was kept, because the status above reflects the stored state.
 */
@StringRes
internal fun saveFailureMessage(error: Throwable): Int = when (error) {
    is YpsoProvisioningService.ReplacementKeyRequiredException -> R.string.ypsopump_import_rejected_key
    is PumpSession.UnresolvedAccountingException -> R.string.ypsopump_save_failed_unresolved
    is PumpSession.KeyOfAnotherPumpException -> R.string.ypsopump_save_failed_other_pump
    is PumpSession.StorageUnavailableException -> R.string.ypsopump_save_failed_storage
    is YpsoProvisioningService.VerificationStartException -> R.string.ypsopump_verification_start_failed
    else -> R.string.ypsopump_save_failed
}

/** Exception types only: messages may echo file contents, and keys must never reach the log. */
internal fun setupFailureLog(operation: String, error: Throwable): String = buildString {
    append("YpsoPump setup ").append(operation).append(" failed: ").append(error.javaClass.simpleName)
    (error as? SessionDocumentException)?.let { append('(').append(it.problem).append(')') }
    error.cause?.let { append(" caused by ").append(it.javaClass.simpleName) }
}

class YpsoProvisioningActivity : TranslatedDaggerAppCompatActivity() {
    @Inject lateinit var provisioning: YpsoProvisioningService
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var plugin: YpsoPumpPlugin
    @Inject lateinit var aapsLogger: AAPSLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (handleShellOwnershipAction()) return
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

    private fun handleShellOwnershipAction(): Boolean {
        if (intent.action !in setOf(
                ACTION_INSPECT_OWNERSHIP,
                ACTION_RECOVER_LOST_JOURNAL_IDENTITY_ONLY,
                ACTION_RECOVER_LOST_JOURNAL,
                ACTION_RECOVER_HISTORY_SELECTOR,
            )
        ) return false
        lifecycleScope.launch {
            val outcome = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    when (intent.action) {
                        ACTION_INSPECT_OWNERSHIP -> "${provisioning.ownershipStatus()},readiness={${plugin.readinessStatus()}}"
                        ACTION_RECOVER_LOST_JOURNAL -> {
                            val path = intent.getStringExtra(EXTRA_SESSION_DOCUMENT_PATH)?.takeIf(String::isNotBlank)
                                ?: error("session document path is required")
                            val documentHash = intent.getStringExtra(EXTRA_SESSION_DOCUMENT_SHA256)?.lowercase()
                                ?: error("session document SHA-256 is required")
                            val evidenceHash = intent.getStringExtra(EXTRA_BOLUS_EVIDENCE_SHA256)?.lowercase()
                                ?: error("bolus evidence SHA-256 is required")
                            File(path).inputStream().use {
                                provisioning.recoverLostJournalForHistory(it, documentHash, evidenceHash)
                            }
                            provisioning.ownershipStatus()
                        }
                        ACTION_RECOVER_LOST_JOURNAL_IDENTITY_ONLY -> {
                            val path = intent.getStringExtra(EXTRA_SESSION_DOCUMENT_PATH)?.takeIf(String::isNotBlank)
                                ?: error("session document path is required")
                            val documentHash = intent.getStringExtra(EXTRA_SESSION_DOCUMENT_SHA256)?.lowercase()
                                ?: error("session document SHA-256 is required")
                            File(path).inputStream().use {
                                provisioning.recoverLostJournalIdentityOnly(it, documentHash)
                            }
                            plugin.onAppVisibilityChanged(true)
                            "REQUESTED:${provisioning.ownershipStatus()}"
                        }
                        ACTION_RECOVER_HISTORY_SELECTOR -> {
                            check(plugin.requestLowerBoundHistoryRecovery {
                                commandQueue.readStatus(YpsoPumpPlugin.LOWER_BOUND_RECOVERY_REASON, null)
                            }) { "selector lower-bound recovery is unavailable, already requested, or was not queued" }
                            "REQUESTED:${provisioning.ownershipStatus()}"
                        }
                        else -> error("unsupported ownership action")
                    }
                }
            }
            outcome.onSuccess { Log.i(OWNERSHIP_LOG_TAG, "${intent.action}:$it") }
                .onFailure { Log.e(OWNERSHIP_LOG_TAG, "${intent.action}:FAILED:${it.javaClass.simpleName}:${it.message}") }
            finish()
        }
        return true
    }

    private var selectedDocument by mutableStateOf<YpsoSessionDocument?>(null)
    // The last save or import failure, shown beside the current state rather than in place of it.
    private var setupFailure by mutableStateOf<Int?>(null)
    private var importingDocument by mutableStateOf(false)

    private fun openDocument(uri: Uri?) {
        if (uri == null) return
        selectedDocument?.sharedKey?.fill(0)
        selectedDocument = null
        setupFailure = null
        importingDocument = true
        lifecycleScope.launch {
            var reviewed: Result<YpsoSessionDocument>? = null
            var transferredToScreen = false
            try {
                reviewed = withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use(provisioning::reviewDocument)
                        ?: throw java.io.FileNotFoundException()
                    }
                }
                currentCoroutineContext().ensureActive()
                reviewed.onSuccess {
                    selectedDocument = it
                    transferredToScreen = true
                    setupFailure = null
                }.onFailure {
                    aapsLogger.warn(LTag.PUMP, setupFailureLog("import review", it))
                    setupFailure = importFailureMessage(it)
                }
            } finally {
                if (!transferredToScreen) reviewed?.getOrNull()?.sharedKey?.fill(0)
                importingDocument = false
            }
        }
    }

    override fun onDestroy() {
        selectedDocument?.sharedKey?.fill(0)
        selectedDocument = null
        super.onDestroy()
    }

    @Composable
    private fun ProvisioningScreen(service: YpsoProvisioningService, importingDocument: Boolean, onPicked: (Uri?) -> Unit) {
        var installed by remember { mutableStateOf(service.installed()) }
        var pending by remember { mutableStateOf(service.pending()) }
        var verificationState by remember { mutableStateOf(service.verificationState()) }
        var checkCanProceed by remember { mutableStateOf(service.verificationCanProceed()) }
        var installing by remember { mutableStateOf(false) }
        var serial by remember { mutableStateOf(installed?.serial.orEmpty()) }
        var mac by remember { mutableStateOf(installed?.mac.orEmpty()) }
        var key by remember { mutableStateOf("") }
        var showKey by remember { mutableStateOf(false) }
        var serialError by remember { mutableStateOf<String?>(null) }
        var macError by remember { mutableStateOf<String?>(null) }
        var keyError by remember { mutableStateOf<String?>(null) }
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
                checkCanProceed = service.verificationCanProceed()
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
        val causes = displayedSession?.availability?.causes.orEmpty()
        val feedback = provisioningFeedback(
            verification = verification,
            presentation = pumpSetupPresentation(
                causes = displayedCauses(verification, causes),
                hasSavedDetails = displayedSession != null,
                verified = installed?.verifiedAt != null,
            ),
            pendingCheck = pendingCheck(displayedSession?.availability, checkCanProceed),
        )
        val busyMessage = when {
            importingDocument -> R.string.ypsopump_importing
            installing -> R.string.ypsopump_saving
            else -> null
        }
        val checking = verification == VerificationPresentation.CHECKING
        val busy = busyMessage != null || checking
        val statusIsError = busyMessage == null && feedback.tone == ProvisioningFeedbackTone.ERROR
        val failure = setupFailure.takeIf { busyMessage == null }
        fun refresh() {
            installed = service.installed()
            pending = service.pending()
            verificationState = service.verificationState()
            checkCanProceed = service.verificationCanProceed()
        }
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
                        getString(busyMessage ?: feedback.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (statusIsError) MaterialTheme.colorScheme.error else colors.textPrimary,
                        modifier = Modifier.semantics {
                            liveRegion = if (statusIsError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        }
                    )
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (checking && busyMessage == null) {
                        GhostButton(getString(R.string.ypsopump_cancel_verification), onClick = {
                            service.cancelCandidate()
                            refresh()
                        })
                    }
                    failure?.let { SetupFailure(getString(it)) }
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
            PrimaryButton(getString(R.string.ypsopump_save_verify), onClick = {
                setupFailure = null
                installing = true
                lifecycleScope.launch {
                    val result = runCatching {
                        verificationStarter.installManual(
                            YpsoProvisioningService.ManualDraft(serial, mac, key.takeIf(String::isNotBlank))
                        )
                    }
                    installing = false
                    refresh()
                    result.onSuccess {
                        serialError = null; macError = null; keyError = null
                        key = ""
                        plugin.onAppVisibilityChanged(true)
                    }
                    .onFailure {
                        aapsLogger.warn(LTag.PUMP, setupFailureLog("save", it))
                        when (it) {
                            is YpsoProvisioningService.ManualValidationException -> when (it.field) {
                                YpsoProvisioningService.ManualField.SERIAL -> serialError = getString(R.string.ypsopump_invalid_serial)
                                YpsoProvisioningService.ManualField.MAC -> macError = getString(R.string.ypsopump_invalid_mac)
                                YpsoProvisioningService.ManualField.KEY -> keyError = getString(R.string.ypsopump_invalid_key)
                            }
                            is YpsoProvisioningService.ReplacementKeyRequiredException -> keyError = getString(R.string.ypsopump_rekey_new_key_required)
                            else -> setupFailure = saveFailureMessage(it)
                        }
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            SecondaryButton(
                getString(R.string.ypsopump_import_session_file),
                onClick = {
                    setupFailure = null
                    picker.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                Modifier.fillMaxWidth(),
                enabled = !busy,
            )
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
                        PrimaryButton(getString(R.string.ypsopump_apply_import), onClick = {
                            val operationDocument = detachDocumentForInstallation(document)
                            selectedDocument = null
                            setupFailure = null
                            installing = true
                            lifecycleScope.launch {
                                val result = runCatching {
                                    verificationStarter.installDocument(operationDocument)
                                }
                                installing = false
                                refresh()
                                result.onSuccess { plugin.onAppVisibilityChanged(true) }
                                .onFailure {
                                    aapsLogger.warn(LTag.PUMP, setupFailureLog("import", it))
                                    setupFailure = saveFailureMessage(it)
                                }
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy)
                    }
                }
            }
        }
    }

    /** Primary-colour text behind an error rule: long red body text is hard to read on every skin. */
    @Composable
    private fun SetupFailure(message: String) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics { liveRegion = LiveRegionMode.Assertive },
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = AapsTheme.colors.textPrimary)
        }
    }

    companion object {
        const val ACTION_INSPECT_OWNERSHIP = "app.aaps.pump.ypsopump.action.INSPECT_OWNERSHIP"
        const val ACTION_RECOVER_LOST_JOURNAL_IDENTITY_ONLY = "app.aaps.pump.ypsopump.action.RECOVER_LOST_JOURNAL_IDENTITY_ONLY"
        const val ACTION_RECOVER_LOST_JOURNAL = "app.aaps.pump.ypsopump.action.RECOVER_LOST_JOURNAL"
        const val ACTION_RECOVER_HISTORY_SELECTOR = "app.aaps.pump.ypsopump.action.RECOVER_HISTORY_SELECTOR"
        const val EXTRA_SESSION_DOCUMENT_PATH = "session_document_path"
        const val EXTRA_SESSION_DOCUMENT_SHA256 = "session_document_sha256"
        const val EXTRA_BOLUS_EVIDENCE_SHA256 = "bolus_evidence_sha256"
        const val OWNERSHIP_LOG_TAG = "YpsoOwnershipImport"
    }
}
