package app.aaps.workflow

import androidx.work.ListenableWorker.Result.Success
import androidx.work.WorkerParameters
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertIs

class InvokeLoopWorkerTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var ads: AutosensDataStore

    private lateinit var storage: DataWorkerStorage

    init {
        addInjector {
            if (it is InvokeLoopWorker) {
                it.dataWorkerStorage = storage
                it.iobCobCalculator = iobCobCalculator
                it.loop = loop
            }
        }
    }

    @Test
    fun `one minute events sharing a processed bucket invoke the loop only once per bucket`() = runBlocking {
        storage = DataWorkerStorage(context)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        var lastTimestamp = 0L
        whenever(loop.lastBgTriggeredRun).thenAnswer { lastTimestamp }
        doAnswer { lastTimestamp = it.getArgument(0); null }.whenever(loop).lastBgTriggeredRun = any()

        val start = T.hours(2).msecs()
        for (minute in 0 until 15) {
            val rawTimestamp = start + T.mins(minute.toLong()).msecs()
            // The companion dense-data test verifies these timestamps using the real bucketer.
            val bucketTimestamp = start + T.mins((minute / 5 * 5).toLong()).msecs()
            whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(bucketTimestamp, 100.0))
            val parameters = mock<WorkerParameters>()
            whenever(parameters.inputData).thenReturn(
                storage.storeInputData(InvokeLoopWorker.InvokeLoopData(EventNewBG(rawTimestamp)))
            )
            val worker = InvokeLoopWorker(context, parameters)

            assertIs<Success>(worker.doWorkAndLog())
            verify(loop, times(minute / 5 + 1)).invoke(any(), eq(true), eq(false))
        }
    }
}
