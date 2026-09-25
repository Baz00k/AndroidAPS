package app.aaps.plugins.sync.wear.wearintegration

import app.aaps.core.interfaces.rx.weardata.EventData
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WearReservoirTest {

    @Test fun unknownReservoirCanBeSentAsStatus() {
        for (unknown in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val reservoir = finiteWearReservoir(unknown)
            assertThat(reservoir).isEqualTo(0.0)
            assertThat(status(reservoir).serialize()).contains("\"reservoir\":0.0")
        }
    }

    @Test fun knownReservoirIsPreserved() {
        assertThat(finiteWearReservoir(6.83)).isEqualTo(6.83)
    }

    private fun status(reservoir: Double) = EventData.Status(
        dataset = 0,
        externalStatus = "",
        iobSum = "",
        iobDetail = "",
        cob = "",
        currentBasal = "",
        battery = "",
        rigBattery = "",
        openApsStatus = -1,
        bgi = "",
        batteryLevel = 0,
        tempTarget = "",
        tempTargetLevel = 0,
        reservoirString = "",
        reservoir = reservoir,
        reservoirLevel = 0
    )
}
