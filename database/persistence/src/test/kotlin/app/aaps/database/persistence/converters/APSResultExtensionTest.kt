package app.aaps.database.persistence.converters

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.RT
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.inject.Provider

class APSResultExtensionTest {

    @Test
    fun `an aborted run keeps its non-finite inputs through storage`() {
        val aborted: APSResult = mock {
            on { algorithm } doReturn APSResult.Algorithm.SMB
            on { date } doReturn 1000L
            on { autosensResult } doReturn AutosensResult(ratio = Double.NaN)
            on { iobData } doReturn arrayOf(IobTotal(time = 1000L, iob = Double.POSITIVE_INFINITY))
            on { rawData() } doReturn RT(algorithm = APSResult.Algorithm.SMB, runningDynamicIsf = false, timestamp = 1000L, duration = 30, rate = 1.0)
        }
        val read: APSResult = mock()
        whenever(read.with(any())).thenReturn(read)

        aborted.toDb().fromDb(Provider { read })

        verify(read).autosensResult = argThat { ratio.isNaN() }
        verify(read).iobData = argThat { single().iob == Double.POSITIVE_INFINITY }
    }
}
