package app.aaps.pump.ypsopump.bolus

import app.aaps.pump.ypsopump.comm.commands.BolusCommand
import kotlin.math.abs
import kotlin.math.roundToInt

data class YpsoValidatedBolusRequest(
    val treatment: YpsoBolusTreatment,
    val shape: YpsoBolusShape,
    val centiUnits: Int,
    val durationMinutes: Int = 0,
    val immediateCentiUnits: Int = 0,
) {
    val units: Double get() = centiUnits / 100.0
    val immediateUnits: Double get() = immediateCentiUnits / 100.0
    val extendedCentiUnits: Int get() = centiUnits - immediateCentiUnits

    fun payload(): ByteArray = BolusCommand(units, durationMinutes, immediateUnits).encode()
}

/** One validator for direct Pump, manual/wizard and SMB requests. It never clamps a dose. */
object YpsoBolusRequestValidator {
    const val PUMP_MIN_UNITS = 0.1
    const val PUMP_MAX_UNITS = 30.0
    const val PUMP_STEP_UNITS = 0.1

    fun validate(units: Double, treatment: YpsoBolusTreatment, aapsMaxBolus: Double): YpsoValidatedBolusRequest =
        validateDelivery(units, durationMinutes = 0, immediateUnits = 0.0, treatment = treatment, aapsMaxBolus = aapsMaxBolus)

    /**
     * Validates a standard, extended or combination bolus. Duration and the combination immediate
     * part must be exactly representable; invalid requests are rejected, never rounded or clamped.
     */
    fun validateDelivery(
        totalUnits: Double,
        durationMinutes: Int,
        immediateUnits: Double,
        treatment: YpsoBolusTreatment,
        aapsMaxBolus: Double,
    ): YpsoValidatedBolusRequest {
        require(treatment != YpsoBolusTreatment.PRIME) { "fill/priming is not supported by therapy delivery" }
        require(aapsMaxBolus.isFinite() && aapsMaxBolus > 0.0) { "AAPS maximum bolus is invalid" }
        require(totalUnits.isFinite()) { "bolus must be finite" }
        require(totalUnits >= PUMP_MIN_UNITS) { "bolus is below the pump minimum" }
        require(totalUnits <= aapsMaxBolus) { "bolus exceeds the AAPS limit" }
        require(totalUnits <= PUMP_MAX_UNITS) { "bolus exceeds the pump protocol limit" }
        val totalCentiUnits = exactSteps(totalUnits, "bolus")
        require(immediateUnits.isFinite()) { "combination immediate amount must be finite" }
        require(durationMinutes in 0..BolusCommand.MAX_DURATION_MINUTES) {
            "durationMinutes is outside 0..${BolusCommand.MAX_DURATION_MINUTES}"
        }
        require(durationMinutes == 0 || durationMinutes in 15..720 && durationMinutes % 15 == 0) {
            "extended duration must be an exact 15-minute step from 15 to 720 minutes"
        }
        val shape = when {
            durationMinutes == 0 -> {
                require(immediateUnits == 0.0) { "an immediate bolus cannot carry a combination part" }
                YpsoBolusShape.IMMEDIATE
            }
            immediateUnits == 0.0 -> YpsoBolusShape.EXTENDED
            else -> YpsoBolusShape.COMBINED
        }
        val immediateCentiUnits = exactSteps(immediateUnits, "combination immediate amount")
        if (shape == YpsoBolusShape.COMBINED) {
            require(immediateCentiUnits >= BolusCommand.MIN_BOLUS_X100) { "combination immediate amount is below the pump minimum" }
            require(totalCentiUnits - immediateCentiUnits >= BolusCommand.MIN_BOLUS_X100) {
                "combination extended amount is below the pump minimum"
            }
        }
        return YpsoValidatedBolusRequest(treatment, shape, totalCentiUnits, durationMinutes, immediateCentiUnits)
    }

    private fun exactSteps(units: Double, name: String): Int {
        require(units >= 0.0) { "$name must not be negative" }
        val steps = units / PUMP_STEP_UNITS
        require(abs(steps - steps.roundToInt()) <= 1e-7) { "$name is not representable at the pump increment" }
        val centiUnits = (units * 100.0).roundToInt()
        require(centiUnits in 0..BolusCommand.MAX_BOLUS_X100)
        return centiUnits
    }
}
