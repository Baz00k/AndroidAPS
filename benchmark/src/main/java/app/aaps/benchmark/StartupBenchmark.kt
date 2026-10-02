package app.aaps.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold launch of an unconfigured install or a configured virtual-pump fixture.
 * Keep permission state and compilation mode explicit; emulator timing is harness evidence, not a hardware baseline.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "info.nightscout.androidaps",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }
}
