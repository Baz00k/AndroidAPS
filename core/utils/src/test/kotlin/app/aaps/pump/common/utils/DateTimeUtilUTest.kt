package app.aaps.pump.common.utils

import app.aaps.core.utils.DateTimeUtil
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import java.time.DateTimeException
import java.time.LocalDateTime

internal class DateTimeUtilUTest {

    @Test fun getaTechDateDifferenceAsMinutes() {
        val dt1 = 20191001182301L
        val dt2 = 20191001192805L
        val aTechDateDifferenceAsMinutes = DateTimeUtil.getATechDateDifferenceAsMinutes(dt1, dt2)
        assertThat(aTechDateDifferenceAsMinutes).isEqualTo(65)
    }

    @Test fun `packed pump times retain local fields leap dates and seconds precision`() {
        val local: LocalDateTime = DateTimeUtil.toLocalDateTime(20240229235959)
        assertThat(local).isEqualTo(LocalDateTime.of(2024, 2, 29, 23, 59, 59))
        assertThat(DateTimeUtil.toATechDate(local)).isEqualTo(20240229235959)
        assertThat(DateTimeUtil.getATechDateDifferenceAsSeconds(20240229235959, 20240301000001)).isEqualTo(2)
        assertThat(DateTimeUtil.getATechDateDifferenceAsSeconds(20240301000001, 20240229235959)).isEqualTo(-2)
        assertThat(DateTimeUtil.getATechDateDifferenceAsMinutes(20240301000001, 20240229235959)).isEqualTo(0)
        assertThat(DateTimeUtil.getATechDateDifferenceAsMinutes(20240301000101, 20240229235959)).isEqualTo(-1)
        assertThrows(DateTimeException::class.java) { DateTimeUtil.toLocalDateTime(20250229000000) }
        assertThrows(ArithmeticException::class.java) { DateTimeUtil.getATechDateDifferenceAsSeconds(20000101000000, 21000101000000) }
    }

    @Test fun `packed differences remain wall-clock arithmetic through DST`() {
        // Packed pump time contains no zone: a gap is a valid local field value here.
        assertThat(DateTimeUtil.toLocalDateTime(20260329023000)).isEqualTo(LocalDateTime.of(2026, 3, 29, 2, 30))
        assertThat(DateTimeUtil.getATechDateDifferenceAsSeconds(20260329015959, 20260329030000)).isEqualTo(3601)
        assertThat(DateTimeUtil.getATechDateDifferenceAsSeconds(20261025020000, 20261025030000)).isEqualTo(3600)
    }
}
