# Pump protocol used by the driver

## Connection and encrypted messages

Android must be bonded to the pump. The driver authenticates access with an MD5-derived password from the BLE address and a fixed protocol salt, verifies the claimed serial against an independently observed bonded name or GATT serial, and reads encrypted status with the imported 32-byte session key. These are separate checks. The supported identity format is eight decimal digits beginning with `10`.

Application payloads use XChaCha20-Poly1305 with a 24-byte nonce **appended** to ciphertext and tag. Decrypted responses end with a 12-byte authenticated little-endian reboot/read-counter tail. The session journal commits the authenticated read floor before publishing a CRC- and schema-valid status. Replay, lower or jumped reboot generations and malformed replies do not publish data. A compatible next-reboot observation adopts the new read floor and reconnects; it does not infer a write floor. The journal seals credentials and replay state using Android Keystore and retains same-key replay history across imports.

## Write counters and command outcomes

The journal's write counter is a durable *allocated high-water mark*, not necessarily the pump's last consumed counter. The first candidate with an unknown floor is 1. Reservations and dispatch intent are persisted before frames are sent. The pump accepts higher write counters without requiring contiguous values. An interrupted write retains its allocation and unknown command outcome; the next operation uses a higher counter. An ordinary history selector write interrupted by a lost connection is retired when the next connection authenticates, provided no history transport still holds it: a selector move has no therapy effect, so it must not block the next therapy command. Therapy writes and lower-bound recovery selectors stay blocking until resolved. History selector writes persist RESERVED, POSSIBLY_SENT and their read-back resolution, but not the transport ACK, which proves nothing POSSIBLY_SENT does not already record. Only a pump-originated **final-frame 139** proves counter rejection and permits an exponential search (`1, 2, 4, 8, …` relative to the recovery baseline). An accepted write establishes the floor. A lost callback, disconnect or unrelated numeric error never proves rejection or delivery. Counter recovery does not resolve a bolus attempt: that requires command-specific status and history evidence.

### Final-frame write status codes

The pump reports a command result as the GATT status of the characteristic-write callback for the **final** frame of an encrypted write. Codes below were observed on firmware V05.00.52 (2026-09-23 bench, pump not attached to a person). In every observed case the rejected command had no pump effect: status and event history were unchanged afterwards. A code on a non-final frame, a missing callback or an unlisted code has no measured meaning and remains an unknown outcome.

| Code | Hex | Observed on | Meaning |
|---:|---:|---|---|
| 130 | `0x82` | TBR start/stop | Parameter out of range (e.g. percent 510, duration 1–14 or 1455 minutes) |
| 134 | `0x86` | TBR start | A TBR is already active; the new TBR was not started |
| 135 | `0x87` | TBR start | The pump is in Stop mode; the TBR was not started |
| 139 | `0x8B` | any encrypted write | Write counter too low; see above |
| 140 | `0x8C` | any encrypted access | Shared key no longer accepted; see [driver behavior](driver.md) |

### Temporary basal command

`START_STOP_TBR` is written to `669a0c20-0008-969e-e211-fcbee38b7bc5`. Its 16-byte plaintext is `percent (u32 LE) || ~percent || minutes (u32 LE) || ~minutes`; the complements are the integrity check and no CRC is appended. The pump accepts percent 0–500 in 1% steps and durations 15–1440 minutes in 1-minute steps over BLE, which is wider than the pump's own menu (0–200% in 10% steps, 15-minute steps).

| Command | TBR already active | No TBR active | Pump stopped |
|---|---|---|---|
| X% for N minutes (N ≥ 15, X ≠ 100) | rejected 134, no effect | TBR starts | rejected 135, no effect |
| any percent for 0 minutes | TBR ends immediately | accepted, no effect | accepted, no effect |
| 100% for N minutes (N ≥ 15) | TBR ends immediately | accepted, no effect | not measured |

There is no native replacement: changing a running TBR requires a stop and then a start. The driver uses 100% for 0 minutes as its stop command. A successful start is visible in system status (percent and remaining minutes) within one second; remaining minutes may already read one below the request. The status basal is the scheduled rate scaled by the percentage and rounded to 0.01 U/h, so a low percentage can read 0.00 U/h.

A pump **Stop** ends a running TBR; **resuming** does not restore it. A TBR can be started while an immediate or extended bolus is delivering; neither command interrupts the other, and stopping the TBR leaves a running extended bolus unchanged.

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
