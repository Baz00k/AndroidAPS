# YpsoPump event-history identity and time contract

> **Evidence scope:** one isolated YpsoPump running master/supervisor firmware V05.00.52,
> control protocol 1.3 and history service 1.4, observed on 2026-09-15 and 2026-09-16. This defines the
> Step 08 ingestion seam; it does not enable therapy or claim command attribution. Event type
> identity follows the two-tier evidence policy in **Event semantics** below. It is a domain
> contract: no production ingestion caller constructs snapshots yet, and no read-only stable-head
> capture procedure exists on the target because event-value reads advance its persistent selector.
> CRC validation uses the project's current CRC-16 interpretation, which is not an independently
> qualified firmware contract; the paired target rows decoded with it are the empirical support.

Protected capture provenance: the consolidated private target capture set has SHA-256
`e3d8ae3d842e32c087a7597b50133cd02d88faeb725d4c0747082ce70b644b82`. Raw rows and real keys are
not published. The 2026-09-15/16 read-only follow-up row bundle has SHA-256
`ade9c3f6ea6676425e987f6d4b8f11228d61769d0998a4b58e8ab16672ea13b5`; it contains no key material
and is also kept private. `YpsoHistoryEntryTest` contains shape-only transformed target fixtures for
the captured kinds (types 2, 3, 4, 9, 10, 14, 16): identifying time/sequence fields were replaced and
each CRC recomputed with an independent script. `YpsoHistoryContractTest` decodes those wires and
asserts their evidenced classification without command origin.

Active-profile switching maps to protocol type 6 (`BASAL_PROFILE_CHANGED`). Two independently
published Ypso protocol implementations agree on that type
([SandraK82/ypsopump-research@de7e867](https://github.com/SandraK82/ypsopump-research/blob/de7e867241fafd2fb8061ceeecf42af2883b9eb4/ypsopump-test/app/src/main/java/com/ypsopump/test/data/PumpDataModels.kt#L34-L44),
[vicktor/ypsomed-pump@71ae55e](https://github.com/vicktor/ypsomed-pump/blob/71ae55e372cb7a4fe1c96bfdd536d3beccbbb8a2/sdk/ypso-sdk/src/main/java/com/ypsopump/sdk/internal/protocol/YpsoProtocolConstants.kt#L66-L72)),
and the target operator paired on-pump
A→B and B→A actions one-to-one with two new therapy-data rows. Type 6 is therefore decoded as a
profile-coherence invalidation event. Its value-field meaning is deliberately not inferred: the
destination A/B profile must be read from the authoritative active-profile setting by Step 09.
Both references also index that active program at setting 1 with values 3=A and 10=B, and place the
hourly profile A/B ranges at indices 14–37 and 38–61; that corroborates the Step 09 read-back plan
but does not alter this history contract. `YpsoHistoryEntryTest` includes independently
CRC-generated type-6 and reference-mapped protocol-contract wires to exercise the strict common
schema; they are not represented as captured target wires.

## Strict wire schema

Event count is exactly one eight-byte GLB safe variable: unsigned 32-bit little-endian value
followed by its bitwise complement. Embedded/trailing GLB searches are not accepted.

An event value is exactly 19 bytes: this 17-byte little-endian payload followed by a valid CRC:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 4 | pump-local seconds since 2000-01-01 00:00:00 |
| 4 | 1 | event type |
| 5 | 2 | value 1 |
| 7 | 2 | value 2 |
| 9 | 2 | value 3 |
| 11 | 4 | global unsigned event sequence |
| 15 | 2 | current logical history index |

Raw 17-byte values, bad CRC, short/long values and trailing fallbacks are rejected.

## Ring and sequence identity

- The observed count `3000` is capacity, not `newest index + 1`.
- Logical index `0` is the moving newest head. A new event inserts at index 0 and shifts older
  entries to higher indices. The same event's index is therefore not identity.
- The sequence is global: gaps are expected when other pump history families consume sequence
  values. A compatible event need only be strictly newer under unsigned 32-bit ordering; it need
  not be `previous + 1`.
- Stable AAPS identity is `(pump serial, sequence generation, event sequence)`. The proposed
  `PumpSync` pump ID is `(sequence generation << 32) | sequence`; PumpSync also scopes it by pump
  type and serial. Receipt time, amount, ring index, BLE key generation and crypto counters are not
  identity.
- The event fingerprint excludes the moving ring index. For ordinary rows it packs factory seconds,
  type, values and sequence into a fixed-width 120-bit value. TBR rows are the evidenced exception: an
  active type-9 row is rewritten in place to terminal type 10 with the same sequence and factory time;
  value2 may change from requested duration to elapsed minutes. Their fingerprint therefore
  normalizes type 9/10 to one TBR type and excludes value2/value3, while retaining factory time,
  percentage and sequence. A separate state fingerprint retains the exact type and values so the
  terminal rewrite is emitted once as an unordered state update under the original event identity.
  Semantics keep the two meanings apart: the active row carries `requestedDurationMinutes`, a
  terminal rewrite or standalone terminal row carries `elapsedDurationMinutes`, and
  `durationMinutes` remains only the programmed duration of a delayed bolus.
  The cursor retains the pump's single active TBR independently of the newest sequence, because a
  later bolus can make the mutable TBR row older than the cursor before cancellation. Every
  reconciliation verifies that tracked row: absence under complete coverage emits
  `TRACKED_TBR_ROW_MISSING`, absence under partial coverage emits `COVERAGE_INCOMPLETE`, and more
  than one concurrently active type-9 row emits `MULTIPLE_ACTIVE_TBR_ROWS` instead of retaining or
  choosing stale state. Historical starts separated by a type-32 abort are replayed oldest-first, so
  abort-then-replacement leaves only the replacement tracked. Other conflicting content for one
  sequence blocks ingestion. A reference-mapped abort row
  (type 32) ends tracked TBR state when observed among newer events, without fabricating a terminal
  row; at bootstrap, a start row that a newer abort row has passed is not tracked. This interaction
  is reference-derived and not yet observed on the target.
- A 32-bit sequence decrease is accepted as wrap only within the normal unsigned forward half-range
  and when the authenticated pump reboot counter has not changed. A decrease coincident with reboot
  is an unresolved reset/wrap ambiguity and produces a gap; reaching the identity generation ceiling
  (`Int.MAX_VALUE`) is a deterministic `SEQUENCE_GENERATION_OVERFLOW` gap, and a jump beyond the
  forward half-range in either direction is `INVALID_ORDER_OR_RESET`. A reboot with a continuing
  sequence does not change event identity generation. Key renewal alone does not change event identity.
- Legacy development builds before this contract wrote PumpSync rows with receipt-time `pumpId`
  values. Those rows predate the `(generation << 32) | sequence` scheme, are not compatible with it,
  and must not be migrated or mixed into the same PumpSync scope; collision is practically impossible
  (receipt-time ids sit at generation ≈ 400), but the schemes are deliberately never combined.
- Protected transport evidence contains four authenticated new-epoch adoptions through reboot
  counters 18, 19, 20 and 21. Each adoption reset the authenticated read counter to 1 or 2 while the
  same history service and older event rows remained readable afterward. Event history therefore
  survives an ordinary power-cycle; reboot is snapshot/reset evidence, not an instruction to clear
  the event cursor or start a new identity generation when sequence continuity is present.

## Stable snapshots, gaps and duplicates

A scan is stable only when all of these match before and after it:

1. authenticated pump reboot counter;
2. exact event count;
3. CRC-valid logical head event at index 0, including sequence and immutable fingerprint;
4. the first returned row equals that head anchor.

This head check is mandatory because a full ring remains at count 3000 while it moves. A changed
count, reboot or head returns `Moving`; no cursor advances and the caller may start a fresh scan.
On the measured target, event-value reads advance a persistent selector. Two consecutive value reads
therefore cannot perform this head check by themselves: index 0 followed by index 1 is observational
evidence only. The bench records such captures with `stable_head_cursor=false`; a future snapshot
producer must use a reviewed, durably reconciled repositioning operation or another non-advancing
head source before it may construct a stable snapshot.

For incremental ingestion, the previous sequence plus immutable fingerprint must appear in the
window. If it does not, incomplete coverage is retriable; complete coverage means the cursor was
overwritten or the sequence reset and produces a deterministic gap. Empty history after a non-empty
cursor, invalid unsigned order, conflicting payloads and generation overflow also produce explicit
gaps. Repeating a stable scan returns no events and leaves the cursor unchanged.

Bootstrap stores the newest stable event as a cursor and imports no historical treatment by default.
Events newer than an existing cursor are emitted oldest first. Cursor state must be persisted only
after downstream ingestion succeeds so restart/retry remains idempotent.

## Pump-local time

Paired observations establish that field 0 is pump display-wall-clock seconds since
`2000-01-01 00:00:00`, not Unix seconds and not intrinsically UTC. Examples:

- `842796136` -> `2026-09-15 14:02:16`, paired with a manual Stop at displayed 14:02;
- `842796573` -> `2026-09-15 14:09:33`, paired with Resume at displayed 14:09;
- `842793548` -> `2026-09-15 13:19:08`, paired with the pump's 1.20 U history row at 13:19.

Conversion to an AAPS instant requires an explicit IANA zone belonging to the pump's clock context.
One valid offset resolves normally. A local time in a daylight-saving gap or overlap remains
unresolved; the implementation does not choose an offset from receipt time or device current offset.
Clock/date-change event layouts were not independently paired, so historical offset reconstruction
across a pump clock change remains blocked instead of guessed.

A controlled +2-minute clock save followed by restoration on 2026-09-16 kept reboot epoch 21 and
count 3000 but shifted an unchanged prior row from embedded index 8 to 10: each save inserted one
newer event. BLE was temporarily unavailable after the forward save, and the unresolved event
selector operation prevented a safe random-access read of those two new rows. This proves clock
changes affect history ordering but does not establish their wire layout or an offset-reconstruction
algorithm; reconstruction therefore remains blocked. Reconciliation orders and identifies rows by
global sequence, never by factory time or receipt time, so forward/backward clock discontinuities are
deterministic and cannot merge or reorder otherwise valid identities.

No boot-time conversion is needed to identify or date these event rows. The reference
`bootTimeToFactoryTime` field was not independently established on this target and is not used as a
fallback. The authenticated reboot counter is retained only as snapshot/reset evidence.

## Event semantics

Type identity (which number means what) is supported in two tiers:

- **Target-paired** for the numbers this session observed against the pump's own display or actions:
  2, 3, 4, 6, 9, 10, 14, 16, and alarm codes 101, 103 and 104 (operator-confirmed against the pump's
  displayed alarm history on 2026-09-16).
- **Reference-corroborated** for the remaining numbers in the published event table, where two
  third-party protocol implementations agree
  ([SandraK82/ypsopump-research@de7e867](https://github.com/SandraK82/ypsopump-research/blob/de7e867241fafd2fb8061ceeecf42af2883b9eb4/ypsopump-test/app/src/main/java/com/ypsopump/test/data/PumpDataModels.kt#L34-L67),
  [vicktor/ypsomed-pump@71ae55e](https://github.com/vicktor/ypsomed-pump/blob/71ae55e372cb7a4fe1c96bfdd536d3beccbbb8a2/sdk/ypso-sdk/src/main/java/com/ypsopump/sdk/internal/protocol/YpsoProtocolConstants.kt#L48-L95)).
  These publications are **not verifiably independent**: vicktor's file documents that its IDs match
  a Python reference (`Ypso-main/pump/constants.py`, `EVENT_NAMES`), so the two may share lineage
  rather than confirm each other. The target-paired numbers are the empirical anchor; every other
  number is reference-only until a target row pairs it. vicktor additionally documents empirical
  confirmation on firmware V05.02.03; the target runs V05.00.52 and the numbering agrees.

Value layouts are claimed only where the target paired them, plus the centi-unit bolus convention
shared by the paired rows and repeated by both references for the other bolus phases. Target rows
always override reference claims: both references treat type-3 `value2` as a second amount in units,
while the paired target row shows it is the programmed duration in minutes. `value2` of other bolus
phases, backup rows, cap changes, date/time changes, rewind, daily totals, battery removal and alarm
value fields stay unclaimed.

Alarm-family reads share the event family's cursor behavior: a value read returns the row at the
persistent cursor and advances it, and a cursor advanced past the last occupied row is rejected by
the pump until it is repositioned. On 2026-09-16 the newest eleven alarm rows were read with strict
19-byte CRC validation: 7× type 101, 2× type 103 and 2× type 104, and the operator confirmed they
match the pump's displayed alarm history. The private head-row and follow-up capture bundles have
SHA-256 `e7673571f942650c30e67cb8b0ddbb62261f3ac7c72657933e7218957815949e` and
`e4cf104763e4eac668548a58b3cf0855cfe29e25021d1c94a64f48f3ac2bab9b`.

| Type | Kind | Claimed values | Evidence |
|---:|---|---|---|
| 1 | DELAYED_BOLUS_RUNNING | value1 / 100 U | reference |
| 2 | IMMEDIATE_BOLUS_COMPLETED_UNATTRIBUTED | value1 / 100 U | target-paired: manual 1.20/1.50 U and two pump-initiated 2.00 U rows |
| 3 | DELAYED_BOLUS_COMPLETED | value1 / 100 U, value2 minutes | target-paired: 3.00 U / 15 min; duration overrides the reference amount claim |
| 4 | PRIMING_FINISHED | value1 / 100 U | target-paired: 1.00 U priming |
| 5 | BOLUS_STEP_CHANGED | — | reference |
| 6 | BASAL_PROFILE_CHANGED | — | target action pair + reference; A/B resolved by setting read-back |
| 7 | BASAL_PROFILE_A_CHANGED | — | reference; hourly values of profile A were edited, active profile not implied |
| 8 | BASAL_PROFILE_B_CHANGED | — | reference; hourly values of profile B were edited, active profile not implied |
| 9 | TEMP_BASAL_STARTED | value1 percent, value2 requested minutes | target-paired: 150% / 15 min start |
| 10 | TEMP_BASAL_COMPLETED / CANCELLED / TERMINAL_UNRESOLVED | value1 percent, value2 elapsed minutes versus the same identity's request | target-paired cancel and expiry; standalone or greater values unresolved |
| 12 | DATE_CHANGED | — | reference |
| 13 | TIME_CHANGED | — | reference |
| 14 | PUMP_MODE_CHANGED | value1=3 Stop, 10 Resume | target-paired Stop/Resume |
| 16 | REWIND_FINISHED | — | target-paired: rewind at 16:13 |
| 17 | COMBINED_BOLUS_RUNNING | value1 / 100 U | reference |
| 18 | COMBINED_BOLUS_COMPLETED | value1 / 100 U | reference |
| 19 | IMMEDIATE_BOLUS_RUNNING | value1 / 100 U | reference |
| 20 | DELAYED_BOLUS_BACKUP | — | reference |
| 21 | COMBINED_BOLUS_BACKUP | — | reference |
| 22 | TEMP_BASAL_BACKUP | — | reference |
| 23 | DAILY_TOTAL_INSULIN | — | reference |
| 24 | BATTERY_REMOVED | — | reference |
| 25 | CANNULA_PRIMING_FINISHED | — | reference |
| 26 | BLIND_BOLUS_COMPLETED | value1 / 100 U | reference |
| 27 | BLIND_BOLUS_RUNNING | value1 / 100 U | reference |
| 28 | BLIND_BOLUS_ABORTED | value1 / 100 U | reference |
| 29 | IMMEDIATE_BOLUS_ABORTED | value1 / 100 U | reference |
| 30 | DELAYED_BOLUS_ABORTED | value1 / 100 U | reference |
| 31 | COMBINED_BOLUS_ABORTED | value1 / 100 U | reference |
| 32 | TEMP_BASAL_ABORTED | value1 percent | reference; observing it ends tracked mutable TBR state without fabricating a terminal row — target wire interaction unverified |
| 33 | BOLUS_AMOUNT_CAP_CHANGED | — | reference |
| 34 | BASAL_RATE_CAP_CHANGED | — | reference |
| 100–108 | ALARM | value fields unclaimed; explicit alarm code | 101/103/104 target-paired: eleven strict target rows read on 2026-09-16 and operator-confirmed against the displayed alarm history; remaining codes reference |
| 150 | DELIVERY_STATUS_CHANGED | — | reference |

Every other number, including unenumerated alert numbers 109–199, classifies `UNKNOWN` and still
blocks command attribution. Priming is distinguishable from therapy history. Aborted-bolus rows
classify by reference name with value1 claimed as the amount; which field is delivered versus
requested remains unpaired and unclaimed. Type 2 contains no qualified command-origin field, so
manual and remote immediate boluses cannot yet be distinguished. A TBR terminal state is
distinguishable as cancel or normal expiry only when the earlier state of that exact identity was
persisted; command origin is still not encoded and TBR command attribution remains blocked.

The operator confirmed that the captured 2.00 U rows at 2026-09-15 15:53 and 17:23 were initiated on
the pump, and that no bolus was interrupted during the observation window. A remote or partial/cancel
layout was therefore not present to pair. Those cases remain explicitly unsupported and attribution
fails closed; no deliberate insulin delivery or interruption is required merely to populate this
schema ticket.

On 2026-09-16 the operator switched the active basal profile on-pump from A to B at pump-local 10:29,
then from B back to A at 10:30, and observed exactly two new entries in the pump's history display.
The driver maps these history-producing actions to the independently corroborated type-6 profile
change event. The persistent event iterator could not be safely repositioned while an earlier
selector write remained unresolved, and an attempted read-only full-ring traversal was stopped as
operationally unsuitable without producing strict target wires. Step 09 must consume type 6 to
invalidate comparison evidence and resolve the active profile via settings read-back.

## Attempt attribution boundary

A later therapy implementation must capture and durably persist a stable history cursor **before**
dispatch. After the attempt it must obtain a stable scan and find exactly one strictly newer event
compatible with the command-specific contract. In-place state updates keep the previous identity and
are never attribution evidence, however many are observed. No new event, multiple compatible events,
unknown intervening event types, gaps, or an origin field not encoded by the event all block
attribution.
History freshness means this stable after-cursor proof, not proximity to the phone's receipt time.

This Step 08 contract deliberately keeps remote bolus and TBR attribution blocked. Amount equality,
recent-event selection, status receipt time and command ACK are never substitutes for identity. The
dormant production fallbacks that created receipt-time pump IDs have been removed; the distributed
artifact remains status-only.

## Validation and hand-back

The exact candidate is validated with the YpsoPump full-debug unit suite, lint, assembly and GATT
write-ownership check, plus the standalone bench unit suite and debug assembly. The bench APK hash,
test counts and independent Standards/Spec review disposition are recorded in issue #13 because they
depend on the final commit. Target evidence used Samsung SM-S918B / Android 16 with pump firmware
V05.00.52 and authenticated reboot epoch 21. Per operator confirmation, the unchanged AAPS
status-only controller was handed back enabled/running; mylife and the bench remained disabled and no
app data was cleared.

The final unresolved selector operation remains protected evidence only and must not be interpreted
as accepted or retried. Remote-command origin remains unobserved and blocked; which field of an
aborted, combined or blind bolus row is delivered versus requested remains unclaimed, and no
unpaired row may be used for attribution. Clock-change insertion behavior and repeated physical
reboot persistence are evidenced, while clock-offset reconstruction and sequence-reset behavior
remain fail-closed because those cases were not observed. TBR cancel versus the paired 15-minute
normal expiry is evidenced, while other terminal values remain unresolved. Reference-corroborated
type identities are admitted only as documented above; they never substitute for receipt-time
proximity, amount matching or selector position, and they never claim command origin.
