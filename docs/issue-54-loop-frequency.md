# Issue #54: loop frequency with one-minute Libre readings

Investigated 2026-09-26 against fork commit `ad9d4bff0dbe86ef8bf8c2a578924343cc349d82`.
Upstream status re-checked 2026-10-05 against `nightscout/AndroidAPS` `dev`
at `acc5668888f656b4994ee7177b47874a73157733`. Local applicability was checked against
PR #71 at `00ed9d5028383018ba3f1cb97c7a881c1dca9645` and fork `main` at
`fa13d6ca5534fb88e0d1e067a6bc0814a7d05e33`; the latter lacks the reference-copy change.
[Fork issue #54](https://github.com/Baz00k/AndroidAPS/issues/54),
[fork PR #71](https://github.com/Baz00k/AndroidAPS/pull/71).

## Conclusion

**Minute-by-minute looping is inherited upstream behavior, not a fork-specific regression.**
The reported configuration is YpsoPump with the SMB algorithm and microboluses disabled.
The mechanism is reproducible: the IOB/COB workers replace the live glucose store with a
clone that drops `referenceTime`, so every new minute can reanchor the bucket grid and pass
the loop's timestamp gate. The same lifecycle test against the unmodified upstream 3.4.2.6
data-store source produces 15 distinct timestamps for 15 one-minute arrivals.

This establishes how the behavior occurs, not that one-minute dosing is undesirable.
The original classification as an unambiguous dosing-cadence bug was too strong.

**Update 2026-10-05 — upstream has now made an explicit maintainer decision, and it reframes
this fork's proposal.** On 2026-09-10, closing upstream issue
[#5066](https://github.com/nightscout/AndroidAPS/issues/5066), maintainer MilosKozak wrote:

> On a 1 minute source the loop now runs about every 5 minutes rather than every minute.
> That is the intended behaviour and it is a change
> ([#5066 comment](https://github.com/nightscout/AndroidAPS/issues/5066#issuecomment-5616258545)).

In open issue [#5148](https://github.com/nightscout/AndroidAPS/issues/5148), on 2026-09-30, the
same maintainer added that this development cycle is "bug fixes only, then a release",
structural changes come after on a fresh cycle, and that he intends to move the bucket grid to
absolute time (`epoch mod 5 min`, no anchor) and split it from the deltas
([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5910483437)).
See [Upstream status and maintainer decisions](#upstream-status-and-maintainer-decisions).

Consequences for PR #71:

- Reference copying matches one part of upstream's cache-stability fix, but does not
  establish that the one-line adaptation is sufficient. The original change also included
  five-minute-path re-anchoring and jitter compensation; later commits correct signed
  alignment, startup seeding, fallback seeding, and clone mode state.
- Approximately five-minute BG-triggered cadence is **upstream-intended policy**. It is not
  evidence of dosing equivalence or a decision the fork must automatically adopt.
- Issue #5148 remains **open**. The proposed fresh-glucose series, faster SMB cadence, and
  absolute cache grid are not landed fixes. An absolute grid alone does not make glucose
  status fresh; the separation of consumers is essential.
- Recommended disposition: retain the draft while assessing the reference change with its
  companion fixes and the fork's dense averaging path. Other backports below are proposed
  follow-ups, not newly imposed blocking dependencies for #54.

## Upstream status and maintainer decisions

All claims below are from `nightscout/AndroidAPS` PR/issue/commit pages, cited inline. The
[#5148](https://github.com/nightscout/AndroidAPS/issues/5148) thread is overwhelmingly the
reporter's (JetFoxy) analysis and proposal; the maintainer decision is MilosKozak's. This
section separates what is merged from what is only proposed.

### Cadence policy is now explicit

Two maintainer statements define the policy:

- Five-minute automatic loop on a one-minute source is **intended behaviour** — `#5066`
  closure comment, 2026-09-10
  ([link](https://github.com/nightscout/AndroidAPS/issues/5066#issuecomment-5616258545)).
  The same comment notes the change is "not confirmed in the field yet, only by unit tests
  and measurement", and that the fix is **not on `dev3`** (AAPS 3.4.x), which "will not be
  updated anymore" ([follow-up](https://github.com/nightscout/AndroidAPS/issues/5066#issuecomment-5622337483)).
- The sensor-aligned anchor machinery is going away: "I intend to move the bucket grid to
  absolute time — `epoch mod 5 min`, no anchor at all — and split it from the deltas, which
  come from new BG as loop parameters." The rationale given is that the whole anchor-phase
  bug family, including all four symptoms in `#5148`, share one root, and that a non-moving
  grid is the precondition for ever storing the precalculated past instead of recomputing
  it ([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5910483437)).

So the earlier doc statement that `#5148` "is reporter analysis, not a maintainer decision"
is now outdated: there is a maintainer decision on direction, even though `#5148` itself
stays open for the sub-5-minute case.

### What is merged on upstream `dev`

| Commit | Date | Change | Status |
| --- | --- | --- | --- |
| [`17dd2bbd`](https://github.com/nightscout/AndroidAPS/commit/17dd2bbd8e69f96c3b78808e3078d46d3aff2c9f) | 2026-09-09 | Keeps `referenceTime`, adds >90 s re-anchoring/jitter compensation, changes loop-trigger ownership, publication guards, and observer error handling | merged |
| [`e50cf65`](https://github.com/nightscout/AndroidAPS/commit/e50cf65590f8870b0ba8cac3f0372f04dc52b282) | 2026-09-10 | Prune `autosensDataTable` to the calculation window | merged |
| [`dbd137f`](https://github.com/nightscout/AndroidAPS/commit/dbd137fa9ca3f27bca0b98d7378a158a019cbd27) | 2026-09-10 | Skip recalculation for a glucose change that carries no new data (`holdsSameData`) | merged |
| [`6300e492`](https://github.com/nightscout/AndroidAPS/commit/6300e492582dfe10627702976b3f3cffcca6ee29) | 2026-09-25 | Recalculated-path handling of the newest reading near a grid point | merged |
| [`a9f21843d`](https://github.com/nightscout/AndroidAPS/commit/a9f21843dcd4d85552dd39e0e5384055658d222d) | 2026-09-25 | Seed the grid anchor from the phase the recent readings share, not from whichever reading is newest | merged |
| [`5a2750966`](https://github.com/nightscout/AndroidAPS/commit/5a27509668425d437a255df386ea43d216ea8c16) | 2026-09-29 | Drop an anchor the 5-min pass invented when it falls back to the recalculated path | merged |
| [`ef87883a9`](https://github.com/nightscout/AndroidAPS/commit/ef87883a9b9a1831c2714777e5895664f826bc98) | 2026-09-29 | Fix `adjustToReferenceTime`'s `abs()` sign error for times older than the anchor | merged |
| [`3e62fa91e`](https://github.com/nightscout/AndroidAPS/commit/3e62fa91e14492168ee16bf06ea7e6bfb38f28b3) | 2026-09-29 | Tests pinning how far the newest bucket may lag the newest reading | merged |
| [`ca35760a4`](https://github.com/nightscout/AndroidAPS/commit/ca35760a4e084b514148eb088a99dbd85212a506) | 2026-09-30 | `@Volatile` on `IobCobCalculatorPlugin.ads` | merged |
| [`57d91ee46`](https://github.com/nightscout/AndroidAPS/commit/57d91ee4651fc668cc04f78bfddfb8e89bab1af2) | 2026-09-30 | Read `bgReadings` once per pass, not once per element, in the hot bucketing loops | merged |
| [`ca27227be`](https://github.com/nightscout/AndroidAPS/commit/ca27227be04492ae6004edfad63d935d2ea8e3f8) | 2026-09-30 | Keep `lastUsed5minCalculation` across `clone()` | merged |
| [`b1951f5ac`](https://github.com/nightscout/AndroidAPS/commit/b1951f5acd) | 2026-10-03 | Let a running calculation finish before a history change restarts it (10-minute cap before forcing a stop) | merged |

`a9f21843d`, `5a2750966`, `ef87883a9` and `3e62fa91e` are all anchor-machinery work, which the
maintainer has said will be replaced by the absolute grid. `57d91ee46` and `ca27227be` were
explicitly acknowledged as coming from the reporter's side findings
([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5910483437)).

### Startup vs mid-session phase

The seeding fix (`a9f21843d`) only acts when no anchor has been established yet
(`referenceTime == -1L`). That means:

- **Bad anchor at startup:** the upstream regression for five regular readings plus a late
  newest reading selects the regular phase and preserves real historical samples. The
  newest bucket may still be interpolated and lag behind. This tests bucketing, not an
  end-to-end AutoISF recovery; the reporter's retest is described in
  [this comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5909913896).
- **Mid-session phase shift on the recalculated path:** still open by design.
  `anAnchorAlreadySetIsNotReSeeded` shows that seeding leaves an established anchor alone.
  The maintainer's stated reason: re-anchoring later moves the grid, and `autosensDataTable`
  keys, the COB chain (`activeCarbsList` cloned from the previous bucket) and carb slotting
  all depend on it not moving; re-anchoring "amounts to a cold-start recomputation of the
  whole window" ([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5908517659)).

This is not a claim that every path has an immutable anchor: the five-minute path retains
the >90 s re-anchor added by `17dd2bbd`. The original field log was unavailable for the
retest, so its cause was not resolved. Tests characterize representative cases rather than
proving that the original user's AutoISF symptom is fixed.

### Rejected `filledGap` proposal

While diagnosing AutoISF `bgAcceleration`/`duraISFminutes` starvation, the reporter proposed
changing the `filledGap` criterion so a grid point is flagged only when the two bracketing
readings are far apart, not when the grid point sits off the readings' phase
([#5148 proposal](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5908582449)).
The maintainer **rejected** it:

> On the `filledGap` change: I am not taking it. An interpolated midpoint genuinely is not a
> sample. Feeding it to the parabola fit does not restore AutoISF, it gives it a fabricated
> and systematically flattened acceleration, which is worse than an honest zero — and AutoISF
> acts on it.
> ([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5910483437))

The reporter agreed and withdrew it
([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5910993570)).
The maintainer's stated resolution is to take AutoISF off the grid entirely. That means any
fork backport of the loosened-`filledGap` change would contradict the upstream decision.

### Future absolute-grid plan

The maintainer's plan, stated 2026-09-30
([#5148 comment](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5910483437)):

1. Move the bucket grid to **absolute time** (`epoch mod 5 min`), removing `referenceTime`
   anchoring and the whole startup/seeding/re-anchoring/sign-error family.
2. **Split grid from deltas**: the glucose status / deltas come from a fresh series, separate
   from the cached grid. This is the surviving half of the reporter's `statusData` proposal;
   the phase-alignment code under it will not survive.
3. Sequencing: "This cycle is bug fixes only, then a release. Structural changes come after
   it, on a fresh cycle." The maintainer asked the reporter to stop investing in phase
   alignment.

This is a plan, not merged code. No absolute-grid commit exists in the commits reviewed here.

### PR #5171 status

[PR #5171](https://github.com/nightscout/AndroidAPS/pull/5171) ("Bucket the recalculated path
in one sweep instead of one search per bucket", JetFoxy, opened 2026-09-30) is **closed,
not merged** (closed 2026-09-30T12:22:48Z). The maintainer's
[closure explanation](https://github.com/nightscout/AndroidAPS/pull/5171#issuecomment-5911216013)
calls it a timing decision, "not quality":

- After `57d91ee46` the remaining cost is a few ms per pass — an optimisation, not a fix, and
  the release is bug fixes only.
- `createBucketedDataRecalculated()` is exactly what the absolute-grid rework will rewrite,
  so he would rather take it with that work.
- Review note: the sweep's tie-break for `older` is not identical to `findOlder()` when a tie
  spans the last two indices; harmless today but the comment overstates what was verified.
- Requests for a return: a phone re-measure against current `dev`, and a plan for the now
  production-dead `findNewer()`/`findOlder()`.

The reporter re-measured on a phone (Redmi Note 10S, `app_process`) on 2026-10-01 and removed
the dead production callers by moving the oracle into the test
([PR #5171 comment](https://github.com/nightscout/AndroidAPS/pull/5171#issuecomment-5930514998)).
The maintainer's guidance was to bring it back after the release, so it is **not** a
recommended backport now.

## Execution path and reproduction

*(This section is the original 2026-09-26 fork investigation and is unchanged.)*

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

*Reporter-measured figures (not reproduced here):* the `statusData` proposal reported
runs/day of 288 (SMB off / `dev`), 480 at the default `SMBInterval` 3, and 1440 at
`SMBInterval` 1; cost figures varied across the thread and the maintainer noted they moved
substantially after `57d91ee46`. Those are JVM-unit-test and `app_process` synthetic-stream
measurements on the reporter's devices, not device profiling of this fork.
[Initial proposal](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5892171403),
[updated measurements](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5909913896).

## Upstream comparison

Official AAPS documentation explicitly distinguishes one-minute Libre readings from the
less frequent AAPS calculations. [Juggluco settings](https://androidaps.readthedocs.io/en/latest/CompatibleCgms/Juggluco.html#juggluco-to-aaps).

Upstream stable source inspected at commit `598e2eb39c7e15876e4c42876a2162bffcb4fe5f`,
app version 3.4.2.6, has the same missing reference in `clone()` and the same clone/replace
lifecycle. Substituting that unmodified data-store source into the targeted lifecycle test
reproduced minute-by-minute bucket advancement. This is source-level reproduction, not a
full upstream app/device test.
[Upstream data store](https://github.com/nightscout/AndroidAPS/blob/598e2eb39c7e15876e4c42876a2162bffcb4fe5f/plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/data/AutosensDataStoreObject.kt),
[upstream worker](https://github.com/nightscout/AndroidAPS/blob/598e2eb39c7e15876e4c42876a2162bffcb4fe5f/workflow/src/main/kotlin/app/aaps/workflow/iob/IobCobOref1Worker.kt).

The behavior has been noticed upstream: [issue #4158](https://github.com/nightscout/AndroidAPS/issues/4158)
describes one-minute Libre-triggered TBR changes in AAPS 3.3.1.3, in the context of an
Omnipod Dash delivery issue. Its pump-specific findings do not establish a YpsoPump issue.
[PR #4651](https://github.com/nightscout/AndroidAPS/pull/4651) proposes an optional loop
frequency limit; contributors discuss existing operation at approximately 1,200 loops/day.
Both are evidence of known behavior.

The maintainer now explicitly describes approximately five-minute BG-triggered cadence as
intended. A smaller `SMBInterval` does not itself schedule an earlier calculation; this is
not a universal minimum interval for all invocation paths. Future freshness and cadence
policy under the proposed grid/status split remain implementation work.

The official Juggluco documentation's five-minute implication aligns with the intended
cadence, but the observed behavior on this fork and the `#5148` thread show the automatic
gate did not enforce it until `17dd2bbd`. Other documented Libre paths use xDrip+ to convert
one-minute input into five-minute values, so sensor availability for years does not imply
every AAPS installation has consumed raw one-minute readings.
[Libre setup](https://androidaps.readthedocs.io/en/latest/CompatibleCgms/Libre3.html).

## Backport assessment for this fork

These are **source-reviewed candidates, not validated backports**. The relevant fork code
uses Android `src/main`, WorkManager workers, and JVM monitors; upstream uses KMP source
sets and a different workflow. Apply behavior deliberately rather than cherry-picking the
upstream files wholesale.

### Focused backport candidates

| Priority | Upstream change | Local applicability and limits |
| --- | --- | --- |
| High | [`ca35760a4e`](https://github.com/nightscout/AndroidAPS/commit/ca35760a4e084b514148eb088a99dbd85212a506), volatile store publication | `IobCobCalculatorPlugin.ads` is a plain property, replaced by both IOB/COB workers and read elsewhere. A volatile reference provides publication visibility; it does **not** prevent a cancelled worker from publishing an obsolete clone, make compound operations atomic, or make mutable store contents independently thread-safe. |
| High | [`ca27227be0`](https://github.com/nightscout/AndroidAPS/commit/ca27227be04492ae6004edfad63d935d2ea8e3f8), preserve bucketing mode | `clone()` loses `lastUsed5minCalculation`, defeating the next pass's mode-transition cache check after publication. Restoring it repairs cache-mode history and clean/recalculated diagnostics; it does not itself enable SMB or restore a dosing constraint. |
| Performance | [`57d91ee465`](https://github.com/nightscout/AndroidAPS/commit/57d91ee4651fc668cc04f78bfddfb8e89bab1af2), local reading-list snapshots | Repeated locked property reads occur in this fork too, including its dense-data functions. Hoist the property read without changing interpolation, averaging, timestamp, or gap rules. Retain synchronization and audit all writers. This is not the single-sweep rewrite in #5171. |

Local sources: [data store](../plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/data/AutosensDataStoreObject.kt),
[calculator](../plugins/main/src/main/kotlin/app/aaps/plugins/main/iob/iobCobCalculator/IobCobCalculatorPlugin.kt),
[loader](../workflow/src/main/kotlin/app/aaps/workflow/LoadBgDataWorker.kt),
[oref1 worker](../workflow/src/main/kotlin/app/aaps/workflow/iob/IobCobOref1Worker.kt),
[oref worker](../workflow/src/main/kotlin/app/aaps/workflow/iob/IobCobOrefWorker.kt).
The property getters use `@Synchronized` (the store's monitor), while compound work uses a
separate `dataLock`. Upstream's explanation of re-entering the **same** lock therefore must
not be copied literally. Local snapshots reduce repeated monitor acquisition but are not a
repair of this fork's entire locking model.

The table's own monitor is also used by `reset()`/`newHistoryData()`. Upstream's earlier
[`73699d1095`](https://github.com/nightscout/AndroidAPS/commit/73699d10959f0e57a438457c985b3bf8f20a46ad)
consolidated these locks; that is a separate concurrency-review candidate, not a guarantee
provided by adding volatility. The commit contains unrelated changes, so inspect the
store-locking portion rather than applying it wholesale.

The mode flag is not a complete record of the actual algorithm: five-minute fallback can
leave it `true`, and both dense averaging and irregular interpolation use `false`. Copying
it does not invalidate caches on averaged/interpolated transitions. In local
[BgQualityCheckPlugin](../plugins/constraints/src/main/kotlin/app/aaps/plugins/constraints/bgQualityCheck/BgQualityCheckPlugin.kt),
the direct max-IOB restriction is for independently detected doubled readings, not the
clean/recalculated label.

### Companion review for the reference-persistence proposal

- **Signed grid alignment — `ef87883a9`: directly relevant.** The fork has the exact
  `abs(someTime - referenceTime)` error. The mathematical fix does not require startup
  seeding: factor a non-negative modulo helper and test times before and after the anchor.
  The upstream patch additionally reuses that helper in seeding code absent here.
- **Five-minute phase handling — relevant part of `17dd2bbd`.** Review its >90-second
  re-anchor and `anchorShift` compensation together with cache invalidation/COB history when
  the grid actually changes. A future rewrite is not a substitute for handling this in a
  persisted-anchor implementation deployed now.
- **Startup and fallback seeding — `a9f21843d` plus `5a2750966`.** Adapt together if the
  persisted-anchor approach is retained. Seeding acts on an unset anchor; fallback must
  discard only the anchor invented by that failed pass, not an established one. The
  existing five-minute re-anchor exception still needs explicit treatment.
- **Newest-bucket freshness — `6300e492` and `3e62fa91e`.** The former can retain a newest
  reading at a grid point up to 30 seconds ahead of its actual timestamp, but refuses to
  manufacture a lone-point series. The latter adds lag tests, not production fixes. Review
  both freshness directions against status consumers; do not transplant the 30-second
  tolerance without a fork-specific decision.

**The dense averaging path is a material fork difference.** `createBucketedData()` routes
one-minute data to `createBucketedDataAveraged()` when there are enough readings and the
median positive interval is below 150 seconds. Upstream's recalculated-path changes do not
exercise or repair that path automatically. Its centered averaging windows can change a
bucket's value as later readings arrive while the bucket timestamp remains unchanged.
Reference persistence and cache reuse therefore require a value/invalidation audit, not
only the existing timestamp-cadence test.
Nonempty averaged buckets also default to `filledGap=false`; they are aggregates, so the
upstream discussion of real samples versus interpolated midpoints does not fully describe
local AutoISF input provenance. Re-anchoring with a populated table additionally needs
review of old-phase entries consumed by
[SensitivityOref1Plugin](../plugins/sensitivity/src/main/kotlin/app/aaps/plugins/sensitivity/SensitivityOref1Plugin.kt),
not merely whether a new cache key can be found.

### Defer or investigate separately

- **Raw-only loop gating:** do not restore faster triggering while status/deltas still read
  the older grid. The reporter and maintainer both identify this as an incomplete fix
  ([proposal](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5892171403),
  [response](https://github.com/nightscout/AndroidAPS/issues/5148#issuecomment-5908517659)).
  Locally, a same-timestamp averaged value can evolve, so "unchanged BG" is not universally
  true either. Fresh raw arrival, bucket timestamp, bucket value, and complete dosing
  inputs are distinct; changing one gate does not establish equivalent dosing.
- **`filledGap` loosening:** rejected upstream. Preserve the distinction between real and
  interpolated samples; no backport of that proposal is recommended.
- **`statusData`/absolute grid:** future architecture, not released code to backport.
- **PR #5171:** closed unmerged; optional optimization requiring fork-specific output and
  duplicate-timestamp regressions. Its upstream removal of `findNewer`/`findOlder` is not
  directly applicable because the fork's dense-gap path also calls them.
- **Workflow reliability:** the lost-cause trigger and superseded-publication changes in
  `17dd2bbd`, cache pruning in `e50cf65`, no-op glucose filtering in `dbd137f`, and scheduler
  change [`b1951f5acd`](https://github.com/nightscout/AndroidAPS/commit/b1951f5acde0b50b24fb06da1a02bb97d3cb6c09)
  deserve a separate local workflow audit. In particular, do not simply remove
  `InvokeLoopWorker`'s `EventNewBG` cause gate: preserve main-versus-history isolation,
  cancellation ownership, freshness, and duplicate-run prevention. The upstream scheduler
  now lets an active calculation finish before applying merged changes, subject to a cap;
  that changes when treatment/history updates take effect and needs its own validation.

## Verification and follow-up

*(Original fork reproduction. These are the fork's own JVM unit tests, not upstream and not
device tests.)*

The corrected `AutosensDataStoreDenseTest` feeds successive one-minute raw series through
the real bucketer and clones/replaces the store after each calculation. Before the fix,
the five-minute expectation failed with 15 distinct timestamps. Copying only
`referenceTime` makes the same test pass. `InvokeLoopWorkerTest` independently verifies
that 15 one-minute events sharing three bucket timestamps produce three loop calls.
It mocks the data store and loop and does not verify pump enactment.

```sh
./gradlew :plugins:main:testFullDebugUnitTest --tests 'app.aaps.plugins.main.iob.AutosensDataStore*' :workflow:testFullDebugUnitTest --tests 'app.aaps.workflow.InvokeLoopWorkerTest'
```

Historical result (2026-09-26): 19 glucose-store tests passed, including existing ordinary
five-minute and irregular bucketing coverage; the unchanged worker test also passed
(Gradle reused its passing result). These were not re-run for this documentation update.

Follow-up:

1. Decide the scope of a coherent reference/cache fix for this fork. Existing tests establish
   timestamps and eligibility, not glucose values, cached IOB/COB equivalence, constraints,
   or delivered insulin.
2. For a subsequent implementation, cover clone/publish mode transitions, signed modulo,
   late startup readings, fallback seeding, phase shifts, freshness boundaries, dense-slot
   value evolution, real gaps, and duplicate timestamps. Verify cache invalidation and
   glucose-status output in addition to the bucket timestamps. Include the 30/90-second
   boundaries, dense classification thresholds, and populated COB across phase changes.
3. Exercise changed dosing behavior in simulation/replay through status, IOB/COB, constraints,
   loop results, and queued delivery decisions, including stale/missing data and cancelled
   calculations. The reported SMB-disabled setup does not validate SMB-enabled operation.
   Both glucose-status calculators reject buckets older than seven minutes, while
   `actualBg()` permits nine; test the resulting boundary mismatch. Cover the fork's
   [Hovorka MPC](../plugins/aps/src/main/kotlin/app/aaps/plugins/aps/hovorka/HovorkaMpcPlugin.kt)
   as well, which consumes SMB glucose status.
4. Keep resource claims separate from correctness: upstream phone/synthetic results are not
   this fork's battery, end-to-end APS, or pump-command measurements.

This update changes documentation only; no new backport, device test, pump validation, or
reproduction of the reporter's benchmarks was performed. Two independent high-capability
source reviews checked local applicability and challenged the initial recommendations;
their findings are incorporated above. This is review evidence, not execution validation.
