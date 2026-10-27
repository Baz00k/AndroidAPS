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

The row timestamp is **pump-local wall time**, not UTC. The driver interprets it using the phone's current IANA timezone, assuming the pump clock uses the same zone. A mismatch can shift imported treatment times. A repeated local time in a DST overlap names two instants an hour apart and is placed only by the AAPS command that row records. A TBR row binds to the AAPS start whose window holds exactly one of them, when no other AAPS start of that percent awaits status and no other row of the same read could be that start's. The TBR journal holds a bounded number of attempts, so a long history stall can drop starts before their rows arrive. Pump history then decides what ran: a TBR row that no journalled start binds is imported by its pump ID at its own time, percent and duration, and the AAPS record of a dropped start that no row bound is invalidated in the next ingestion pass that reads history after it was saved. Such records are listed before the read starts. A record below 100 % is invalidated before the pass applies its rows and any other record after them, so a pass that stops part-way leaves insulin on board erring high, never low. The record of a start left unmatched for lack of a clock reading is bound to the row it stands in for, so it stays when the journal drops the start. A dropped AAPS start therefore shows as set on the pump, and one in a repeated hour blocks like any TBR set on the pump. A record the user removed stays removed while its row is imported. An immediate bolus row takes the instant within ten minutes of when AAPS sent its dose. Any other ambiguous or nonexistent local time, such as a TBR or Stop set on the pump in that hour, blocks that ingestion pass without advancing its cursor. A pump clock read while it shows a repeated time takes the offset nearest the phone; one read in a skipped hour uses the offset before the change. Rows are ordered by sequence rather than timestamp, so a pump clock edit cannot reorder their identities.

## Event interpretation

The classifier in `history/YpsoHistoryContract.kt` defines the supported type mapping. Relevant treatment rows are immediate completion (`2`, delivered centi-units in value 1), square completion (`3`, delivered centi-units in value 1 and pump-reported minutes in value 2), combination completion (`18`, delivered total in value 1), TBR start/terminal (`9`/`10`) and profile changes (`6–8`). A type-10 TBR row may rewrite a type-9 row with the same identity. An immediate completion row does not encode whether the command came from AAPS or the pump; amount or receipt-time proximity alone never establishes origin. Unknown events and history gaps prevent speculative command attribution. Terminal bolus history, together with the persisted attempt and same-link bolus identity, reconciles AAPS delivery and cancellation.

### Temporary basal and pump mode rows

Observed on V05.00.52 for BLE- and pump-menu-started TBRs:

- A started TBR appends a type-9 row: value 1 = percent, value 2 = requested minutes.
- When the TBR ends, the **same row** (same sequence and timestamp, which is the start time) is rewritten to type 10: value 1 keeps the percent, value 2 becomes the elapsed whole minutes. Natural expiry leaves value 2 equal to the requested duration; a BLE stop, a cancel from the pump's TBR menu or a pump Stop leaves it smaller (0 when stopped within the first minute). No separate type-32 abort row was observed for any of these.
- A pump Stop that ends a TBR first rewrites the TBR row, then appends type 14 with value 1 = 3. Resuming appends type 14 with value 1 = 10 and does not start a new TBR.
- Rejected TBR commands (codes 130, 134, 135) add no row.

For additional pump event names, see the [original research event table](https://github.com/SandraK82/ypsopump-research/blob/main/ypsopump-test/app/src/main/java/com/ypsopump/test/data/PumpDataModels.kt). The driver's classifier is authoritative for fields it actually consumes.
