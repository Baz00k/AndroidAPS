package app.aaps.pump.ypsopump.bolus

data class YpsoBolusReadiness(
    val connected: Boolean,
    val authenticatedCurrentSession: Boolean,
    val statusCurrent: Boolean,
    val pumpRunning: Boolean,
    val reservoirHasInsulin: Boolean,
    val immediateBolusIdle: Boolean,
    val extendedOrMixedBolusIdle: Boolean,
    val stableHistoryCursorCaptured: Boolean,
    val unresolvedInsulinAbsent: Boolean,
)

sealed interface YpsoBolusPreflight {
    data object Ready : YpsoBolusPreflight
    data class Blocked(val reason: Reason) : YpsoBolusPreflight

    enum class Reason {
        DISCONNECTED,
        SESSION_NOT_CURRENT,
        STATUS_NOT_CURRENT,
        PUMP_NOT_RUNNING,
        RESERVOIR_EMPTY,
        IMMEDIATE_BOLUS_ACTIVE,
        UNSUPPORTED_DELIVERY_ACTIVE,
        HISTORY_BASELINE_MISSING,
        UNRESOLVED_INSULIN,
    }
}

object YpsoBolusPreflightPolicy {
    fun evaluate(value: YpsoBolusReadiness): YpsoBolusPreflight {
        val reason = when {
            !value.connected -> YpsoBolusPreflight.Reason.DISCONNECTED
            !value.authenticatedCurrentSession -> YpsoBolusPreflight.Reason.SESSION_NOT_CURRENT
            !value.statusCurrent -> YpsoBolusPreflight.Reason.STATUS_NOT_CURRENT
            !value.pumpRunning -> YpsoBolusPreflight.Reason.PUMP_NOT_RUNNING
            !value.reservoirHasInsulin -> YpsoBolusPreflight.Reason.RESERVOIR_EMPTY
            !value.immediateBolusIdle -> YpsoBolusPreflight.Reason.IMMEDIATE_BOLUS_ACTIVE
            !value.extendedOrMixedBolusIdle -> YpsoBolusPreflight.Reason.UNSUPPORTED_DELIVERY_ACTIVE
            !value.stableHistoryCursorCaptured -> YpsoBolusPreflight.Reason.HISTORY_BASELINE_MISSING
            !value.unresolvedInsulinAbsent -> YpsoBolusPreflight.Reason.UNRESOLVED_INSULIN
            else -> null
        }
        return reason?.let(YpsoBolusPreflight::Blocked) ?: YpsoBolusPreflight.Ready
    }

    /** Cancellation deliberately has fewer prerequisites than a new dose. */
    fun evaluateCancellation(
        connected: Boolean,
        authenticatedCurrentSession: Boolean,
        intendedBolusIdentityKnown: Boolean,
        cancellationNotAlreadyDispatched: Boolean,
    ): YpsoBolusPreflight {
        val reason = when {
            !connected -> YpsoBolusPreflight.Reason.DISCONNECTED
            !authenticatedCurrentSession -> YpsoBolusPreflight.Reason.SESSION_NOT_CURRENT
            !intendedBolusIdentityKnown -> YpsoBolusPreflight.Reason.HISTORY_BASELINE_MISSING
            !cancellationNotAlreadyDispatched -> YpsoBolusPreflight.Reason.UNRESOLVED_INSULIN
            else -> null
        }
        return reason?.let(YpsoBolusPreflight::Blocked) ?: YpsoBolusPreflight.Ready
    }
}
