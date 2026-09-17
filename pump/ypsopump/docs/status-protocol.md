# Status protocol evidence

## Firmware policy

The intended minimum firmware is **V05.00.52**, inclusive. Older firmware is unsupported.
Newer firmware is a compatibility assumption based on the reported CamAPS minimum and a
user report of successful operation on a newer pump; it is not evidence that every version
has been tested. Firmware eligibility and validation of a received status layout are separate
checks. A service-version string must never stand in for pump firmware.
Trusted status and the debug capture path additionally require the observed control
protocol version, canonical ASCII `1.3\0`. Missing, malformed or changed control versions
fail closed. Settings/history versions do not gate these control-status reads.

## Status capability matrix (V05.00.52, bench pump)

| Field / publication boundary | Wire source | Observed states | Fails closed on |
|---|---|---|---|
| Reservoir (U) | System status @1, u32 LE centi-units | Running, stopped, TBR, bolus; 0–17129 observed | `0xFFFFFFFF` sentinel (no/empty cartridge), > 20000, wrong length |
| Battery (%) | System status @5, bars 0–5, shown as bars × 20 | Bars 0, 2, 3, 5 against display; 100% on full | Bars > 5, wrong length |
| Suspended | System status @0 == 3 | Stop verified twice; mode 10 running | Mode outside 3/10 |
| TBR percent (published) | System status @10, u32 LE | 130% (~2.5 h), 0% (1 h), cancellation to 100% | Percent > 500, wrong length |
| TBR remaining (decoder diagnostic only) | System status @14, u32 LE minutes | Countdown and cancellation to 0; natural expiry unobserved | Remaining > 1440, wrong length |
| Basal rate (diagnostic) | System status @6, u32 LE centi-units/h | Profile A/B rates, TBR scalings, 0 stopped/0% TBR | > 40.00 U/h, nonzero while stopped |
| Bolus activity (diagnostic) | Bolus status, 42 B | Immediate active, mixed active, square active, idle | Non-42 B, unknown codes, injected > total, elapsed > total |
| Firmware identity | Master + supervisor characteristics | `V05.00.52` strict `Vxx.xx.xx\0` match, minimum gate | Malformed, absent, or below minimum → no trusted status |

Not published: serial (characteristic absent), active profile identity, measured delivery,
terminal bolus outcomes (idle is ambiguous), delivery-halting alarms beyond the cartridge
sentinel (hypothesized same sentinel, unconfirmed).

## Settings selector observation (2026-09-16)

The preserved Android GATT cache declares setting ID `669a0c20-0008-969e-e211-fcbeb3147bc5`
at value handle `0x001b` with properties `0x0a` (`READ | WRITE`), and setting value at handle
`0x001d` with the same properties. Production reconciliation therefore requires an authenticated,
same-connection exact-GLB read-back of the requested setting ID before accepting its separately read
value. This avoids treating an equal or stale hourly value as proof of selector acceptance. Physical
qualification of the encrypted setting-ID read-back remains required; the declaration alone proves
capability, not semantics.

A single bounded setting-ID `1` attempt on target firmware `V05.00.52` used the authenticated
settings selector characteristic `669a0c20-0008-969e-e211-fcbeb3147bc5` at durable write counter
34. Android accepted all four local frame dispatches. GATT callbacks succeeded for frames 1–3;
frame 4 returned raw status `139`. The write therefore remains durably `POSSIBLY_SENT` rather
than being classified as accepted or rejected.

A fresh authenticated, read-only observation of the corresponding setting-value characteristic
`669a0c20-0008-969e-e211-fcbeb4147bc5` returned raw status `131`, with no decryptable body.
The first harness version had already closed the selector connection before that read. A second
fresh-connection read reproduced status `131`; neither observation reproduces the reference
implementation's immediate same-connection selector-value read and therefore cannot determine
whether the selected setting is connection-scoped.

After preserving counter `34` as hash-bound `UNKNOWN`, a one-shot convergence selector repeated
setting ID `1` at counter `35`. This candidate was safe whether the pump floor was `33` or `34`
because both strict-next and forward-gap-by-one selector behavior had already been measured on this
target. Frames 1–3 again returned status `0`; frame 4 again returned `139`. The harness then read
`SETTING_VALUE` on the same authenticated GATT connection, but it dispatched that read synchronously
from the final `onCharacteristicWrite` call before the Android callback returned. This alone does not
establish a sequencing defect: Nordic BLE 2.8.0 also calls `nextRequest(true)` inside its write callback,
and that method directly dispatches queued reads/writes. The earlier claim that Nordic necessarily
waits for callback return was incorrect. Likewise, `131` shares AOSP's `GATT_DB_FULL` numeric value,
but the native GATT client also passes a peer ATT error byte through as the operation status. The
numeric label does not establish local origin; a correlated HCI/ATT trace is needed. Counter `35` remains
durably `POSSIBLY_SENT` with reviewed `UNKNOWN` evidence; the epoch's one-shot convergence gate is
consumed and no third selector attempt is permitted.

The bench harness now supplies a single handler to `connectGatt` and posts characteristic-write
processing to that same queue, so every later frame and final value read starts only after the prior
platform callback returns. This is an untested timing variant, not a demonstrated fix for status `131`.
It does not retroactively classify counter `35`. With counters `34` and `35` unresolved, `36` could
be strict-next, `floor + 2`, or the unmeasured `floor + 3`. This limits the existing qualification gate;
it is not counter scarcity or proof that further selector-only experiments are inherently unsafe.
A new recovery experiment must explicitly define its non-therapy scope, evidence and recovery path.

### Wire-level error origin and official-client error map

A subsequent fresh-link value-only observation, with full phone HCI logging enabled, reproduced
`131`. The capture contains an outgoing ATT Read Request for handle `0x001d` followed approximately
47 ms later by an incoming ATT Error Response for that request/handle with byte `0x83`. No settings
selector was written in this observation. This establishes peripheral origin for this reproduced
failure, not Android-local database exhaustion. The HCI archive SHA-256 is
`abda3254fa7146b0c980f28aa12c2b77495585d5ed0ec20103c70211c5537526`.

The installed mylife APK (SHA-256
`059e629365d50d1546bac6d49a72bb9463d2ca31436c50e637412e483043d501`) contains the
`ePumpErrorCode` enum in its DeviceImport assembly. Its application-error map names `131`
`APPERR_INVALID_ID`, `138` `APPERR_DECRYPT_ERROR`, `139` `APPERR_COUNTER_ERROR`, and `141`
`APPERR_FRAGMENTATION_ERROR`. Its profile importer uses GLB setting ID `1` and reads hourly basal
settings through the same selector/value UUIDs. These are official-client code observations, not
proof of counter consumption or successful target profile reads. In particular, the counter-error
label must be reconciled with the earlier history-selector observations before reclassifying them.

The working hypothesis is that the value read lacks a valid selected setting because selector
processing failed. Counter synchronization, selector framing and settings authorization remain
testable causes; callback-return timing is not an established explanation.

### Counter recovery and active-program read-back

The handler-post variant at counter `36` did not change requested event selection: authenticated
CRC-valid index `3` before became `4` afterward, not requested `17`. HCI confirmed a peripheral ATT
error `0x8b` on the final write. Independent decryption of the captured request verified GLB `17`,
reboot `21`, write counter `36`, and frame lengths `20/20/20/4`. This excludes a wrongly encoded
counter or requested index for that attempt. It also demonstrates an ascending iterator here, so
earlier read-back coincidences must not be treated as changed-selection evidence without a pre-read.

A single deliberate counter jump to `4096` then produced four successful callbacks and an
authenticated CRC-valid changed event index `5 → 17`. The journal retained all older unknown
attempts and reconciled this new selector as accepted. No pump reboot or journal reset was used.
The previously assumed low write position was unsuitable; the exact prior pump counter and how it
advanced are not established. This result does not prove acceptance of every possible counter gap.

From that verified position, setting `1` at counter `4097` succeeded and returned an authenticated
exact 8-byte GLB `3`, paired with operator-confirmed active Profile A. After a manual A→B switch,
counter `4098` returned exact GLB `10`, paired with operator-confirmed Profile B. Thus this target
V05.00.52 supports active-program read-back; the earlier errors were not evidence of unsupported
settings firmware. GLB redundancy is the value integrity check here; a separate CRC is not present.

All 48 hourly slots were subsequently read and individually reconciled against operator-transcribed
pump schedules. Profile A occupies settings `14–37`; B occupies `38–61`. Values are centi-units/hour:

```
A: 45 45 45 60 60 75 75 75 60 60 60 50 50 50 50 50 50 50 50 50 50 50 50 50
B: 30 30 35 45 45 55 55 65 60 40 40 35 35 35 35 45 45 45 45 40 35 35 35 35
```

Active-program observations before/after and at every resumed segment returned `10`, with the
operator leaving B active. The final accepted counter was `4152`. Four connection-establishment
failures (`133`) occurred before authentication/reservation; pre/post journals were byte-identical
and only those not-sent rows were resumed. This segmented capture qualifies layout and units; it is
not evidence of one atomic uninterrupted production acquisition or of mid-read switch detection.
Combined redacted hourly evidence SHA-256:
`fed9c11bf388b4390f43c5d337e9f547da7f976dd32d49d10208ba56fab0365c`.

The operator subsequently restored A; setting `1` returned GLB `3` at accepted counter `4153`,
completing the reverse B→A pairing. An authenticated clock observation returned date bytes
`ea070910` and time bytes `17351c`, decoding to 2026-09-16 23:53:28 pump-local. The operator
confirmed the displayed pump date/time matched the phone. Precise automated skew bounds and
cross-midnight/DST acquisition remain software/target qualification work, not established by that
display comparison alone.

### Production selector identity and atomic profile qualification (2026-09-17)

The journal-preserving bench first qualified encrypted same-link `SETTING_ID` read-back at strict-next
counter `4154`: requested ID `14`, exact GLB ID read-back `14`, then exact GLB value `45`. The accepted
resolution is bound to reviewed evidence SHA-256
`b613af4207a7de13a9e984b224c9414ac36427932d2877d1db2ba6f8c9f44f83`; GATT callbacks were retained
only as transport evidence and did not establish semantic acceptance.

An uninterrupted one-connection acquisition then verified active A before/after, settings `14–61`,
pump date/time, and stable event count `3000` in 71 seconds. It ended at write counter `4204`; the
protected profile-capture hash is
`99fac0802b8fc7b960a979bb0c150af1637e052a6777c84cdd598ac93bae2360`. The decoded A and B schedules
exactly matched the previously qualified 24-hour centi-U/h schedules.

A second valid same-connection run bracketed active B, paused after setting `37`, and the operator
manually switched B→A. The run resumed and rejected with `atomic profile coherence validation failed`;
the profile-capture file remained byte-identical at the hash above, proving no incoherent publication.
The final selector was verified at counter `4280`, the pump was left stopped on Profile A, and other
controllers were force-stopped. Final protected hashes were journal
`fef03d2cd68b4cbabbe1bfe8921bb79bf3fc4c5694626249f4949577f4b13e43`, evidence
`e0d846ed4d19df7f8f9f1caffcec157b20613b5825fdc2d51fc6165b0865e54e`, history
`bbad34be816f1d2f7b81b395925db3331c9e6158100cca8e2c42fc27308ca60d`, and profile capture as above.

Production ownership is transferred only through the reviewed, HMAC-authenticated complete journal
record. AndroidAPS requires an independently supplied file SHA-256 and validates pump/key/serial, epoch,
verified reservation, evidence chain, and local non-conflict before committing under its own Keystore.
Importing or seeding numeric floor `4280` alone is explicitly unsupported.

The pinned SandraK82 repository implements this sequence but explicitly says its payloads still
require real-pump verification. A separate researcher reported viewing Profiles A and B on a real
pump, but published neither firmware identity nor raw selector/value traces. The target result here
therefore differs from that reported success and is not the same as the separately reported
configuration-write authorization error `8`.
These observations do not establish active A/B decoding, setting-value framing, schedule units,
or write-counter consumption. They block later selector writes pending explicit reconciliation and
require profile coherence to remain unavailable/fail-closed.

Responses rejected by envelope, AEAD, counter-tail or counter-freshness validation do not
mutate session counters. Later CRC/schema/firmware rejection publishes no status; an
authenticated fresh response advances its durable replay floor before publication. See
[session ownership](session-ownership.md) for restart, migration and unsupported transitions.

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

The observed AAPS status-read sequence reads system status from
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
| 1 | u32 LE | Up to 17129 on a fresh 160 U cartridge | Reservoir centi-units; bound 20000 |
| 5 | u8 | Battery bars (0–5) | Reported unit; UI shows mapped percent |
| 6 | u32 LE | 78 at 130% TBR, 60/50/35 across profiles and hours, 0 stopped or 0% TBR | Current basal centi-units/hour; operator-confirmed (0.60 profile A, 0.35 profile B) |
| 10 | u32 LE | 130 TBR, 100 normal/stopped | Basal percentage |
| 14 | u32 LE | 144 TBR, 0 normal/stopped | Remaining TBR minutes |

The previous `batteryPercent = data[6]` and `deliveryMode = data[5]` interpretation was
incorrect. In particular it generated a false empty battery on Stop. Offset 5 is battery
bars shown as bars × 20 percent; offset 6 is the reported basal rate, not measured physical
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

### Bench recovery
Pump-side Bluetooth off during polling rejected the in-flight read immediately and a
connect attempt hit the 8 s handshake deadline without hanging. With Bluetooth back on,
the next cycle reconnected, re-authenticated and resumed successful reads within seconds;
session counters survived (reads accepted, no re-key). No app restart needed.

Setting the pump clock to a wrong time changed nothing in system status: reads continue to
decode identically, as expected — the body carries no timestamps. Clock skew matters only
for history/time conversion, owned by a later ticket.

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
no-cartridge alarm on the pump display. Forcing the alarm with an empty second cartridge
produced the byte-identical body, so the pump does not distinguish empty from missing
cartridge in system status; the sentinel covers both. Hypothesized to cover every alarm that
halts delivery (occlusion, faults), but only the two cartridge alarms are confirmed —
unconfirmed by choice, not by gap: any alarm encoding outside the validated ranges fails
closed by construction, so enumerating every alarm is unnecessary for status-only safety. System status authenticated and CRC-validated,
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

### Containing-service discovery

On 2026-09-08 at 16:46:37, the non-debuggable FullLoop metadata-logging build observed:

| Service UUID | Relevant characteristics |
|---|---|
| `fb349b5f-8000-0080-0010-0000feda0000` | System status `669a0c20-0008-969e-e211-fcbee48b7bc5`, bolus status `669a0c20-0008-969e-e211-fcbee28b7bc5`, control version `669a0c20-0008-969e-e211-fcbee08b7bc5` |
| `fb349b5f-8000-0080-0010-0000feda0002` | EXTREAD `669a0c20-0008-969e-e211-fcff000000ff` |

The five discovery-metadata log lines have SHA-256
`7cb54cf4e16210be416efc5eb00128e91cca078af5eff788658d0726e84c9cfa`.
The capture APK SHA-256 is
`003c5af7ca129ec9d00499455bede413807e042d139ac0ee0fee59ffc83d2645`.
It was built from the working review-fix candidate after `78e9c4cc57`, before service-scoped
lookup was added. Normal status resumed on the same pump: 166.52 U, 3 battery bars,
0.50 U/h, mode 10. This observation establishes the mapping; it is not final-artifact
acceptance of the subsequent lookup gate.

Remaining evidence gaps are exact physical range limits, unobserved alarm/terminal-bolus
enums, natural TBR expiry, and zero-total framing semantics (zero-total is rejected).
This pump has Stop, not Pause. Stop, active boluses and no/empty-cartridge cases have the
observations above; other fault states remain unobserved. Independent real-crypto fixtures
cover stopped and zero-TBR publication as well as decoder/rejection cases. Basal rate and
TBR remaining minutes are decoder diagnostics, not published framework measurements.
Evidence covers one pump firmware and one Android device/OS, not all eligible versions.
# Production acquisition integration — 2026-09-17

Final lifecycle hardening binds cached evidence to the live GATT/session/reboot owner at every
lookup, independently of disconnect callbacks. Cached evidence also invalidates permanently on
zone changes, DST-offset transitions, and phone wall-clock drift beyond 30 seconds relative to
monotonic elapsed time. Tests cover owner loss/restoration, both DST transitions, clock jumps,
and status-poll reacquisition after sentinel invalidation. Polling remains the detection boundary
for a manual change; no push-history or instantaneous remote-change detection is claimed.

The lifecycle-hardened APK `44f4b7ec2944881f4dc8a148e9f1251e2d03d3dcb549306fd59aa9ebe6875764`
repeated production coherent acquisition at 12:16:55 phone-local: Profile A, event count 3000,
60,052 ms. Ownership inspection after normal disconnect showed reboot 21, read 3538, write 4535,
final selector VERIFIED, and 444 evidence records. The preceding candidate also ran another
automatic acquisition at 12:03:21 (62,912 ms); both runs account for the additional 102 selectors.
The bench's four protected hashes remained unchanged; AAPS was force-stopped after the final run.
Final software suite: 375 tests, zero failures/errors/skips. DST/clock faults and cached-sentinel
changes are injected test evidence; physical evidence covers complete acquisition, manual program
switching and mid-read switch rejection, plus production restart/reconnect acquisition.

The subsequent production candidate explicitly published coherent Profile A at 12:00:06 phone-local
time, with stable authenticated event count 3000 and acquisition duration 77,554 ms. Its APK SHA-256
was `ed8c68fe6064edba8e473dd1e2e4d9d854d2a0993e647f6dee954d09bbfb28f6`.
An ownership inspection immediately before publication showed reboot 21, write floor 4433, final
selector VERIFIED, 342 evidence records, and the original reviewed handoff/retired record intact.
Two additional 51-selector sequences followed the initial run: one automatic app restart on the
preceding candidate and the explicitly logged final candidate. No counter gap or recovery was used.
The operator confirmed the pump disconnected from a person, stopped, on Profile A. The loop was
disabled; mylife and the bench were quiescent. AAPS was force-stopped after capture to prevent further
automatic acquisition. This proves production publication; cached-sentinel physical fault injection
has not yet been performed. Software tests cover sentinel changes/failures/cancellation.

The production acquisition now carries its authenticated event count into immutable profile
evidence. Status polling checks that count before reusing fresh evidence; a changed or failed check
invalidates the cache. This is a conservative polling sentinel, not production history-row ingestion.
Disconnect still invalidates evidence, so a normal command-queue disconnect requires a complete
acquisition on the next connection. The sentinel does not extend the original freshness deadline.

The full manager acquisition test exposed an integration error in the final GATT guard: encrypted
selector frames were being checked as plaintext GLB. Plaintext selector allowlisting now remains at
the coordinator boundary before reservation/encryption; the GATT boundary permits only the exact
frame during that coordinator's synchronous dispatch. Cancellation disconnects the owned GATT and
prevents queued continuations from reserving more selectors.

Software validation: 372 module tests passed with zero failures/errors/skips, including the complete
50-selector sequence with real encryption and mocked GATT, changed-history rejection, malformed
identity rejection, cancellation, and authenticated cache-sentinel tests. Standalone bench tests and
APK build passed. Both debug and release GATT write-ownership checks passed.

An initial production-phone run advanced the imported strict-next write floor from 4280 to 4331
(50 acquisition selectors plus a changed-identity witness), with the last reservation VERIFIED and
the evidence chain growing from 189 to 240 entries. Read floor became 3106, reboot remained 21,
and the reviewed handoff plus retired legacy record remained present. This records selector
reconciliation only: the first candidate lacked explicit successful profile-publication logging,
so final coherent publication is not claimed from the counter progression alone. The command queue
disconnected normally after the run. All four protected bench hashes matched their pre-run values.
