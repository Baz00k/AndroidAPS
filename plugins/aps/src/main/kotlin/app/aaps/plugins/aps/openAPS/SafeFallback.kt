package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.RT

/** A value the dosing decision depends on cannot be trusted, so the run must not dose from it. */
class UntrustedValueException(message: String) : RuntimeException(message)

fun Double.requireFinite(name: String): Double =
    if (isFinite()) this else throw UntrustedValueException("$name=$this is not finite")

fun Double.requirePositive(name: String): Double =
    if (isFinite() && this > 0.0) this else throw UntrustedValueException("$name=$this is not a positive number")

/**
 * Leaves delivery safe when a run cannot be trusted: replaces a running high temp with a neutral temp of [basal],
 * shortens a long zero temp to 30 minutes, and otherwise changes nothing. It never suspends delivery.
 */
fun safeFallback(rT: RT, currenttemp: CurrentTemp, basal: Double, deliverAt: Long): RT {
    if (currenttemp.rate > basal) {
        rT.reason.append(". Replacing high temp basal of ${currenttemp.rate} with neutral temp of $basal")
        rT.deliverAt = deliverAt
        rT.duration = 30
        rT.rate = basal
    } else if (currenttemp.rate == 0.0 && currenttemp.duration > 30) {
        rT.reason.append(". Shortening " + currenttemp.duration + "m long zero temp to 30m. ")
        rT.deliverAt = deliverAt
        rT.duration = 30
        rT.rate = 0.0
    } else {
        rT.reason.append(". Temp ${currenttemp.rate} <= current basal ${Math.round(basal * 100) / 100.0}U/hr; doing nothing. ")
    }
    return rT
}
