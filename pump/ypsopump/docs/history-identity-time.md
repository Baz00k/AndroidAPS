# YpsoPump event-history identity and time contract

> **Evidence scope:** one isolated YpsoPump running master/supervisor firmware V05.00.52,
> control protocol 1.3 and history service 1.4, observed on 2026-09-15. This defines the
> Step 08 ingestion seam; it does not enable therapy or claim command attribution.

Protected capture provenance: the consolidated private target capture set has SHA-256
`e3d8ae3d842e32c087a7597b50133cd02d88faeb725d4c0747082ce70b644b82`. Raw rows and real keys are
not published. `YpsoHistoryEntryTest` contains shape-only transformed target fixtures for all seven
supported kinds (types 2, 3, 4, 9, 10, 14, 16): identifying time/sequence fields were replaced and
each CRC recomputed with an independent script. `YpsoHistoryContractTest` decodes those wires and
asserts their evidenced classification without command origin.

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
- The immutable event fingerprint is the canonical hex encoding of factory seconds, type, values and
  sequence, excluding the moving ring index. Reusing one sequence with different immutable content is
  a conflict and blocks ingestion.
- A 32-bit sequence decrease is accepted as wrap only within the normal unsigned forward half-range
  and when the authenticated pump reboot counter has not changed. A decrease coincident with reboot
  is an unresolved reset/wrap ambiguity and produces a gap. A reboot with a continuing sequence does
  not change event identity generation. Key renewal alone does not change event identity.

## Stable snapshots, gaps and duplicates

A scan is stable only when all of these match before and after it:

1. authenticated pump reboot counter;
2. exact event count;
3. CRC-valid logical head event at index 0, including sequence and immutable fingerprint;
4. the first returned row equals that head anchor.

This head check is mandatory because a full ring remains at count 3000 while it moves. A changed
count, reboot or head returns `Moving`; no cursor advances and the caller may start a fresh scan.

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

No boot-time conversion is needed to identify or date these event rows. The reference
`bootTimeToFactoryTime` field was not independently established on this target and is not used as a
fallback. The authenticated reboot counter is retained only as snapshot/reset evidence.

## Target-evidenced event semantics

| Type | Values | Supported meaning | Paired evidence |
|---:|---|---|---|
| 2 | value1 / 100 U | completed immediate bolus, origin unknown | manual 1.20, 2.00 and 1.50 U rows |
| 3 | value1 / 100 U, value2 minutes | completed delayed/square bolus | 3.00 U / 15 min and 3.50 U / 15 min |
| 4 | value1 / 100 U | priming finished | 1.00 U priming at 16:15 |
| 9 | value1 percent, value2 minutes | TBR started/running | controlled 150% / 15 min start |
| 10 | value1 percent, value2 minutes | TBR terminal row; completion vs cancel unresolved | historical 200% / 30 min and 170% / 30 min |
| 14 | value1=3 or 10 | Stop or Resume respectively | controlled Stop/Resume pair |
| 16 | firmware-specific values unresolved | rewind finished | rewind at 16:13 |

All other event types are unsupported until paired on the target. In particular, reference enums are
not promoted to supported semantics by name alone. Priming is distinguishable from therapy history.
Type 2 contains no qualified command-origin field, so manual and remote immediate boluses cannot yet
be distinguished. Partial/cancelled bolus and TBR cancellation layouts remain unresolved.

## Attempt attribution boundary

A later therapy implementation must capture and durably persist a stable history cursor **before**
dispatch. After the attempt it must obtain a stable scan and find exactly one strictly newer event
compatible with the command-specific contract. No new event, multiple compatible events, unknown
intervening event types, gaps, or an origin field not encoded by the event all block attribution.
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
as accepted or retried. Remote-command origin, partial/cancelled bolus, TBR cancellation,
clock-change reconstruction and sequence behavior across a physical reboot are still unevidenced and
therefore blocked. These limitations do not receive inferred semantics from reference enums,
receipt-time proximity, amount matching or selector position.
