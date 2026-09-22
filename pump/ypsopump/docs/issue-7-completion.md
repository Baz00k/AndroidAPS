# Issue 7 completion assessment — 2026-09-22

Implementation candidate: `d0c9cb46e2` on `issue-7-durable-immediate-bolus`.
Issue: https://github.com/Baz00k/AndroidAPS/issues/7

## Outcome and acceptance

The operator reports the final acceptance pass successful for delivery, normal cancellation,
extended cancellation, truthful accounting, and removal surviving history replay. Earlier captured
evidence establishes that the stuck cursor was a running square bolus rewritten in place, that
history subsequently reached Applied, and that a duplicate 2 U provisional / 0.54 U history dose
was repaired to a single 0.54 U treatment (operator confirmed).

Square cancellation experiments requested 0.5 U / 15 minutes twice. Pump events 48166 and 48167
reported 0.08 U with terminal elapsed-minute fields 0 and 2, respectively. Recorded windows were
7852 ms and 108265 ms. Each reconciled approximately 13 seconds after cancellation completed.
These observations establish quantized elapsed duration, not exact pulse timestamps or rounding.

## Issue checklist assessment

| Criterion | Evidence | Disposition |
|---|---|---|
| Journal/counter/dispatch crash handling; no automatic repeat of possibly applied insulin | `YpsoBolusAttemptJournalTest`, `YpsoBolusAttemptFileStoreTest`, `YpsoWriteAccountingTest`, `ProductionPumpSessionTest`, `SessionJournalTest` | Software evidence present; do not equate injected boundary tests with every physical failure being exercised. |
| Distinguish lost ACK, stale/manual/same-size delivery, partial/cancelled and invalid history | Request validator, immediate/extended reconcilers, preflight and coordinator tests; target fixtures in `BolusCommandTest` and `YpsoBolusNotificationTest` | Implemented and tested; supported physical delivery/cancellation accepted by operator. |
| One physical dose, stable identity, amount/time correction and DB failures | `YpsoHistoryIngestionTest`, `SyncBolusWithTempIdTransactionTest`, terminal-window tests; operator confirmed historical duplicate merge | Observed defect repaired. Extended history is minute-resolution and cannot reconstruct pulse timing. |
| Cancellation before/during/after delivery and timeout/restart | Durable cancellation state, bounded controller handling, journal tests, documented target runs in `status-protocol.md`, operator final acceptance | Software plus supported target evidence present; uncertainty remains explicit where final evidence is unavailable. |
| Independent fixtures, target comparisons and independent review | Existing fixture/bench evidence and historical `issue-7-opus-safety-review.md` | Final exact-candidate independent review remains outstanding. The earlier review predates substantial fixes and contains OPEN findings; it is not final approval. |

## Written scope differences requiring disposition

- The issue/reference and parent route exclude extended/mixed enactment. Later operator-directed work
  includes extended delivery and cancellation and combination protocol/accounting support. Record
  that scope expansion explicitly before closing against the original specification.
- The issue requires unresolved insulin to inhibit automated delivery. Later operator direction
  rejected time-based/stranded-write therapy blocks and required pump-status authority, conservative
  provisional accounting and visible uncertainty. This is a changed policy, not proof that the original
  inhibition requirement was met.
- The conversation's broader recovery goal covers overwritten cursors and sequence resets. Normal
  moving-ring/reconnect/timeout recovery was repaired; universal recovery from those other states
  has not been demonstrated and must not be implied by this acceptance.

## Fresh software verification

Command (Linux, repository Gradle wrapper):

```sh
./gradlew :pump:ypsopump:testFullDebugUnitTest :database:impl:testFullDebugUnitTest :implementation:testFullDebugUnitTest :pump:ypsopump:lintFullDebug --no-parallel --console=plain
```

JUnit XML reports under each module's `build/test-results/testFullDebugUnitTest`:

| Module | Tests reported | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| pump/ypsopump | 510 | 0 | 0 | 0 |
| database/impl | 354 | 0 | 0 | 0 |
| implementation | 200 | 0 | 0 | 0 |

Build succeeded; Ypso lint passed. These are JVM tests, including mocked Android/GATT and DAO
boundaries; they are not on-phone database integration tests. Earlier APK builds and operator
acceptance are reported separately. The operator's local `YpsoPumpConst.kt` change is excluded
from new commits; the branch already contains historical changes to this file relative to main.
An exact committed-artifact hardware claim cannot be inferred from the local build configuration.

## Closure recommendation

Open the PR for review and record successful operator acceptance. Keep issue 7 open pending
exact-candidate independent review and explicit disposition of the scope/policy differences above.
Do not automatically close the issue on merge until its evidence contract is satisfied.
