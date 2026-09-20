# Issue 7: Opus safety review

> **Status:** Software remediation implemented and locally verified; still not approved
> for real therapy pending independent safety re-review and hardware acceptance.
>
> **Source:** Extracted in four sequential responses from the original resumed Opus
> review session `ses_f44538f79ffeYEL2lq4Ab0YIyo`. Outputs from two accidentally
> created fresh sessions were cancelled and are not used here.
>
> This record preserves the complete finding inventory, the corrections Opus made
> while re-reading the code, the cross-cutting issues, test gaps, verified invariants,
> remediation order, and therapy acceptance criteria. Exact line numbers refer to the
> uncommitted working tree reviewed on 2026-09-20 and will drift as fixes are applied.

## Remediation tracking

The original findings below are retained as the review record. This table is the live
implementation ledger; a finding is not closed until its regression evidence is recorded.

| ID | Validation | Status | Fix commit | Evidence |
|---|---|---|---|---|
| Gate | Confirmed | Fixed | `5fe8765ba9` | `READ_ONLY_MODE` restored to `true` |
| S1 | Confirmed | Fixed | `6ce1521334`, `a206ff0757` | Stable `pumpId` read-back verifies inserted or updated terminal rows |
| S2 | Confirmed | Fixed | `a206ff0757` | Extended deadline = duration + 90 s; epoch change becomes durable warning |
| S3 | Confirmed | Fixed | `a206ff0757` | Split dose inhibition/reconciliation/row holdback; backwards-clock regression passes |
| S4 | Confirmed | Fixed | Restored reviewed baseline | Independent selector identity proof retained; dropped write cannot be proven by auto-advancing event value |
| S5 | Confirmed | Fixed | `ba3435d492` | Stop retries until dispatch/pending; history yields locally; BLE watchdog moved off main looper |
| S6 | Confirmed | Fixed | `a206ff0757` | Authoritative terminal amount publishes final progress |
| S7 | Confirmed | Fixed | `a206ff0757` | Live PumpSync row without durable identity now fails explicitly |
| S8 | Confirmed | Fixed | `a206ff0757` | Synchronized warning lifecycle dismisses, raises, clears, and can re-raise |
| S9 | Confirmed | Fixed | `a206ff0757` | Background history removed from queue `isBusy` |
| S10 | Confirmed | Fixed | `a206ff0757` | Queued therapy can repeatedly bypass transport-only backoff; rekey remains blocking |
| S11 | Confirmed | Fixed | `a206ff0757` | Post-dispatch identity loss is enacted uncertainty; confirmed partial is successful enacted result |
| S12 | Confirmed | Fixed | `a206ff0757` | Removed redundant post-terminal stop transaction |
| S13.1 | Confirmed | Fixed | `e14a906696` | Alarm runs before UI work; refresh is main-thread and binding-guarded |
| S13.2 | Confirmed | Fixed | `e14a906696` | Confirmation latch resets on pause and view destruction |
| S13.3 | Confirmed | Fixed | `e14a906696` | Pending state localized; queue and confirmation guards are visible/disabled |
| S13.4 | Confirmed | Fixed | `e14a906696` | Pending cancel deduplicated and tested; queued set is explicitly superseded |
| S14 | Confirmed naming/test gap | Fixed | `b5b0b5fea8` | Presentation renamed; paired GATT 129 and pump 140 tests cover retry vs rekey |
| S15 | Confirmed | Fixed | `a206ff0757`, `b5b0b5fea8` | Unsafe-contract tests rewritten; 407 YpsoPump tests pass |

### Local verification after remediation

- `:pump:ypsopump:testFullDebugUnitTest`: **407 tests, 0 failures, 0 errors**.
- `:pump:ypsopump:lintFullDebug`: passed in the combined module verification run.
- `:implementation:testFullDebugUnitTest --tests app.aaps.implementation.queue.CommandQueueImplementationTest`: passed.
- `:plugins:main:compileFullDebugKotlin`: passed.
- `:plugins:main:lintFullDebug`: analysis reached `lintAnalyzeFullDebug` twice but exceeded the
  local 2-minute and 5-minute command limits without reporting an error; a completed lint result
  is still required before therapy approval.
- `git diff --check`: passed.

## Finding inventory

### S1 — Terminal extended-bolus reconciliation can never confirm

**Classification: Confirmed, critical.**

`YpsoPumpPlugin.reconcileBolusAttempt` interprets
`PumpSync.syncExtendedBolusWithPumpId()` as an accounting-success boolean. The core
implementation returns `result.inserted.isNotEmpty()`. Terminal reconciliation operates
on the row already inserted by `deliverExtended`, so the transaction takes its update
branch and returns false even when the database update succeeded. The fallback,
`extendedAccountingMatches`, asks `expectedPumpState().extendedBolus`, which only returns
an extended bolus active at `now`; the terminal update just ended the row in the past.
Thus both checks fail and `markUnresolved` returns before `confirmTerminal` for every
normal or partial extended bolus.

Consequences include a false urgent uncertainty warning on every square bolus, 90-second
cancel waits even after a clean stop, no attempt-level terminal identity, and loss of the
SMB/NORMAL type mapping. Generic history ingestion may still account the insulin, but the
durable attempt lifecycle remains wrong.

**Fix:** Verify the persisted row directly by stable `pumpId` and expected timestamp,
amount, and duration. Do not use insert-vs-update transaction shape or the currently-active
row as the oracle. Cover completed, partial, already-correct, and genuinely-absent rows.

### S2 — Extended attempts can permanently block therapy after identity epoch changes

**Classification: Confirmed, critical.**

`reconcileBolusAttempt` silently returns if serial, session generation, or pump reboot no
longer matches. A routine battery change increments the reboot counter and a rekey changes
the generation. The restart expiry deliberately excludes extended attempts, so a
`DELIVERING` or `ACCEPTED_UNVERIFIED` record remains inhibitory forever. Once its database
duration elapses, the Actions cancel affordance disappears; if initial accounting failed,
it may never appear. New immediate and extended doses then fail indefinitely.

**Fix:** Bound extended inhibition to `dispatchedAt + programmed duration + reconciliation
margin`; convert evidence-epoch changes to an explicit durable warning instead of silently
returning; and provide an operator-visible recovery action independent of a live PumpSync
row. Do not expire an extended attempt while its programmed delivery window remains open.

### S3 — Non-blocking `UNRESOLVED` permits dose stacking during active uncertainty

**Classification: Confirmed, critical.**

`UNRESOLVED` was removed from `inhibitsAutomatedDelivery`, but the controller writes that
state not only for old terminal/accounting uncertainty. It also writes it when identity
polling fails after an acknowledged write, when the connection changes around dispatch,
when a pump reports an unexpected programmed dose, and when a cancellation is only
possibly applied. In those cases insulin may still be actively delivering. A later loop
cycle can dispatch another dose.

Opus corrected its first timing claim: eight polling iterations are not simply 1.2 seconds,
because each BLE status read can itself wait up to 15 seconds. The safety defect remains:
these transitions can occur far before the intended 90-second observation deadline.

The same predicate is overloaded for three meanings: permission to start another dose,
whether an attempt should still be reconciled, and whether terminal history should be held
for identity attribution. Excluding `UNRESOLVED` therefore also prevents a later terminal
row from upgrading the attempt and clearing the warning.

**Fix:** Split the predicates into `inhibitsNewDose(now)`, `awaitsReconciliation`, and
`holdsTerminalRow`. Active uncertainty remains inhibitory for a bounded window; terminal
or accounting uncertainty becomes a durable non-blocking warning only after that window.
Handle backwards wall-clock changes explicitly. Rewrite the contradictory “Do not retry”
text together with the state model.

### S4 — The replacement history-selector proof is not independent

**Classification: High risk; logic confirmed against documented pump behavior, hardware
rejection consequences not fully characterized.**

The changed implementation pre-reads encrypted `CHAR_EVENT_VALUE` and uses the embedded
index after a selector write as acceptance proof. Repository protocol evidence says reading
event value advances the persistent selector. If the requested index is exactly one after
the pre-read index, the post-write read can return the requested row even when the selector
write was dropped. The code can then mark the reserved write counter accepted, desynchronize
the durable high-water mark, and expose the next bolus write to pump error 139.

A full scan can also leave the selector past the occupied range. The final pre-read may be
rejected until the selector is repositioned, causing an otherwise complete snapshot to be
discarded.

Opus withdrew two earlier claims after re-reading: sequential scanning does not force a
selector write for every row, and the broad battery/latency claim based on that premise was
wrong. The independent-proof defect remains.

**Fix:** Keep the encrypted row's embedded index for value binding, but restore an
independent acceptance observable. Read `EVENT_INDEX_UUID` with a short timeout and classify
timeout/failure as unknown rather than accepted. If that characteristic is unusable, never
claim acceptance solely from the auto-advancing value read. Handle out-of-range re-anchoring
without discarding the completed scan.

### S5 — Stop can be silently dropped and never retried

**Classification: Confirmed cancellation defect; main-looper watchdog is a risk.**

Opus corrected the original latency framing: `stopBolusDelivering` invokes `requestStop`
immediately on a separate thread. The confirmed defect is silent loss. If a history
multiframe read owns the shared cursor, `readBolusStatus(owner)` returns null and
`requestStop()` exits without dispatching. The terminal loop retries once, but sets its
`cancellationSignalled` latch whether or not cancellation was actually dispatched. A second
collision, transient non-delivering status, or missing connection key loses Stop for the
rest of the observation window.

Foreground terminal history reads do not receive `stopWhen` or an externally stored attempt,
so the background cancellation mechanism cannot preempt them. The BLE operation watchdog
and write continuations run on the main looper; UI load can delay the component intended to
detect a stalled BLE operation. Source alone does not prove this caused the observed 20-second
stall, so Opus classified that part as risk.

**Fix:** Retry until a durable `cancelRequestId` exists or the attempt is terminal; return a
distinct status from `requestStop` instead of silently returning; make foreground history
reads Stop-aware; move timeouts to a dedicated scheduler. Tests must contend with an active
multiframe owner and verify a pump cancel write actually dispatched.

### S6 — Immediate bolus progress can freeze at 10%

**Classification: Confirmed.**

Terminal status clears the fast sequence and programmed amount. The in-loop progress gate
requires both to match the proven command, so it closes at completion. The terminal return
does not publish final progress. A 0.10 U dose may complete before the first 250 ms poll;
the only sample can be the 0.01 U identity observation, yielding exactly the reported 10%.

**Fix:** Publish the authoritative terminal delivered amount immediately before returning.
Emit 100% only for full completion; emit the true fraction for partial delivery. Preserve
monotonicity and the requested-dose ceiling.

### S7 — Extended cancel can claim success without cancelling the pump

**Classification: Confirmed, critical.**

`cancelExtendedBolus` returns `success(true).enacted(false)` when the durable attempt is
absent, immediate-shaped, or non-inhibiting. The UI's belief that an extended bolus is active
comes from the database, not the attempt journal. Those sources diverge after sync rejection,
`UNRESOLVED`, reboot/rekey, or process failure. The operator can therefore receive a success
result although no cancel write was dispatched and no database stop was recorded.

**Fix:** If PumpSync still has a live extended row, never return no-op success solely from
the attempt state. Use durable slow-sequence identity to attempt an exact pump-side cancel,
or fail explicitly and direct the operator to inspect the pump. Exact status matching already
makes a stale cancel degrade safely without writing.

### S8 — The urgent uncertainty notification never clears or reliably re-raises

**Classification: Confirmed notification lifecycle defect and data race.**

The publisher updates an in-memory boolean but emits no dismiss event on the falling edge.
An urgent warning can persist after resolution; if the user dismisses it manually, a later
genuine uncertainty can be swallowed because the boolean remains true. The field is neither
volatile nor lock-guarded and is accessed from queue, background recovery, and lifecycle
threads.

**Fix:** Mirror the profile notification lifecycle: synchronize publication, dismiss on the
clearing edge and before replacing text, deduplicate by message/cause rather than a boolean,
and reset persisted notification state on startup before re-evaluating.

### S9 — Background history still participates in queue `isBusy`

**Classification: Confirmed race/yield defect, narrower than first stated.**

Opus corrected the general 15–30 second claim: ingestion is skipped when a command was
already queued at the check. The remaining race is that a command can arrive just after
recovery passes the yield check and enters ingestion. No later step checks yield while
`historyRecoveryActive` keeps `isBusy()` true. With an inhibitory attempt, one or two
15-second bolus-status waits can then defer therapy.

Yield cancellation also disconnects a healthy link and logs a false timeout, forcing the
queued bolus through reconnect. Background unowned status reads can collide with foreground
multiframe operations and create spurious null evidence.

**Fix:** Prefer removing `historyRecoveryActive` from `isBusy()` and let abandonable
accounting lose to queued therapy. Otherwise thread preemption through every ingestion step.
Abort a history operation without tearing down a healthy link, distinguish yield from timeout,
and bind reconciliation status reads to ownership.

### S10 — One-shot therapy reconnect bypass can leave only one real attempt

**Classification: Confirmed in saturated durable backoff; narrower than first stated.**

Opus corrected its original generalization: at low failure counts the escalating 5/15/30/60
second backoff still allows multiple attempts within QueueWorker's 119-second window. The
defect appears after routine absence saturates the durable failure count at the five-minute
delay. Queued therapy consumes its one bypass; if that GATT attempt fails transiently, every
remaining per-second queue retry is deferred until QueueWorker clears the command.

The token resets only for `disconnect("Queue empty")`, not watchdog or `stopConnecting`, and
control is keyed to the literal reason string `"Connection needed"`.

**Fix:** Make queued therapy plus transport-only failure a standing retry policy, while
keeping suspected rekey a hard block. A bounded periodic bypass is a lesser alternative.
Reset the state on all disconnect/stop paths and remove string-matched control flow.

### S11 — Dispatched doses can be reported as not enacted; partial delivery can drop carbs

**Classification: Confirmed.**

If durable identity changes after dispatch and identity proof, `awaitBolusTerminal` uses the
generic failure helper, returning `success(false).enacted(false)` and zero delivered. Insulin
may already be in the patient; this direction invites retry and contradicts other uncertain
post-dispatch paths.

For a pump-truncated partial without an AAPS cancel request, the result is `success(false)`.
Core `CommandBolus` records associated carbohydrates only on success, so known delivered
insulin is recorded while entered carbohydrates are silently omitted.

**Fix:** Every path after `DeliveryResult.Started` must report `enacted(true)` and create a
durable unresolved record if identity is lost. Treat pump-confirmed partial delivery as a
known completed command with accurate `bolusDelivered`, or separately fix the shared carbs
gate to record carbs whenever insulin was enacted. Avoid retry semantics for known partials.

### S12 — Extended stop accounting is a guaranteed no-op and can implicate another bolus

**Classification: Confirmed; currently masked by S1.**

Terminal reconciliation first updates the extended row's duration so it ends exactly at the
terminal timestamp, then calls `syncStopExtendedBolusWithPumpId` at that same timestamp.
The stop transaction cannot find the row as active because its end predicate is strict, and
no `endId` exists yet, so it always returns false. The subsequent check asks whether *any*
extended bolus is active; an unrelated row can then cause this correctly reconciled attempt
to be marked unresolved. The same value is also used for start `pumpId` and `endPumpId`,
despite those representing distinct identities.

**Fix:** Prefer deleting the redundant stop call: the terminal history update already ends
the row at the authoritative time. If an explicit stop marker is required, preserve the
original duration until the stop transaction and use a distinct terminal-event end ID.
Any verification must be keyed to this attempt, not the globally active extended bolus.

### S13 — Extended-cancel UI/single-flight defects

**Classification: Confirmed. S13.1 was newly found during the final re-read.**

#### S13.1 — Queue-thread UI mutation can suppress the cancellation alarm

The cancel callback executes directly on QueueWorker. Its first statement calls `updateGui`,
which touches Views and Compose state. This can throw `CalledFromWrongThreadException`, or
throw on a null binding after navigation. Because refresh precedes `runAlarm`, the exception
can suppress the audible failure alarm while the pump continues delivering.

**Fix:** Run the alarm independently and first; marshal refresh to the main thread; null-guard
the view and never make safety signaling conditional on UI refresh success.

#### S13.2 — Confirmation latch survives fragment destruction

The local confirmation-open flag clears only through dialog callbacks. Rotation or view
destruction may invoke neither, leaving all later Cancel taps as silent no-ops. Reset it on
lifecycle teardown or derive single-flight exclusively from the command queue.

#### S13.3 — Silent refusal and hardcoded text

Guard failures provide no feedback, and `"cancellation pending"` is a hardcoded English
therapy string. Provide operator feedback and use a string resource.

#### S13.4 — Queue deduplication is load-bearing

Set and cancel commands share `CommandType.EXTENDEDBOLUS`. Without the explicit duplicate
cancel guard, `removeAll` can delete a queued cancellation. Document and test this. The
shared type also means a just-starting extended command cannot be aborted; that must be an
explicitly documented choice rather than an accidental enum consequence.

### S14 — Authentication presentation split is sound, but under-tested

**Classification: Sound therapy behavior; naming and test-coverage defect.**

Generic Android/GATT authentication failure now maps to retryable authentication text,
while pump error 140 and ciphertext authentication failure retain the rekey path. Opus
confirmed the numeric cause routing remains correct. The presentation enum name
`PUMP_NEEDS_CHECKING` no longer matches the generic message, and the new 129 test does not
pin the complementary 140 behavior.

**Fix:** Rename the presentation state and add paired tests for 129 → generic authentication
and 140 → suspected rekey, including the multiframe path.

### S15 — Tests that encode unsafe behavior or provide false confidence

**Classification: Consolidated test-quality finding; this was the recalled fifteenth issue,
not a fifteenth code defect.**

Tests that must be rewritten rather than kept green:

- The unresolved journal test permits a second request immediately after `UNRESOLVED`,
  encoding S3.
- The restart-expiry test asserts extended delivery remains inhibitory indefinitely,
  encoding S2.
- The provisioning test asserts the reconnect bypass is one-shot, encoding S10. Its rekey
  hard-block half is correct and must remain.
- The BLE test requiring that `EVENT_INDEX_UUID` is never read locks in removal of an
  independent selector acceptance observable, encoding S4.

Happy-path-only tests also hide defects: selector auto-advance tests never drop a write or
reject an out-of-range read; plugin tests assert only the read-only not-enacted result shape;
and core PumpSync transaction tests are correct in isolation but do not cover the driver's
incorrect interpretation of their return values.

There is no end-to-end test binding controller lifecycle to stable PumpSync identity across
restart: dispatch → ACK → identity proof → terminal history → exactly one accounting record,
idempotent on replay.

## Cross-cutting issues

1. **PumpSync transaction-shape booleans are misused as accounting oracles.** Add one
   `pumpId`-keyed persisted-row verifier and use it consistently.
2. **Attempt journal and AAPS database are competing sources of truth for active extended
   delivery.** Define authority separately for dosing, UI, and cancellation, and make every
   divergence explicit.
3. **`inhibitsAutomatedDelivery` carries three incompatible meanings.** Split dose gating,
   reconciliation eligibility, and history holdback.
4. **Threading discipline is inconsistent.** BLE callbacks, timeouts, cross-thread flags,
   and UI updates need explicit ownership and enforcement.
5. **User text contradicts behavior.** “Do not retry” cannot coexist with an immediately
   non-inhibiting state.
6. **`READ_ONLY_MODE` is checked in as false.** The intended committed default is true;
   local bench builds may deliberately flip it.

## Required regression coverage

- Extended update and partial paths reach `confirmTerminal`; an actually absent row still
  becomes unresolved.
- Reboot, generation, and serial changes cannot create permanent inhibition; extended
  attempts expire only after duration plus margin.
- Active `Uncertain` and `PossiblyApplied` cancellation refuse a second dose inside the
  observation window, then become non-blocking while remaining reconcilable.
- Backwards wall-clock movement cannot extend inhibition forever.
- Dropped selector write plus auto-advanced read is never accepted; full-ring recovery can
  re-anchor after an out-of-range selector.
- Stop retries through cursor contention and breaks stalled history promptly; tests prove a
  cancel write dispatched, not merely that a flag was set.
- Full and partial terminal amounts publish correct final progress.
- A live database extended bolus plus missing/non-inhibiting journal never returns no-op
  cancellation success.
- Urgent warnings raise, clear, and re-raise with updated causes under concurrent calls.
- Queued therapy is not blocked by recovery ingestion, and yielding does not disconnect a
  healthy link.
- Saturated transport backoff still gives queued therapy multiple attempts within 119
  seconds; rekey remains blocking.
- Every post-dispatch identity-loss path reports enacted uncertainty; known partial insulin
  does not silently discard associated carbs.
- An unrelated active extended bolus cannot affect this attempt's terminal result.
- Cancel callback from a worker thread with no binding still produces the failure alarm.
- Authentication 129 and pump error 140 remain distinctly presented.

## Invariants Opus checked and found sound

These must not regress while fixing the findings:

1. ACK is never counted as delivered insulin.
2. ACK is never treated as confirmed cancellation.
3. Cancellation requires exact proven block, sequence, amount, ownership, and current
   delivering status.
4. Dose and duration validity are exact and never rounded or clamped.
5. Command ownership and dispatch state are durably persisted before transport dispatch.
6. Bolus authorization performs no profile comparison or pump history scan; removing the
   volatile `historyRecoveryReady` gate was correct.
7. Routine status schedules history recovery asynchronously rather than inline.
8. Immediate terminal reconciliation is conservative and history-authoritative.
9. The bolus journal uses temp-write, file sync, atomic rename, parent sync, strict validation,
   and explicit migration.

## Prioritized remediation

### Tier 0 — Immediate deployment gate

Restore `YpsoPumpConst.READ_ONLY_MODE = true` in the committed tree. Flip only in explicit
bench builds until the criteria below are met.

### Tier 1 — Required before any therapy-enabled build

- S1/X1: persisted-row verification by stable pump identity.
- S3/X3/X5: bounded active uncertainty, split predicates, corrected operator text.
- S2: bounded extended lifecycle, explicit epoch-change transition, manual recovery.
- S7: no false cancel success.
- S4: independent selector acceptance evidence or unknown accounting.
- S13.1: cancellation failure alarm cannot be suppressed by UI refresh.

### Tier 2 — Required before extended hardware testing

- S5: reliable Stop retry, cancellable foreground history, off-main watchdog.
- S8: correct urgent warning lifecycle and synchronization.
- S6: terminal progress publication.
- S9: queue-independent background recovery.
- S11: correct post-dispatch and partial-result direction.
- S12: remove or correctly identify stop accounting.

### Tier 3 — Follow-up

S10, S13.2–S13.4, S14, and broader source-of-truth/threading design cleanup.

### Tier 4 — Throughout

Rewrite the four tests that encode unsafe behavior and add each missing regression alongside
its fix, not after implementation.

## Criteria before real therapy

1. Read-only is changed to false only in a single deliberate, reviewed therapy-enablement
   commit.
2. Every Tier 1 issue is closed with its regression; all four encoding tests are rewritten.
3. Hardware demonstrates completed square, cancelled partial, and combination lifecycles
   from start through authoritative type-3/type-18 history and `confirmTerminal`, without a
   false uncertainty warning.
4. A battery change during a running square bolus is followed by successful recovery and a
   later immediate dose, without permanent inhibition.
5. Stop during immediate delivery demonstrably dispatches a cancel, and failed cancellation
   produces an audible alarm.
6. A 0.10 U dose reaches 100% progress; partial delivery shows the accurate fraction.
7. Full 3000-row recovery completes without snapshot loss and yields to queued therapy in
   about one second without disconnecting a healthy link.
8. The uncertainty notification raises, clears, and re-raises for a different cause.
9. Mixed history scans and boluses do not desynchronize the durable write counter or produce
   bolus error 139.
10. An independent reviewer confirms the nine sound invariants above remain intact.

## Final verdict

The architecture—durable persist-before-dispatch ownership and pump-history-authoritative
terminal accounting—is appropriate, and the immediate-bolus path contains several strong
safety invariants. Removing `historyRecoveryReady` fixed a real authorization defect.

The current implementation is nevertheless **not safe for real therapy**. Extended terminal
confirmation is unreachable, active uncertainty can become non-inhibiting too early,
extended uncertainty can also inhibit forever, cancellation can falsely report success,
selector acceptance evidence is no longer independent, urgent warnings are unreliable, and
the cancel failure alarm can be suppressed by a worker-thread UI exception. These are live
while `READ_ONLY_MODE` remains false.
