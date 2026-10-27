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

The row timestamp is **pump-local wall time**, not UTC. The pump follows no time zone and never changes its clock for DST: its clock is whatever the user last set, and every set writes a date or time change row. Each scan reads the pump clock before its final count and head check, so no change row follows the reading, and records the offset between the pump's wall time read as UTC and the phone clock, unrounded. Every row of the read is placed from that one offset, chained back through the change rows newer than it: crossing a change row backwards removes the step it made (offset before = offset after − (new setting − replaced clock)). A change row is stamped with the clock it replaced, so it belongs to the segment before it. Rewritten rows below the cursor, such as a running TBR's terminal rewrite, are placed the same way, since the read covers every row from them to the head. Nothing about the clock is stored between scans.

A scan whose clock read fails places no row: history stops without advancing the cursor, and the next scan reads the clock again. A change row whose setting is impossible, or later than the clock the next change row replaced, stops history at that row with the incomplete history alert. Rows are ordered by sequence rather than timestamp, so a pump clock edit cannot reorder their identities.

Exact times also make TBR binding exact: a TBR row binds to the AAPS start whose window holds its time. The TBR journal holds a bounded number of attempts, so a long history stall can drop starts before their rows arrive. Pump history then decides what ran: a TBR row that no journalled start binds is imported by its pump ID at its own time, percent and duration, and the AAPS record of a dropped start that no row bound is invalidated in the next ingestion pass that reads history after it was saved. Such records are listed before the read starts. A record below 100 % is invalidated before the pass applies its rows and any other record after them, so a pass that stops part-way leaves insulin on board erring high, never low. The record of a start an earlier build left unmatched is bound to the row it stands in for, so it stays when the journal drops the start. A dropped AAPS start therefore shows as set on the pump. A record the user removed stays removed while its row is imported.

## Event interpretation

The classifier in `history/YpsoHistoryContract.kt` defines the supported type mapping. Relevant treatment rows are immediate completion (`2`, delivered centi-units in value 1), square completion (`3`, delivered centi-units in value 1 and pump-reported minutes in value 2), combination completion (`18`, delivered total in value 1), TBR start/terminal (`9`/`10`), profile changes (`6–8`) and clock changes (`12`/`13`). A clock change row is stamped with the replaced clock and carries the new setting, paired on the target: a date change (`12`) has day, month and year − 2000 in values 1–3 and keeps the time of day; a time change (`13`) has hour, minute and second and keeps the date. A type-10 TBR row may rewrite a type-9 row with the same identity. An immediate completion row does not encode whether the command came from AAPS or the pump; amount or receipt-time proximity alone never establishes origin. Unknown events and history gaps prevent speculative command attribution. Terminal bolus history, together with the persisted attempt and same-link bolus identity, reconciles AAPS delivery and cancellation.

### Temporary basal and pump mode rows

Observed on V05.00.52 for BLE- and pump-menu-started TBRs:

- A started TBR appends a type-9 row: value 1 = percent, value 2 = requested minutes.
- When the TBR ends, the **same row** (same sequence and timestamp, which is the start time) is rewritten to type 10: value 1 keeps the percent, value 2 becomes the elapsed whole minutes. Natural expiry leaves value 2 equal to the requested duration; a BLE stop, a cancel from the pump's TBR menu or a pump Stop leaves it smaller (0 when stopped within the first minute). No separate type-32 abort row was observed for any of these.
- A pump Stop that ends a TBR first rewrites the TBR row, then appends type 14 with value 1 = 3. Resuming appends type 14 with value 1 = 10 and does not start a new TBR.
- Rejected TBR commands (codes 130, 134, 135) add no row.

For additional pump event names, see the [original research event table](https://github.com/SandraK82/ypsopump-research/blob/main/ypsopump-test/app/src/main/java/com/ypsopump/test/data/PumpDataModels.kt). The driver's classifier is authoritative for fields it actually consumes.
