# Status protocol evidence

## Firmware policy

The intended minimum firmware is **V05.00.52**, inclusive. Older firmware is unsupported.
Newer firmware is a compatibility assumption based on the reported CamAPS minimum and a
user report of successful operation on a newer pump; it is not evidence that every version
has been tested. Firmware eligibility and validation of a received status layout are separate
checks. A service-version string must never stand in for pump firmware.

## Sources and unresolved differences

Research references are pinned to
[`de7e867241fafd2fb8061ceeecf42af2883b9eb4`](https://github.com/SandraK82/ypsopump-research/tree/de7e867241fafd2fb8061ceeecf42af2883b9eb4).

- `docs/02-ble-protocol.md` describes SDK command indices, not bytes to prepend to reads.
  Its write/notification read flow differs from the working characteristic-read/EXTREAD flow.
- `docs/03-encryption.md` describes an appended nonce but its example prepends one. Its
  big-endian counter description differs from independently decrypted target responses.
- `ypsopump-test/.../ble/YpsoPumpUuids.kt` claims observations on V05.02.03. Its master
  characteristic is `669a0c20-0008-969e-e211-fcbeb0147bc5`. Its service-version names and
  binary version formatting need target confirmation.
- The scaffold's status decoder and test-app layouts are not interchangeable. Successful
  permissive decoding is insufficient to select one.

## Initial observations

The existing AAPS app on a Samsung SM-S918B, Android 16, reads system status from
`669a0c20-0008-969e-e211-fcbee48b7bc5`, then reads successive frames from
`669a0c20-0008-969e-e211-fcff000000ff`. Observed headers are `14 24 34 44`, with
19, 19, 19 and 15 payload bytes respectively (72 bytes after reassembly).

Independent system-libsodium decryption with the current locally provisioned key succeeds
using ciphertext/tag followed by a 24-byte nonce, no associated data. The resulting plaintext
is 32 bytes: 18 status bytes, 2 integrity bytes, then a 12-byte counter tail. Observed tail
values decode as reboot 16 and incrementing read counters in little-endian order.

The operator confirmed a disconnected bench pump, a just-finished square bolus and an
active 130% TBR with approximately 2.5 hours remaining. Status offset 10 is u32 LE 130;
offset 14 decreases by five across five-minute samples (164, 159, 154, 149). This is evidence
for a remaining-minutes hypothesis, pending precisely paired display observations.
Offset 5 is 2. It is not yet sufficient evidence for the full delivery-mode enum.

Raw captures and real keys are retained privately. Public fixtures must use synthetic keys
and record the transformation, original trace hash and transformed fixture hash.

## Open evidence

Paired physical observations corrected the provisional decoder:

| Offset | Wire type | Observations | Interpretation |
|---|---|---|---|
| 0 | u8 | 10 running (normal and TBR), 3 stopped | Observed operating states only; other enums unknown. This pump offers Stop, not Pause |
| 1 | u32 LE | 4102 then 4099 | Reservoir centi-units; display 41.0 U |
| 5 | u8 | Battery bars (0–5) | Reported unit; UI shows mapped percent |
| 6 | u32 LE | 78 at 130% TBR, 60/50/35 across profiles and hours, 0 stopped or 0% TBR | Current basal centi-units/hour; operator-confirmed (0.60 profile A, 0.35 profile B) |
| 10 | u32 LE | 130 TBR, 100 normal/stopped | Basal percentage |
| 14 | u32 LE | 144 TBR, 0 normal/stopped | Remaining TBR minutes |

The previous `batteryPercent = data[6]` and `deliveryMode = data[5]` interpretation was
incorrect. In particular it generated a false empty battery on Stop. Battery percentage
is now unknown; the single observed bar value does not justify a percentage conversion.
The captured basal value describes reported rate, not independently measured physical
delivery. It does not verify the stored profile or enable therapy readiness.

Replacing the battery while stopped moved offset 5 with the display bars (2→3), a fresh
battery read 5/5, removal read 0, and reinserting a used battery read 3 — all matching the
pump display each time. Offset 5 is battery bars. After resuming basal and programming
a square bolus of 0.50 U over 15 minutes on the disconnected pump, system mode remained
10 and basal was 60. The separate bolus body reported extended status 1, injected 17,
total 50, elapsed 5 and total duration 15, all numeric fields u32 LE. Other bolus terminal
states and immediate/combo blocks still require physical observations.

### Public fixture provenance

`CapturedStatusProtocolTest` uses independently re-encrypted frames from the private
filtered logcat trace SHA-256
`a8c635b5ba500ba4720610dedb11143849f2e355e4088fb8dc0a6b72bbf09722`.
The transformation utility authenticates original frames using system libsodium, retains
body and integrity bytes, replaces the counter tail with synthetic reboot 8/read sequence,
and re-encrypts with public key bytes 00..1f and deterministic synthetic nonces. No driver
encoder or crypto mock generates these ciphertexts.

| Capture | Original concatenated frames SHA-256 | Public envelope SHA-256 |
|---|---|---|
| Stopped, 2 bars | `f48c390186d158116cf611891665454c14d804c083d16687acf22a3ca89d5ad1` | `96eb9f314481b74fb72eaef83c8073e5eb1ee828312d0a356f716d07ad511421` |
| Square bolus, 5/15 minutes | `fcc54ce971d62b34fb11f0056ab14ef3334f86471f70f285e96fe48890c9dba6` | `0f3b239c003360d45a4750dfc4579ddb67356bfdb933af3d5a813675ccf191ba` |

The capture app was a locally built FullDebug instrumentation candidate, not a final
qualified artifact. Its decoder still logged the old incorrect labels; raw bytes and
operator observations, not those labels, establish the corrections above.

### Local bench capture mode

An explicit `ypso_protocol_capture` boolean in private `ypso_ble_state` preferences opts a
debuggable installed artifact into bolus-status reads. While its pump tab is visible, the
same flag queues status reads every five seconds when the AAPS queue is idle. Navigation
away stops this cadence. Non-debuggable artifacts ignore the flag. Disable it after the
bench session. It does not authorize any additional GATT writes.

Routine polling reads firmware identity and system status; bolus collection is diagnostic.
Cancelling the square bolus produced an idle all-zero body at the next five-minute poll;
no distinct cancelled transition was observed at that cadence. Fast five-second bench
captures then observed: mixed bolus (0.50 U immediate + 0.50 U over 15 minutes) with
extended status 3 while actively delivering (extended injected 50, total 100; combo
immediate sub-block 50/50 at offsets 26/30, not separately published), progressing elapsed minutes 1 then 2 and
returning directly to idle after pump-side cancellation; standard 1.00 U bolus with
immediate status 1 and 0.79 U reported before returning to idle.

Rewinding, rebooting and leaving the disconnected pump without a cartridge produced a
no-cartridge alarm on the pump display. System status authenticated and CRC-validated,
but decodes to mode 3 with reservoir `0xFFFFFFFF`, three battery bars, zero basal and
100%/0 minutes. The sentinel fails closed: no reservoir measurement is published.
Standard serial (0x2A25) is absent on this pump, so serial identity remains an evidence gap.

### Battery representation

Offset 5 is battery bars (0–5). `batteryLevel` maps bars × 20 for AAPS consumers that expect
a percentage; the driver's tile and short status show that percent.

A pump-side 0% TBR for 60 minutes reads mode 10 with basal 0.00 U/h, percent 0 and
remaining 59 minutes one minute in — running, not suspended. Zero basal with a running mode
is therefore valid and distinct from Stop (mode 3, basal 0). Pump-side cancellation of that
TBR transitioned directly from 57 minutes remaining to percent 100 / remaining 0 / basal
0.50 within one five-second poll — no intermediate state. Switching the active basal profile
from A to B later moved the reported rate 0.50→0.35 U/h with mode running at 100%, and the
operator confirmed profile B programs 0.35 U/h this hour. The rate tracks the active profile,
but status never identifies which profile is active.

Extended public fixtures from private filtered logcat trace SHA-256
`ffcdadf9cf58cf0ef382fed5b05a09a959b89f80c964fc7d95df4182545ee54e`:

| Capture | Original concatenated frames SHA-256 | Public envelope SHA-256 |
|---|---|---|
| 0% TBR, 59 min remaining | `49a3db518bc689a0f3f6902c511056167818ab2677028a12e73ff217619b03e8` | `61f9d847e415a70d3dd8d1a8c8742845e930dcad2403bd654e843e41460f0447` |

| Capture | Original concatenated frames SHA-256 | Public envelope SHA-256 |
|---|---|---|
| Mixed active, status 3 | `fb83455dd8081bdb0e1b94760d488b50dcd00521c7d46a14a983dd888139963d` | `023ecddee71da91c41f6ccc687cd5d673ebf422beb2617a30d210762a0289285` |
| Standard active, status 1 | `56627c76b39321d5a205d295acd5a6982d6e8f63a590ba712521cd95b4e8adab` | `378e516d08b664fe404f3282da6bb5b410e5d97979823747e44cc056cd5f1607` |
| No-cartridge sentinel | `8488055744c1d842677fc62adaf30e098cc2598f5494a670dccff850f5d87789` | `a577aeff53b21e95c873cba5ea5cd3319d5d5a7da542782d8fb8a8b3fc222b86` |

Target discovery returned master and supervisor `V05.00.52\0`, base `1.1\0`, settings
`1.7\0`, history `1.4\0`, and control `1.3\0`. Standard firmware revision (0x2A26) is
absent; software revision (0x2A28) is binary `00 02 02 01` and is not pump firmware.
Idle bolus status reassembles from six frames into 96 bytes, decrypting to 42 zero status
bytes plus two integrity bytes and the counter tail.

Containing service UUIDs,
bolus-status integrity/layout, exact field ranges and enums, zero-total framing, physical
stopped/paused/bolusing/supply/fault cases, and independent publication fixtures are pending.
The initial observations alone do not qualify the complete schema or all firmware versions.
