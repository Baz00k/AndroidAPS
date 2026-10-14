package app.aaps.pump.ypsopump

import androidx.annotation.StringRes
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.ypsopump.ble.YpsoBondState
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing
import app.aaps.pump.ypsopump.ble.YpsoPumpScanner
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService

/** The address to pair, which a supported pump's serial determines; null for an unsupported serial. */
internal fun pairingTarget(serial: String): String? = runCatching { PumpIdentity.macForSerial(serial) }.getOrNull()

/**
 * After a new bond, check the saved session for that pump the usual way: a fresh status read that
 * authenticates, confirms the pump's identity and verifies a pending key. A bond skips the retry
 * backoff once, but never a suspected re-key. Returns whether a check was requested.
 */
internal fun requestCheckAfterPairing(service: YpsoProvisioningService, commandQueue: CommandQueue, mac: String, reason: String): Boolean {
    val saved = service.pending() ?: service.installed() ?: return false
    if (!saved.mac.equals(mac, ignoreCase = true)) return false
    if (!service.requestConnectionAttempt()) return false
    commandQueue.ensureStatusReadQueued(reason)
    return true
}

internal enum class PairingPresentation(@StringRes val message: Int, val tone: ProvisioningFeedbackTone, val canPair: Boolean) {
    DETAILS_REQUIRED(R.string.ypsopump_setup_start, ProvisioningFeedbackTone.NEUTRAL, false),
    NOT_PAIRED(R.string.ypsopump_pairing_not_paired, ProvisioningFeedbackTone.NEUTRAL, true),
    WAITING(R.string.ypsopump_pairing_waiting, ProvisioningFeedbackTone.NEUTRAL, false),
    PAIRED(R.string.ypsopump_pairing_paired, ProvisioningFeedbackTone.SUCCESS, false),
    NOT_COMPLETED(R.string.ypsopump_pairing_not_completed, ProvisioningFeedbackTone.ERROR, true),
    TIMED_OUT(R.string.ypsopump_pairing_timed_out, ProvisioningFeedbackTone.ERROR, true),
    NOT_STARTED(R.string.ypsopump_pairing_not_started, ProvisioningFeedbackTone.ERROR, true),
    BLUETOOTH_OFF(R.string.ypsopump_pairing_bluetooth_off, ProvisioningFeedbackTone.ERROR, false),
    PERMISSION_REQUIRED(R.string.ypsopump_pairing_permission, ProvisioningFeedbackTone.ERROR, true),
}

/**
 * What the person sees about pairing [target]. Verification after a bond is the setup status's to report.
 * An attempt for [target] that is waiting or has failed takes precedence over Android still bonding, so
 * a timeout is shown and can be retried. Outcomes for another address are ignored.
 */
internal fun pairingPresentation(target: String?, bond: YpsoBondState?, attempt: YpsoPumpPairing.Attempt?): PairingPresentation {
    if (target == null) return PairingPresentation.DETAILS_REQUIRED
    val own = attempt?.takeIf { it.mac.equals(target, ignoreCase = true) }?.phase
    if (own == YpsoPumpPairing.Phase.WAITING) return PairingPresentation.WAITING
    val failure = when (own) {
        YpsoPumpPairing.Phase.NOT_COMPLETED -> PairingPresentation.NOT_COMPLETED
        YpsoPumpPairing.Phase.TIMED_OUT -> PairingPresentation.TIMED_OUT
        YpsoPumpPairing.Phase.NOT_STARTED -> PairingPresentation.NOT_STARTED
        else -> null
    }
    return when (bond) {
        YpsoBondState.BONDED -> PairingPresentation.PAIRED
        YpsoBondState.BLUETOOTH_OFF -> PairingPresentation.BLUETOOTH_OFF
        YpsoBondState.PERMISSION_REQUIRED -> PairingPresentation.PERMISSION_REQUIRED
        YpsoBondState.BONDING -> failure ?: PairingPresentation.WAITING
        YpsoBondState.NOT_BONDED, null -> failure ?: PairingPresentation.NOT_PAIRED
    }
}

internal enum class SetupAction { FIND_PUMP, PAIR_PUMP, CANCEL_CHECK }

/** The setup screen's one status card: a single instruction, whether work is under way, and what can be done. */
internal data class SetupCard(
    @StringRes val message: Int,
    val tone: ProvisioningFeedbackTone,
    val progress: Boolean = false,
    val actions: List<SetupAction> = emptyList(),
    val showFoundPumps: Boolean = false,
)

/**
 * Chooses the card. Work under way speaks first; a phone that is not paired cannot reach the pump, so pairing
 * comes before the setup status; and a search is only shown while no supported serial has been entered.
 */
internal fun setupCard(
    @StringRes busyMessage: Int?,
    feedback: ProvisioningFeedback,
    checking: Boolean,
    configured: Boolean,
    pairing: PairingPresentation,
    scan: YpsoPumpScanner.Scan?,
): SetupCard {
    if (busyMessage != null) return SetupCard(busyMessage, ProvisioningFeedbackTone.NEUTRAL, progress = true)
    val cancel = listOfNotNull(SetupAction.CANCEL_CHECK.takeIf { checking })
    if (pairing == PairingPresentation.WAITING) return SetupCard(pairing.message, pairing.tone, progress = true, actions = cancel)
    if (scan?.phase == YpsoPumpScanner.Phase.SCANNING)
        return SetupCard(R.string.ypsopump_scan_running, ProvisioningFeedbackTone.NEUTRAL, progress = true, showFoundPumps = true)
    val pair = listOfNotNull(SetupAction.PAIR_PUMP.takeIf { pairing.canPair })
    if (checking) return SetupCard(feedback.message, feedback.tone, progress = true, actions = cancel + pair)
    if (pairing == PairingPresentation.DETAILS_REQUIRED) {
        if (configured) return SetupCard(feedback.message, feedback.tone)
        val found = scan?.pumps.orEmpty().isNotEmpty()
        val message = when (scan?.phase) {
            YpsoPumpScanner.Phase.FINISHED -> if (found) R.string.ypsopump_scan_choose else R.string.ypsopump_scan_none
            YpsoPumpScanner.Phase.FAILED -> R.string.ypsopump_scan_failed
            YpsoPumpScanner.Phase.BLUETOOTH_OFF -> R.string.ypsopump_scan_bluetooth_off
            YpsoPumpScanner.Phase.PERMISSION_REQUIRED -> R.string.ypsopump_scan_permission
            YpsoPumpScanner.Phase.LOCATION_OFF -> R.string.ypsopump_scan_location_off
            YpsoPumpScanner.Phase.SCANNING, null -> R.string.ypsopump_setup_start
        }
        val tone = if (message == R.string.ypsopump_setup_start || message == R.string.ypsopump_scan_choose) ProvisioningFeedbackTone.NEUTRAL
        else ProvisioningFeedbackTone.ERROR
        return SetupCard(message, tone, actions = listOf(SetupAction.FIND_PUMP), showFoundPumps = found)
    }
    // An entered serial already names the pump, so pairing it is the one thing to do.
    if (pairing != PairingPresentation.PAIRED) return SetupCard(pairing.message, pairing.tone, actions = pair)
    if (!configured) return SetupCard(R.string.ypsopump_setup_add_key, ProvisioningFeedbackTone.NEUTRAL)
    return SetupCard(feedback.message, feedback.tone)
}
