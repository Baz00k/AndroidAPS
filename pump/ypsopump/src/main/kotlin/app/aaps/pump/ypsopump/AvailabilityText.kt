package app.aaps.pump.ypsopump

import androidx.annotation.StringRes
import app.aaps.pump.ypsopump.crypto.PumpSession

/**
 * The complete, intentional vocabulary presented outside the provisioning boundary.
 *
 * Diagnostic availability facts may coexist, but a person can act on only one instruction at a
 * time. This model selects that instruction at the boundary so composables, notifications, and
 * pump-tab state cannot accidentally display protocol diagnostics or concatenate causes.
 */
internal enum class PumpSetupPresentation(@StringRes val message: Int) {
    SETUP_REQUIRED(R.string.ypsopump_cause_unconfigured),
    DETAILS_NEED_CHECKING(R.string.ypsopump_cause_identity_mismatch),
    KEY_MAY_NEED_UPDATING(R.string.ypsopump_cause_rekey),
    BLUETOOTH_NEEDS_ATTENTION(R.string.ypsopump_cause_bond_permission),
    CONNECTION_FAILED(R.string.ypsopump_cause_transport),
    PUMP_NEEDS_CHECKING(R.string.ypsopump_cause_authentication),
    STATUS_NEEDS_CHECKING(R.string.ypsopump_cause_encrypted_status),
    DETAILS_NEED_VERIFICATION(R.string.ypsopump_configured_unverified),
    READY(R.string.ypsopump_connected),
}

/** Translate diagnostic facts once; callers only receive the operator presentation vocabulary. */
internal fun pumpSetupPresentation(
    causes: Set<PumpSession.AvailabilityCause>,
    hasSavedDetails: Boolean,
    verified: Boolean,
): PumpSetupPresentation = when {
    !hasSavedDetails || PumpSession.AvailabilityCause.UNCONFIGURED in causes -> PumpSetupPresentation.SETUP_REQUIRED
    PumpSession.AvailabilityCause.IDENTITY_MISMATCH in causes -> PumpSetupPresentation.DETAILS_NEED_CHECKING
    PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED in causes -> PumpSetupPresentation.KEY_MAY_NEED_UPDATING
    PumpSession.AvailabilityCause.BOND_OR_PERMISSION in causes -> PumpSetupPresentation.BLUETOOTH_NEEDS_ATTENTION
    PumpSession.AvailabilityCause.AUTHENTICATION in causes -> PumpSetupPresentation.PUMP_NEEDS_CHECKING
    PumpSession.AvailabilityCause.KEY_REJECTED in causes -> PumpSetupPresentation.KEY_MAY_NEED_UPDATING
    PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE in causes -> PumpSetupPresentation.STATUS_NEEDS_CHECKING
    PumpSession.AvailabilityCause.TRANSPORT in causes -> PumpSetupPresentation.CONNECTION_FAILED
    verified -> PumpSetupPresentation.READY
    else -> PumpSetupPresentation.DETAILS_NEED_VERIFICATION
}
