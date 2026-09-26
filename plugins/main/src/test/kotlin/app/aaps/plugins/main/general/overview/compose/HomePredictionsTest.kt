package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.Predictions
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class HomePredictionsTest {

    private val now = 1_000_000_000L

    private fun result(date: Long = now, predictions: Predictions? = Predictions(IOB = listOf(100, 90, 80))): APSResult =
        mock<APSResult>().also {
            whenever(it.date).thenReturn(date)
            whenever(it.hasPredictions).thenReturn(true)
            whenever(it.predictions()).thenReturn(predictions)
        }

    @Test
    fun `missing disabled stale and future results have no forecasts`() {
        assertThat(buildHomePredictions(null, now) { it }).isEmpty()
        assertThat(buildHomePredictions(result(predictions = null), now) { it }).isEmpty()
        assertThat(buildHomePredictions(result(predictions = Predictions()), now) { it }).isEmpty()
        assertThat(buildHomePredictions(result(now - 15 * 60_000L - 1), now) { it }).isEmpty()
        assertThat(buildHomePredictions(result(now + 1), now) { it }).isEmpty()
        val disabled = result()
        whenever(disabled.hasPredictions).thenReturn(false)
        assertThat(buildHomePredictions(disabled, now) { it }).isEmpty()
    }

    @Test
    fun `uses original timestamps skips starting glucose and converts units`() {
        val data = result(now - 60_000L)
        val mgdl = buildHomePredictions(data, now) { it }.single()
        assertThat(mgdl.kind).isEqualTo(PredictionKind.IOB)
        assertThat(mgdl.points).containsExactly(
            GlucosePoint(now + 4 * 60_000L, 90.0),
            GlucosePoint(now + 9 * 60_000L, 80.0)
        ).inOrder()
        val mmol = buildHomePredictions(data, now) { it / 18.0 }.single()
        assertThat(mmol.points.first().value).isEqualTo(5.0)
        assertThat(mmol.points.map { it.time }).isEqualTo(mgdl.points.map { it.time })
    }

    @Test
    fun `preserves all prediction types and low floor without fabricating missing values`() {
        val predictions = Predictions(
            IOB = listOf(100, 39), COB = listOf(100, 120), ZT = listOf(100, 80),
            UAM = listOf(100, 130), aCOB = listOf(100, 110)
        )
        val series = buildHomePredictions(result(predictions = predictions), now) { it }
        assertThat(series.map { it.kind }).containsExactlyElementsIn(PredictionKind.entries)
        assertThat(series.first().points.single().value).isEqualTo(39.0)
        assertThat(buildHomePredictions(result(predictions = Predictions(IOB = listOf(100))), now) { it }).isEmpty()
    }

    @Test
    fun `expired and invalid points are omitted without shifting later timestamps`() {
        val series = buildHomePredictions(
            result(now - 5 * 60_000L, Predictions(IOB = listOf(100, 100, 0, 90))), now
        ) { it }.single()
        assertThat(series.points).containsExactly(GlucosePoint(now + 10 * 60_000L, 90.0))
    }

    @Test
    fun `forecasts never become historical trace or make an empty chart measured data`() {
        val forecasts = buildHomePredictions(result(), now) { it }
        val empty = HomeChartData(from = now - 60_000L, to = now, predictions = forecasts)
        assertThat(empty.hasData).isFalse()
        assertThat(empty.trace).isEmpty()
        val readings = listOf(GlucosePoint(now, 100.0))
        assertThat(empty.copy(readings = readings).trace).isEqualTo(readings)
    }
}
