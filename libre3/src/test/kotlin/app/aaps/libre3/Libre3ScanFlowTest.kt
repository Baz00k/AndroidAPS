package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The scan flow guards the one irreversible action in the whole system. These tests exist to
 * make sure it cannot be reached by accident, and that the user is told the actual consequence
 * before it happens.
 */
class Libre3ScanFlowTest {

    private fun flow() = Libre3ScanFlow(warmupMinutes = 60)

    @Test
    fun `scanning is impossible without confirming first`() {
        val f = flow()
        // mayScan is the single gate on an irreversible NFC write.
        assertThat(f.mayScan).isFalse()
        f.begin(Libre3ScanFlow.Current.None)
        assertThat(f.mayScan).isFalse()          // Confirm shown, not yet accepted
        f.confirm()
        assertThat(f.mayScan).isTrue()
    }

    @Test
    fun `out of order calls fail rather than silently advancing`() {
        val f = flow()
        assertThat(f.confirm()).isInstanceOf(Libre3ScanFlow.Step.Failed::class.java)
        assertThat(f.scanned(false)).isInstanceOf(Libre3ScanFlow.Step.Failed::class.java)
        assertThat(f.firstReadingReceived()).isInstanceOf(Libre3ScanFlow.Step.Failed::class.java)
    }

    @Test
    fun `scanned cannot be called twice — the irreversible step runs once`() {
        val f = flow()
        f.begin(Libre3ScanFlow.Current.None)
        f.confirm()
        assertThat(f.scanned(false)).isInstanceOf(Libre3ScanFlow.Step.WarmUp::class.java)
        assertThat(f.scanned(false)).isInstanceOf(Libre3ScanFlow.Step.Failed::class.java)
        assertThat(f.mayScan).isFalse()
    }

    @Test
    fun `with no sensor running the warning names the real consequence`() {
        val step = flow().begin(Libre3ScanFlow.Current.None) as Libre3ScanFlow.Step.Confirm
        assertThat(step.overlapping).isFalse()
        assertThat(step.consequence).contains("60 minutes")
        assertThat(step.consequence).contains("insulin on board")
        // not a content-free prompt
        assertThat(step.consequence.lowercase()).doesNotContain("are you sure")
    }

    @Test
    fun `with a sensor still running overlap is offered and recommended`() {
        val step = flow().begin(
            Libre3ScanFlow.Current.Running(remainingMinutes = 18 * 60)
        ) as Libre3ScanFlow.Step.Confirm
        assertThat(step.overlapping).isTrue()
        assertThat(step.consequence).contains("18h 0m left")
        assertThat(step.consequence).contains("no gap")
        assertThat(step.consequence).contains("Recommended")
    }

    @Test
    fun `activate and takeover are carried through distinctly`() {
        val a = flow().begin(Libre3ScanFlow.Current.None, Libre3ScanFlow.Operation.ACTIVATE)
        val t = flow().begin(Libre3ScanFlow.Current.None, Libre3ScanFlow.Operation.TAKEOVER)
        assertThat((a as Libre3ScanFlow.Step.Confirm).operation).isEqualTo(Libre3ScanFlow.Operation.ACTIVATE)
        assertThat((t as Libre3ScanFlow.Step.Confirm).operation).isEqualTo(Libre3ScanFlow.Operation.TAKEOVER)

        val f = flow()
        f.begin(Libre3ScanFlow.Current.None, Libre3ScanFlow.Operation.TAKEOVER)
        // the operation must survive into the scan step — the two are not interchangeable and
        // ACTIVATE on an already-running sensor burns it
        assertThat((f.confirm() as Libre3ScanFlow.Step.Scan).operation)
            .isEqualTo(Libre3ScanFlow.Operation.TAKEOVER)
    }

    @Test
    fun `overlap keeps the old sensor feeding through warm up`() {
        val f = flow()
        f.begin(Libre3ScanFlow.Current.Running(18 * 60))
        f.confirm()
        val warm = f.scanned(oldSensorStillFeeding = true) as Libre3ScanFlow.Step.WarmUp
        assertThat(warm.oldSensorStillFeeding).isTrue()
        assertThat(warm.minutesRemaining).isEqualTo(60)
    }

    @Test
    fun `handover is triggered by the first reading, not by the clock`() {
        val f = flow()
        f.begin(Libre3ScanFlow.Current.Running(60))
        f.confirm()
        f.scanned(true)
        // A reading arriving early ends warm-up; waiting on the timer alone would delay handover.
        assertThat(f.firstReadingReceived()).isEqualTo(Libre3ScanFlow.Step.Handover)
    }

    @Test
    fun `warm up counting to zero also reaches handover`() {
        val f = flow()
        f.begin(Libre3ScanFlow.Current.None)
        f.confirm()
        f.scanned(false)
        assertThat(f.warmUpTick(30)).isInstanceOf(Libre3ScanFlow.Step.WarmUp::class.java)
        assertThat(f.warmUpTick(0)).isEqualTo(Libre3ScanFlow.Step.Handover)
    }

    @Test
    fun `the flow is resumable — warm up state survives being re-entered`() {
        // Closing the app mid warm-up must not lose the flow, which is why warmUpTick takes the
        // remaining time from outside rather than holding a timer.
        val f = flow()
        f.begin(Libre3ScanFlow.Current.None)
        f.confirm()
        f.scanned(false)
        val resumed = f.warmUpTick(12) as Libre3ScanFlow.Step.WarmUp
        assertThat(resumed.minutesRemaining).isEqualTo(12)
    }

    @Test
    fun `reset clears the gate`() {
        val f = flow()
        f.begin(Libre3ScanFlow.Current.None)
        f.confirm()
        assertThat(f.mayScan).isTrue()
        f.reset()
        assertThat(f.mayScan).isFalse()
        assertThat(f.step).isNull()
    }
}
