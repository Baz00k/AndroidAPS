package app.aaps.workflow

import androidx.work.ListenableWorker.Result.Success
import androidx.work.WorkerParameters
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.events.EventNewHistoryData
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.main.iob.iobCobCalculator.data.AutosensDataStoreObject
import app.aaps.shared.tests.TestBaseWithProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertIs

class InvokeLoopWorkerTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop
    @Mock lateinit var ads: AutosensDataStore
    private lateinit var storage: DataWorkerStorage
    private var lastTimestamp = 0L

    init {
        addInjector {
            if (it is InvokeLoopWorker) {
                it.dataWorkerStorage = storage
                it.iobCobCalculator = iobCobCalculator
                it.loop = loop
            }
        }
    }

    @BeforeEach
    fun prepare() {
        storage = DataWorkerStorage(context)
        whenever(iobCobCalculator.ads).thenReturn(ads)
        whenever(loop.lastBgTriggeredRun).thenAnswer { lastTimestamp }
        doAnswer { lastTimestamp = it.getArgument(0); null }.whenever(loop).lastBgTriggeredRun = any()
    }

    private suspend fun run(cause: Event?) {
        val parameters = mock<WorkerParameters>()
        whenever(parameters.inputData).thenReturn(storage.storeInputData(InvokeLoopWorker.InvokeLoopData(cause)))
        assertIs<Success>(InvokeLoopWorker(context, parameters).doWorkAndLog())
    }

    @Test
    fun `one minute events invoke only once per processed bucket`() = runBlocking {
        val start = System.currentTimeMillis() - T.mins(8).msecs()
        var store = AutosensDataStoreObject()
        for (minute in 0L..9L) {
            val rawTimestamp = start + T.mins(minute).msecs()
            store.bgReadings = (minute downTo minute - 30L).map {
                GV(
                    timestamp = start + T.mins(it).msecs(), value = 100.0, raw = 100.0,
                    noise = 0.0, sourceSensor = SourceSensor.LIBRE_2, trendArrow = TrendArrow.FLAT
                )
            }
            store.createBucketedData(aapsLogger, dateUtil)
            store = store.clone() as AutosensDataStoreObject
            whenever(iobCobCalculator.ads).thenReturn(store)

            run(EventNewBG(rawTimestamp))

            verify(loop, times((minute / 5 + 1).toInt())).invoke(any(), eq(true), eq(false))
        }
    }

    @Test
    fun `non glucose calculation does not initiate a new loop run`() = runBlocking {
        whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(T.hours(2).msecs(), 100.0))

        run(EventNewHistoryData(0, false))

        verify(loop, never()).invoke(any(), any(), any())
    }

    @Test
    fun `missing or outdated glucose does not initiate a loop run`() = runBlocking {
        whenever(ads.actualBg()).thenReturn(null)

        run(EventNewBG(T.hours(2).msecs()))

        verify(loop, never()).invoke(any(), any(), any())
    }

    @Test
    fun `older and duplicate buckets cannot repeat a loop run`() = runBlocking {
        val start = T.hours(2).msecs()
        for (time in listOf(start, start, start - T.mins(5).msecs())) {
            whenever(ads.actualBg()).thenReturn(InMemoryGlucoseValue(time, 100.0))
            run(EventNewBG(time))
        }

        verify(loop, times(1)).invoke(any(), eq(true), eq(false))
    }
}
