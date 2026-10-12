package app.aaps.pump.common.data

import java.time.Duration
import java.time.ZonedDateTime

/**
 * Created by andy on 28/05/2021.
 */
class PumpTimeDifferenceDto(
    var localDeviceTime: ZonedDateTime,
    var pumpTime: ZonedDateTime
) {

    var timeDifference = 0

    fun calculateDifference() {
        // Positive means the pump is ahead. Divide milliseconds to truncate toward zero
        // for negative sub-second differences too (Duration.seconds floors instead).
        timeDifference = Math.toIntExact(Duration.between(localDeviceTime, pumpTime).toMillis() / 1000)
    }

    init {
        calculateDifference()
    }
}
