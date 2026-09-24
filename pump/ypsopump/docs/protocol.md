# Pump protocol used by the driver

## Connection and encrypted messages

Android must be bonded to the pump. The driver authenticates access with an MD5-derived password from the BLE address and a fixed protocol salt, verifies the claimed serial against an independently observed bonded name or GATT serial, and reads encrypted status with the imported 32-byte session key. These are separate checks. The supported identity format is eight decimal digits beginning with `10`.

Application payloads use XChaCha20-Poly1305 with a 24-byte nonce **appended** to ciphertext and tag. Decrypted responses end with a 12-byte authenticated little-endian reboot/read-counter tail. The session journal commits the authenticated read floor before publishing a CRC- and schema-valid status. Replay, lower or jumped reboot generations and malformed replies do not publish data. A compatible next-reboot observation adopts the new read floor and reconnects; it does not infer a write floor. The journal seals credentials and replay state using Android Keystore and retains same-key replay history across imports.

## Write counters and command outcomes

The journal's write counter is a durable *allocated high-water mark*, not necessarily the pump's last consumed counter. The first candidate with an unknown floor is 1. Reservations and dispatch intent are persisted before frames are sent. The pump accepts higher write counters without requiring contiguous values. An interrupted write retains its allocation and unknown command outcome; the next operation uses a higher counter. Only a pump-originated **final-frame 139** proves counter rejection and permits an exponential search (`1, 2, 4, 8, …` relative to the recovery baseline). An accepted write establishes the floor. A lost callback, disconnect or unrelated numeric error never proves rejection or delivery. Counter recovery does not resolve a bolus attempt: that requires command-specific status and history evidence.

Each durable session revision uses a fresh Keystore key; retiring the previous key makes restored older revisions fail closed. `RESERVED` and `POSSIBLY_SENT` remain separate durable boundaries before dispatch. A transport `ACKED` phase only refines the live state: after process death, the journal may still say `POSSIBLY_SENT`, which retains the counter and requires reconciliation or interrupted-write recovery. A later durable update can include the ACK. Semantic resolution and any resulting clearance of counter uncertainty are committed together; identical availability updates do not commit. The first accepted write therefore needs three journal rotations (reservation, dispatch intent, resolution), excluding authenticated reads and other changed state. This does not batch counters or defer read replay floors.

## Settings and status

Setting ID `1` reports the active basal program (`3` = A, `10` = B). Hourly A settings occupy `14–37` and B settings `38–61`; rates are centi-units/hour. A selector write is followed on the same authenticated link by an exact selected-ID readback and separately read value. A full profile acquisition brackets the hourly reads with active-program checks and reads the pump clock. Settings and history selectors do not program therapy.

The system-status decoder accepts exactly 18 integrity-checked bytes (before the authenticated counter tail). All multi-byte numeric fields below are unsigned little-endian:

| Offset | Field | Driver interpretation |
|---:|---|---|
| 0 | mode (byte) | `3` Stop, `10` running; other modes rejected |
| 1 | reservoir (u32) | centi-units; `0xFFFFFFFF` rejects the entire status |
| 5 | battery (byte) | bars 0–5, mapped to percent in the UI |
| 6 | basal (u32) | centi-units/hour, current rate after TBR |
| 10 | TBR (u32) | percent |
| 14 | TBR remaining (u32) | minutes |

The driver requires compatible pump firmware (minimum `V05.00.52`) and control-service protocol `1.3` for trusted status. Status decoding rejects unsupported modes, lengths and out-of-range values. Firmware V05.00.52 is the physically characterized layout; newer versions pass the compatibility policy but are not individually documented here as characterized.

Protocol background: [SandraK82's BLE notes](https://github.com/SandraK82/ypsopump-research/blob/main/docs/02-ble-protocol.md) and [encryption notes](https://github.com/SandraK82/ypsopump-research/blob/main/docs/03-encryption.md). Where their counter byte order or generic request/notification flow differs, the behavior above follows this driver's authenticated characteristic-read and counter handling.
