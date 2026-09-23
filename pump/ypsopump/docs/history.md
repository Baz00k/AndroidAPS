# Event history

## Row layout and identity

The event count is one eight-byte GLB safe variable. An event value is exactly 19 bytes: a 17-byte little-endian payload followed by two integrity bytes. The decoder rejects other lengths and invalid CRCs.

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | pump-local seconds since 2000-01-01 00:00:00 |
| 4 | 1 | event type |
| 5, 7, 9 | 2 each | values 1–3 |
| 11 | 4 | global event sequence |
| 15 | 2 | current logical ring index |

Index zero is the newest row; the index moves as new events arrive and is never used as identity. The stable identity is **pump serial + sequence generation + unsigned event sequence**; PumpSync uses `(generation << 32) | sequence` scoped by pump type and serial. Sequence gaps are normal because other history families share the counter. An ordinary pump reboot with continuing sequence does not change event identity generation. In-place TBR and bolus row changes are matched to their previous sequence and start time rather than imported as new treatments.

## Scanning and time

A scan verifies the authenticated reboot, count and newest row before and after reading and requires the previous cursor in an incremental window. Moving heads, incomplete coverage, conflicting content and ambiguous sequence resets do not advance the durable cursor. An empty store anchors the newest stable event without importing older therapy; subsequent rows are processed oldest-first. Successful downstream ingestion precedes cursor persistence. Background scans yield the BLE link to therapy commands.

The row timestamp is **pump-local wall time**, not UTC. The driver interprets it using the phone's current IANA timezone, assuming the pump clock uses the same zone. A mismatch can shift imported treatment times. Ambiguous or nonexistent local times during a DST overlap/gap block that ingestion pass without advancing its cursor. Rows are ordered by sequence rather than timestamp, so a pump clock edit cannot reorder their identities.

## Event interpretation

The classifier in `history/YpsoHistoryContract.kt` defines the supported type mapping. Relevant treatment rows are immediate completion (`2`, delivered centi-units in value 1), square completion (`3`, delivered centi-units in value 1 and pump-reported minutes in value 2), combination completion (`18`, delivered total in value 1), TBR start/terminal (`9`/`10`) and profile changes (`6–8`). A type-10 TBR row may rewrite a type-9 row with the same identity. An immediate completion row does not encode whether the command came from AAPS or the pump; amount or receipt-time proximity alone never establishes origin. Unknown events and history gaps prevent speculative command attribution. Terminal bolus history, together with the persisted attempt and same-link bolus identity, reconciles AAPS delivery and cancellation.

For additional pump event names, see the [original research event table](https://github.com/SandraK82/ypsopump-research/blob/main/ypsopump-test/app/src/main/java/com/ypsopump/test/data/PumpDataModels.kt). The driver's classifier is authoritative for fields it actually consumes.
