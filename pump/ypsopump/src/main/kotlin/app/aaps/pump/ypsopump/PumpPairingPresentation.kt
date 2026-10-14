package app.aaps.pump.ypsopump

import androidx.annotation.StringRes
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.pump.ypsopump.ble.YpsoBondState
import app.aaps.pump.ypsopump.ble.YpsoPumpPairing
import app.aaps.pump.ypsopump.provisioning.PumpIdentity
import app.aaps.pump.ypsopump.provisioning.YpsoProvisioningService

/** The address to pair: only a supported pump whose address matches its serial. */
internal fun pairingTarget(serial: String, mac: String): String? = runCatching {
    PumpIdentity.validatePair(serial, mac)
    PumpIdentity.normalizeMac(mac)
}.getOrNull()

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
    DETAILS_REQUIRED(R.string.ypsopump_pairing_details_required, ProvisioningFeedbackTone.NEUTRAL, false),
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
