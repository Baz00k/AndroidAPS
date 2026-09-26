# Issue #54: loop frequency with one-minute Libre readings

Investigated 2026-09-26 against fork commit `ad9d4bff0dbe86ef8bf8c2a578924343cc349d82`.
[Issue](https://github.com/Baz00k/AndroidAPS/issues/54).

## Conclusion

**This is a state-copying bug, not an intentional one-minute dosing cadence.** The reported
configuration is YpsoPump with the SMB algorithm and microboluses disabled, with TBRs
changing every minute. The workflow can invoke the loop on every one-minute reading because `AutosensDataStoreObject.clone()` did not copy
`referenceTime`, and both IOB/COB workers replace the live store with that clone. Each
subsequent reading therefore starts a new five-minute grid at its own timestamp. The loop's
already-used-timestamp guard accepts each new minute.

The data store explicitly requires bucket timestamps to remain aligned to `referenceTime`
for correct cache reuse. Discarding that reference during cloning violates this invariant.
Official AAPS guidance also says one-minute Libre readings should not trigger one-minute
calculations (linked below). Tests must include clone-and-replace between readings:
retaining a single store instance hides this bug.

The fix copies `referenceTime` into the clone. The regression test
includes clone-and-replace between readings: it failed before the fix and passes afterward.
This restores grid continuity without adding a timer to the dosing loop. It has not been
deployed or verified against a real pump.

## Execution path and reproduction

1. New glucose history schedules calculation work after a five-second debounce, with
   `EventNewBG` as the cause.
   [IobCobCalculatorPlugin](../plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/IobCobCalculatorPlugin.kt).
2. The main workflow loads glucose, calculates IOB/COB, updates sensitivity, then invokes
   `InvokeLoopWorker`. WorkManager uses `REPLACE`, so slow work can be superseded.
   [CalculationWorkflowImpl](../workflow/src/main/kotlin/app/aaps/workflow/CalculationWorkflowImpl.kt).
3. Loading creates buckets on a five-minute reference grid and applies smoothing.
   [LoadBgDataWorker](../workflow/src/main/kotlin/app/aaps/workflow/LoadBgDataWorker.kt),
   [AutosensDataStoreObject](../plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/data/AutosensDataStoreObject.kt).
4. Both IOB/COB workers call `ads.clone()`, process the copy, then assign it back to
   `iobCobCalculator.ads`. Before the fix, its `referenceTime` reverted to `-1`.
   [IobCobOref1Worker](../workflow/src/main/kotlin/app/aaps/workflow/iob/IobCobOref1Worker.kt),
   [IobCobOrefWorker](../workflow/src/main/kotlin/app/aaps/workflow/iob/IobCobOrefWorker.kt).
5. `actualBg()` returns the latest bucket (if less than nine minutes old), and
   `InvokeLoopWorker` skips timestamps already used. With the reference lost, the next
   incoming reading reanchors the grid and supplies a new bucket timestamp.
   [InvokeLoopWorker](../workflow/src/main/kotlin/app/aaps/workflow/InvokeLoopWorker.kt).

For 15 successive one-minute arrivals, the real bucketer with clone-and-replace produces:

| Lifecycle | Latest bucket offsets in minutes | Distinct timestamps eligible for invocation |
| --- | --- | --- |
| Before fix | `0,1,2,3,4,5,6,7,8,9,10,11,12,13,14` | 15 |
| Preserve reference in clone | `0,0,0,0,0,5,5,5,5,5,10,10,10,10,10` | 3 |

Disabled looping, an invalid profile, busy pump queue, or missing data can still prevent
an APS calculation. Manual actions, temporary-target changes, and temp-basal fallback
invoke the loop outside the BG timestamp guard. Thus preserving the grid does not impose
a global five-minute minimum on every loop invocation.
[LoopPlugin](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt),
[LoopFragment](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopFragment.kt).

## Dosing, pump activity, and resource implications

- **Dosing:** calculation, recommendation, and delivery are separate steps. Basal and bolus
  constraints apply to the result. SMB has its own minimum interval, default three minutes
  (setting range 1–10), checked both in the loop and again when the queued command executes.
  This setting permits delivery when an eligible calculation requests it; it is not an SMB
  timer. Extra loop calls would not imply an SMB on every call, nor establish equivalent dosing.
  [LoopPlugin](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt),
  [IntKey](../core/keys/src/main/kotlin/app/aaps/core/keys/IntKey.kt),
  [CommandSMBBolus](../implementation/src/main/kotlin/app/aaps/implementation/queue/commands/CommandSMBBolus.kt).
- **Pump:** no-action results and sufficiently similar active temporary basals can produce
  successful results without enactment. Changed requests can still issue pump commands.
  Loop frequency alone cannot determine Bluetooth traffic, pump wear, or insulin delivered.
  See `applyTBRRequest()` in [LoopPlugin](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/loop/LoopPlugin.kt).
- **Resources:** one-minute input still triggers glucose loading, bucketing/smoothing,
  IOB/COB processing, and widget updates. Cached autosensitivity entries can skip work;
  graph preparation is gated on UI visibility in this fork. A fivefold raw input rate is
  not evidence of fivefold CPU, battery, or pump activity. Those require device profiling.
  [CalculationWorkflowImpl](../workflow/src/main/kotlin/app/aaps/workflow/CalculationWorkflowImpl.kt),
  [IobCobOref1Worker](../workflow/src/main/kotlin/app/aaps/workflow/iob/IobCobOref1Worker.kt).

## Upstream comparison

Official AAPS documentation explicitly distinguishes one-minute Libre readings from the
less frequent AAPS calculations. [Juggluco settings](https://androidaps.readthedocs.io/en/latest/CompatibleCgms/Juggluco.html#juggluco-to-aaps).

Upstream inspected at commit `598e2eb39c7e15876e4c42876a2162bffcb4fe5f`, app version
3.4.2.6, has the same bucket-timestamp guard **and the same omission of `referenceTime`
in `clone()`**. The fork's dense averaging is therefore not sufficient evidence of a
fork-specific origin. Upstream runtime behavior still requires lifecycle verification;
the documentation alone should not be treated as proof that this bug cannot occur there.
[Upstream worker](https://github.com/nightscout/AndroidAPS/blob/598e2eb39c7e15876e4c42876a2162bffcb4fe5f/workflow/src/main/kotlin/app/aaps/workflow/InvokeLoopWorker.kt),
[upstream data store](https://github.com/nightscout/AndroidAPS/blob/598e2eb39c7e15876e4c42876a2162bffcb4fe5f/plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/data/AutosensDataStoreObject.kt).

Official Libre guidance describes five-minute processing and limitations of direct
one-minute input, with an alternative route through xDrip+ smoothing. This does not
establish a need to throttle this worker, nor clinically validate this fork's averaging.
[Libre 3 guidance](https://androidaps.readthedocs.io/en/latest/CompatibleCgms/Libre3.html#method-1-use-1-minute-readings-directly).

## Verification and follow-up

The corrected `AutosensDataStoreDenseTest` feeds successive one-minute raw series through
the real bucketer and clones/replaces the store after each calculation. Before the fix,
the five-minute expectation failed with 15 distinct timestamps. Copying only
`referenceTime` makes the same test pass. `InvokeLoopWorkerTest` independently verifies
that 15 one-minute events sharing three bucket timestamps produce three loop calls.
It mocks the data store and loop and does not verify pump enactment.

```sh
./gradlew :plugins:main:testFullDebugUnitTest --tests 'app.aaps.plugins.main.iob.AutosensDataStore*' :workflow:testFullDebugUnitTest --tests 'app.aaps.workflow.InvokeLoopWorkerTest'
```

Result: 19 glucose-store tests pass, including existing ordinary five-minute and irregular
bucketing coverage; the unchanged worker test also passes (Gradle reused its passing result).

Follow-up: review the local fix as a dosing-cadence change and validate against a device
trace. Correlate raw timestamps, bucket timestamps, `invoke from` initiators, APS results,
and actual TBR enactments before/after. The reproduction explains eligibility for
minute-by-minute TBR changes, but does not replay this user's specific dosing decisions.
The clone also omits `lastUsed5minCalculation`; audit that separately for cache invalidation
on source-cadence changes. Profile remaining per-reading background work separately if
resource use is a concern.
