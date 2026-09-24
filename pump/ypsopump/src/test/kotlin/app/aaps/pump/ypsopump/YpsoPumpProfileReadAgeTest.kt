package app.aaps.pump.ypsopump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.ypsopump.data.YpsoProfileReadback
import app.aaps.pump.ypsopump.data.YpsoPumpState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class YpsoPumpProfileReadAgeTest {

    @Test
    fun `profile last read uses compact days and hours past a day`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val now = Instant.parse("2026-09-18T12:00:00Z")
        val template = YpsoProfileReadbackTest.verified()
        val evidence = YpsoProfileReadback.VerifiedReadback(
            template.generation, template.reboot, template.connectionId,
            template.activeProgram, template.profileA, template.profileB,
            template.acquiredElapsedMs, zone, template.eventCount,
            observedAt = now.minus(Duration.ofHours(27)),
        )
        val pumpState = YpsoPumpState().apply {
            currentZone = { zone }
            publishProfileEvidence(evidence)
        }
        val rh: ResourceHelper = mock()
        whenever(rh.gs(any())).thenReturn("localized")
        whenever(rh.gs(any(), anyVararg())).thenReturn("localized")
        val dateUtil: DateUtil = mock()
        whenever(dateUtil.now()).thenReturn(now.toEpochMilli())

        val rows = buildPumpStatusState(pumpState, mock(), dateUtil, rh).rows

        assertThat(rows.map { it.value }).contains("1d 3h")
    }
}
