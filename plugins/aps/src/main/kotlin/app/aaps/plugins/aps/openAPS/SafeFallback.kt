package app.aaps.plugins.aps.openAPS

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import kotlin.math.abs

/** A value the dosing decision depends on cannot be trusted, so the run must not dose from it. */
class UntrustedValueException(message: String) : RuntimeException(message)

fun Double.requireFinite(name: String): Double =
    if (isFinite()) this else throw UntrustedValueException("$name=$this is not finite")

fun Double.requirePositive(name: String): Double =
    if (isFinite() && this > 0.0) this else throw UntrustedValueException("$name=$this is not a positive number")

fun Double.requireWithin(limit: Double, name: String): Double =
    if (abs(requireFinite(name)) < limit) this else throw UntrustedValueException("$name=$this is out of range")

/** Checks the glucose, temp, IOB and meal inputs; each algorithm checks its own profile. */
fun requireFiniteState(glucoseStatus: GlucoseStatus, currentTemp: CurrentTemp, iobData: Array<IobTotal>, mealData: MealData) {
    glucoseStatus.glucose.requireFinite("bg")
    glucoseStatus.noise.requireFinite("noise")
    glucoseStatus.delta.requireFinite("delta")
    glucoseStatus.shortAvgDelta.requireFinite("shortAvgDelta")
    glucoseStatus.longAvgDelta.requireFinite("longAvgDelta")
    currentTemp.rate.requireFinite("currenttemp.rate")
    iobData.forEach {
        it.iob.requireFinite("iob")
        it.activity.requireFinite("activity")
        it.iobWithZeroTemp?.iob?.requireFinite("iobWithZeroTemp.iob")
        it.iobWithZeroTemp?.activity?.requireFinite("iobWithZeroTemp.activity")
    }
    mealData.carbs.requireFinite("carbs")
    mealData.mealCOB.requireFinite("mealCOB")
    mealData.slopeFromMaxDeviation.requireFinite("slopeFromMaxDeviation")
    mealData.slopeFromMinDeviation.requireFinite("slopeFromMinDeviation")
}

fun RT.requireFiniteResult() {
    bg?.requireFinite("result bg")
    eventualBG?.requireFinite("eventualBG")
    targetBG?.requireFinite("targetBG")
    insulinReq?.requireFinite("insulinReq")
    sensitivityRatio?.requireFinite("sensitivityRatio")
    COB?.requireFinite("COB")
    IOB?.requireFinite("IOB")
    rate?.requireFinite("rate")
    units?.requireFinite("units")
}

/** Ends the run in [rT] with the [safeFallback], logging the untrusted value and where it was found. */
fun abortRun(rT: RT, e: UntrustedValueException, currentTemp: CurrentTemp, basal: Double, deliverAt: Long): RT {
    rT.consoleError?.add("Aborting run: ${e.message} at ${e.stackTrace.take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }}")
    rT.reason.append("Aborting run: ${e.message}")
    return safeFallback(rT, currentTemp, basal, deliverAt)
}

/**
 * Leaves delivery safe when a run cannot be trusted: replaces a running high temp with a neutral temp of [basal],
 * shortens a long zero temp to 30 minutes, and otherwise changes nothing. It never suspends delivery.
 * If [basal] or the running temp rate cannot be trusted either, a running non-zero temp is cancelled so the pump
 * returns to its own basal schedule.
 */
fun safeFallback(rT: RT, currentTemp: CurrentTemp, basal: Double, deliverAt: Long): RT {
    fun setTemp(rate: Double, duration: Int, reason: String) {
        rT.reason.append(reason)
        rT.deliverAt = deliverAt
        rT.duration = duration
        rT.rate = rate
    }

    val trusted = basal.isFinite() && basal > 0.0 && currentTemp.rate.isFinite()
    when {
        !trusted && currentTemp.duration > 0 && currentTemp.rate != 0.0 ->
            setTemp(0.0, 0, ". Cancelling temp basal of ${currentTemp.rate} because basal $basal or the temp rate cannot be trusted")

        trusted && currentTemp.rate > basal                               ->
            setTemp(basal, 30, ". Replacing high temp basal of ${currentTemp.rate} with neutral temp of $basal")

        currentTemp.rate == 0.0 && currentTemp.duration > 30              ->
            setTemp(0.0, 30, ". Shortening ${currentTemp.duration}m long zero temp to 30m. ")

        trusted                                                           ->
            rT.reason.append(". Temp ${currentTemp.rate} <= current basal ${Math.round(basal * 100) / 100.0}U/hr; doing nothing. ")

        else                                                              ->
            rT.reason.append(". Basal $basal or the temp rate cannot be trusted; doing nothing. ")
    }
    return rT
}
