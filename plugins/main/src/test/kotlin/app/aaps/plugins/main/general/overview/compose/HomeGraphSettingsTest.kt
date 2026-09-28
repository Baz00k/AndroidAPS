package app.aaps.plugins.main.general.overview.compose

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HomeGraphSettingsTest {
    private val now = 1_000_000L
    private val data = HomeChartData(
        from = 0, now = now,
        readings = listOf(GlucosePoint(now, 100.0)),
        targets = listOf(GlucosePoint(0, 140.0), GlucosePoint(now, 140.0)),
        predictions = listOf(
            ChartPrediction(PredictionKind.IOB, listOf(GlucosePoint(now + 300_000, 80.0))),
            ChartPrediction(PredictionKind.COB, listOf(GlucosePoint(now + 900_000, 300.0)))
        ),
        basal = listOf(BasalStep(0, 0.5, 0.5), BasalStep(now, 0.5, 0.5)),
        treatments = listOf(ChartTreatment(now, 10.0, TreatmentKind.CARBS)),
        lowMark = 72.0, highMark = 180.0,
        additional = AdditionalGraphData(mapOf(AdditionalSeries.IOB to listOf(GlucosePoint(now, 1.0))))
    )

    @Test fun `defaults preserve existing layers but new overlays and panels are opt in`() {
        val settings = HomeGraphSettings.decode("")
        assertFalse(settings.visible(GlucoseOverlay.TARGET))
        assertTrue(settings.visible(GlucoseOverlay.BASAL))
        assertTrue(settings.visible(GlucoseOverlay.TREATMENTS))
        assertTrue(settings.visible(GlucoseOverlay.RAW_READINGS))
        assertTrue(settings.forecasts.isEmpty())
        AdditionalSeries.entries.forEach { assertEquals(0, AdditionalGraphSettings.decode("").graph(it)) }
    }

    @Test fun `every overlay and forecast survives serialization independently`() {
        var settings = HomeGraphSettings()
        GlucoseOverlay.entries.forEach { settings = settings.withOverlay(it, !it.defaultVisible) }
        settings = settings.withForecast(PredictionKind.COB, true).withForecast(PredictionKind.ZT, true)
        assertEquals(settings, HomeGraphSettings.decode(settings.encode()))
        assertEquals(setOf(PredictionKind.ZT), settings.withForecast(PredictionKind.COB, false).forecasts)
    }

    @Test fun `invalid and unknown values do not enable optional lines`() {
        val settings = HomeGraphSettings.decode("TARGET=yes,PREDICTION_IOB=1,UNKNOWN=true,BASAL=false,RAW_READINGS=true=oops")
        assertFalse(settings.visible(GlucoseOverlay.TARGET))
        assertFalse(settings.visible(GlucoseOverlay.BASAL))
        assertTrue(settings.visible(GlucoseOverlay.RAW_READINGS))
        assertTrue(settings.forecasts.isEmpty())
    }

    @Test fun `only selected forecasts are drawn and toggling never moves the sample or history cutoff`() {
        val settings = HomeGraphSettings().withForecast(PredictionKind.IOB, true).withOverlay(GlucoseOverlay.TARGET, true)
        val plotted = data.forDisplay(settings)
        assertEquals(listOf(PredictionKind.IOB), plotted.predictions.map { it.kind })
        assertEquals(data.targets, plotted.targets)
        assertEquals(data.basal, plotted.basal)
        assertEquals(data.additional, plotted.additional)
        assertEquals(now, plotted.targets.last().time)
        assertEquals(now, plotted.basal.last().time)
        // The visible window comes from ChartWindow, never from the forecasts in the snapshot.
        assertEquals(data.from, plotted.from)
        assertEquals(now, plotted.now)
        assertEquals(now, data.forDisplay(HomeGraphSettings()).now)
        assertEquals(2, data.predictions.size) // Original snapshot is never mutated by toggling.
    }

    @Test fun `forecast gaps and timestamps survive filtering untouched`() {
        val gappy = ChartPrediction(
            PredictionKind.UAM,
            listOf(GlucosePoint(now + 300_000, 100.0), GlucosePoint(now + 900_000, 110.0), GlucosePoint(now + 1_200_000, 120.0))
        )
        val plotted = data.copy(predictions = listOf(gappy)).forDisplay(HomeGraphSettings().withForecast(PredictionKind.UAM, true))
        assertEquals(gappy.points, plotted.predictions.single().points)
    }

    @Test fun `hiding basal also hides the scheduled reference, which lives in the same samples`() {
        val plotted = data.forDisplay(HomeGraphSettings().withOverlay(GlucoseOverlay.BASAL, false))
        assertTrue(plotted.basal.isEmpty())
        assertEquals(data.basal, data.forDisplay(HomeGraphSettings()).basal)
    }

    @Test fun `hidden layers cannot change glucose data thresholds or axis scale`() {
        var settings = HomeGraphSettings()
        GlucoseOverlay.entries.forEach { settings = settings.withOverlay(it, false) }
        val plotted = data.forDisplay(settings)
        assertTrue(plotted.targets.isEmpty())
        assertTrue(plotted.predictions.isEmpty())
        assertTrue(plotted.basal.isEmpty())
        assertTrue(plotted.treatments.isEmpty())
        assertEquals(data.readings, plotted.readings)
        assertEquals(data.trace, plotted.trace)
        assertEquals(data.lowMark, plotted.lowMark)
        assertEquals(data.highMark, plotted.highMark)
        assertEquals(182.0, plotted.glucoseBounds().second)
        assertTrue(data.forDisplay(settings.withForecast(PredictionKind.COB, true)).glucoseBounds().second > 300.0)
    }

    @Test fun `axis includes visible targets traces and predictions together`() {
        val source = data.copy(targets = listOf(GlucosePoint(now, 350.0)), bucketed = listOf(GlucosePoint(0, 40.0), GlucosePoint(now, 50.0)))
        val (low, high) = source.forDisplay(HomeGraphSettings().withOverlay(GlucoseOverlay.TARGET, true).withForecast(PredictionKind.COB, true)).glucoseBounds()
        assertTrue(low < 40.0)
        assertTrue(high > 350.0)
        val noForecasts = data.copy(predictions = emptyList())
        assertEquals(noForecasts.now, noForecasts.forDisplay(HomeGraphSettings().withForecast(PredictionKind.IOB, true)).now)
    }
}
