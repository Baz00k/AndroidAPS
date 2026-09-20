# Write-counter recovery

## Protocol rule

**YpsoPump accepts an authenticated command whose counter is greater than the last accepted
write counter. Counters need not be contiguous.** `N+1`, `N+2`, and `N+1000` are valid forward
positions; neither a missing callback nor uncertainty about consumption requires a pump reboot,
journal reset, operator-reviewed consumption probe, or permanent write lockout.

The pump owner explicitly confirmed this protocol rule during issue #7. Earlier target runs also
accepted a forward gap and a jump to 4096. Those individual experiments were evidence of the rule,
not maximum permitted gap sizes. Earlier documentation describing a one-shot recovery window or
an unmeasured `floor+3` as a reason to stop was an incorrect driver restriction.

## Runtime contract

`PumpSession.Record.write` is the durable **local allocated high-water mark**, not a claim to know
the pump's exact last accepted counter. An unknown floor (`write == null`, `UNKNOWN_MID_EPOCH` or
`OBSERVED_NEW_EPOCH`) is a normal, fully supported state, not a write block: the first allocation
starts at zero, so the initial candidate is counter `1`. Persist a reservation before encryption
and the dispatch boundary before sending frames. After an interrupted transport is torn down:

1. Preserve its complete command binding as `WriteEvidence`, with unknown resolution.
2. Retain the allocated counter, even if the pump might not have consumed it.
3. Release the live reservation atomically with that evidence.
4. Reserve the next command strictly above the retained high-water mark.

For the interrupted setting-14 write at 4281, the next ordinary candidate is 4282 whether the pump's
last accepted value is 4280 or 4281. Transport uncertainty alone never creates a large jump.
Repeated interruptions advance the high-water mark again;
recovery is not a once-per-epoch experiment. Restart/in-place upgrade preserves the same behavior.
Persistence failure prevents dispatch. Counter exhaustion does not wrap. A missing or corrupt
journal, changed identity/key, or writes by another controller outside this owner's accounting
are separate ownership problems; do not invent a high-water mark from a GATT error code.

The first issue #7 runtime recovery dispatched 4282 but received pump error 139 on the final frame
and read selector 1 instead of 14. AndroidAPS was subsequently found running alongside the bench and
stopped; whether it advanced the pump is unproven.

## Confirmed counter-error search

Only a pump-originated final-frame `139` (`APPERR_COUNTER_ERROR`) advances counter search. The
consecutive-error state is durable. Relative to the pre-recovery baseline, candidates are:

```text
N+1, N+2, N+4, N+8, ...
```

`YpsoWriteAccounting` performs the search inline: it persists the `139` as a proven rejection and
redispatches the same logical write above the retained position, so a caller sees one logical
operation rather than one failure per candidate. An unknown floor uses exactly the same mechanism
from baseline `0` (`1, 2, 4, 8, ...`), and a pump-accepted counter durably promotes the record to
`ESTABLISHED`. This is the runtime path for sessions imported from `ypso-keys`, identity-only journal
recovery and observed pump reboots. The reviewed ownership handoff and selector lower-bound recovery
remain available as evidence-seeded entry points, but they are no longer required to make an unknown
floor writable.

Each 139 is persisted as a proven rejection before another candidate can be allocated. Semantic
acceptance resets the exponent to zero. An unrelated GATT status, timeout, disconnect, lost or
ambiguous callback, local dispatch failure, or unknown command outcome does not increase it. The
exponent is bounded at 20: the increment stops doubling there, further rejections are still
persisted, later candidates advance by the maximum increment, and counter arithmetic cannot wrap.
A single logical write redispatches at most once per exponent step; beyond that budget the
persisted `139` is surfaced as a proven rejection with the reservation closed.

This follows the research repository's established facts: write counters are monotonically
increasing, 139 identifies counter mismatch, pump reads synchronize reboot state, and parallel
controllers are unsafe. The exponential policy is an AAPS recovery design; the research repository
does not prescribe its exact increments.

Recovery runs through shared `YpsoWriteAccounting` before the next command, after the previous
transport owner has been released. Profile/history acquisition can restart on the next connection
and must still validate every returned row. This does not turn stale read-back into acceptance.

## Command outcome is separate from counter recovery

A lost ACK does not prove failure, success, delivered insulin, or cancellation. Retiring a
transport reservation never marks its command accepted, rejected, or bolus attempt terminal.

- Selector acquisition may restart using a new command and higher counter.
- An uncertain bolus start remains in the durable bolus attempt journal and cannot be retried as
  another dose merely because counter recovery succeeded.
- Status/history reads and their selectors must remain available to reconcile that attempt.
- Cancellation uses a higher counter and must still target the previously proven active fast
  sequence. Counter uncertainty alone is not a reason to prevent cancellation.
- Confirmed insulin accounting and request attribution retain their independent evidence rules.

## Documentation precedence

This contract supersedes counter-contiguity assumptions in the historical Step 07 experiment
instructions, session-ownership notes, and status-protocol captures. Preserve raw historical facts
(including unknown command outcomes), but do not copy experimental one-shot gates into runtime
recovery or treat them as pump protocol restrictions.
