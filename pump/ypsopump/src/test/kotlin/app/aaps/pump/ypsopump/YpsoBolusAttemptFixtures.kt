package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.bolus.YpsoBolusAttempt
import app.aaps.pump.ypsopump.bolus.YpsoBolusBaseline
import app.aaps.pump.ypsopump.bolus.YpsoBolusOutcome
import app.aaps.pump.ypsopump.bolus.YpsoBolusShape
import app.aaps.pump.ypsopump.bolus.YpsoBolusTreatment

/** Shared durable-attempt fixtures so identity tests describe one dose the same way everywhere. */
object YpsoBolusAttemptFixtures {

    fun extended(
        baselinePumpId: Long,
        slowSequence: Long?,
        requestedCentiUnits: Int = 50,
        durationMinutes: Int = 15,
        outcome: YpsoBolusOutcome = YpsoBolusOutcome.DELIVERING,
    ) = YpsoBolusAttempt(
        requestId = "request",
        pumpSerial = "10000001",
        sessionGeneration = "generation",
        treatment = YpsoBolusTreatment.NORMAL,
        requestedCentiUnits = requestedCentiUnits,
        payloadHash = "ab".repeat(32),
        baseline = YpsoBolusBaseline(10, 20, baselinePumpId, 1, 2, 21, 1_000),
        createdAt = 1_100,
        shape = YpsoBolusShape.EXTENDED,
        durationMinutes = durationMinutes,
        outcome = outcome,
        dispatchCounter = 1,
        dispatchedAt = 1_200,
        pumpSlowSequence = slowSequence,
    )
}
