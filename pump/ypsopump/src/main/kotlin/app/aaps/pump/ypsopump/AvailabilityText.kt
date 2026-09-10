package app.aaps.pump.ypsopump

import androidx.annotation.StringRes
import app.aaps.pump.ypsopump.crypto.PumpSession

@StringRes
internal fun PumpSession.AvailabilityCause.labelResource(): Int = when (this) {
    PumpSession.AvailabilityCause.UNCONFIGURED                -> R.string.ypsopump_cause_unconfigured
    PumpSession.AvailabilityCause.BOND_OR_PERMISSION          -> R.string.ypsopump_cause_bond_permission
    PumpSession.AvailabilityCause.TRANSPORT                   -> R.string.ypsopump_cause_transport
    PumpSession.AvailabilityCause.AUTHENTICATION              -> R.string.ypsopump_cause_authentication
    PumpSession.AvailabilityCause.ENCRYPTED_STATUS_UNAVAILABLE -> R.string.ypsopump_cause_encrypted_status
    PumpSession.AvailabilityCause.SUSPECTED_REKEY_REQUIRED    -> R.string.ypsopump_cause_rekey
    PumpSession.AvailabilityCause.COUNTER_UNCERTAIN           -> R.string.ypsopump_cause_counter_uncertain
    PumpSession.AvailabilityCause.IDENTITY_MISMATCH           -> R.string.ypsopump_cause_identity_mismatch
}

internal fun Set<PumpSession.AvailabilityCause>.localizedSummary(resolve: (Int) -> String): String =
    joinToString { resolve(it.labelResource()) }

/** Operator-visible causes only. COUNTER_UNCERTAIN is internal replay state, never an operator action. */
internal fun Set<PumpSession.AvailabilityCause>.operatorCauses(): Set<PumpSession.AvailabilityCause> =
    this - PumpSession.AvailabilityCause.COUNTER_UNCERTAIN

internal fun Set<PumpSession.AvailabilityCause>.operatorSummary(resolve: (Int) -> String): String =
    operatorCauses().localizedSummary(resolve)
