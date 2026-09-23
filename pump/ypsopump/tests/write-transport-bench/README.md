# Serialized Bluetooth qualification bench

## Current counter recovery contract

**Counters are strictly increasing, not contiguous.** The pump accepts any higher counter.
See [write-counter recovery](../../docs/counter-recovery.md) before interpreting the historical
one-shot experiments below. Their gap limits and review gates are experimental controls, not
pump requirements. Normal profile/history acquisition and bolus transport recover interrupted
reservations automatically after teardown, preserving unknown command outcomes and advancing
above the durable allocated high-water mark. An unknown floor is normal and starts at zero: the
first candidate is counter `1`, and the same pump-confirmed search establishes the floor when the
pump accepts. A lost callback at 4281 permits 4282 without establishing whether 4281 was consumed.
Only a pump-originated final-frame counter error 139 starts a persisted exponential search
(`+1,+2,+4,+8,...`); the driver persists the rejection and redispatches the same logical write
above the retained position, and other failures never enlarge the gap. Bolus retry and cancellation
ownership remain governed by the durable bolus attempt journal.

This is a separate Android application and UID. It compiles the driver's real session owner,
crypto, framing, selector policy, readiness model, serialized transport and coordinator. It has
no AAPS plugin or configuration API. Its ordinary `BenchActivity` actions remain non-therapy. The
separate `BolusBenchActivity` is an explicit disconnected-pump qualification capability and is the
only path in this artifact that may issue a bolus start/cancel command. App-initiated
remote writes are:

- MD5 access authentication;
- control-notification CCCD enable (`0x0001`);
- exact 8-byte GLB selectors for event, alarm, system history and setting ID.
- standard/square/combination bolus start and cancellation only through `BolusBenchActivity`, after
  strict validation and durable attempt/counter journaling.

Complaint history is excluded because the references conflict and no target-verified UUID exists.
Setting **values**, date/time, history clearing and TBR are not writable from this artifact.
The exported operator activity requires the platform `android.permission.DUMP` permission held by
the ADB shell, preventing ordinary installed applications from invoking its actions.

## Safety boundary

Use only with a target pump in the reviewed non-therapy bench state. A selector still mutates
protocol and counter state. The app never retries a possibly effective write, never scans counters,
and never interprets bare 134/138/139 as a safe recovery instruction. A GATT-successful fragmented
write remains `AcceptedUnverified`; the app captures read-back but requires explicit, measured
reconciliation before another selector can run. For settings it first reads and decrypts the readable
`SETTING_ID` characteristic and requires exact GLB identity equality before reading `SETTING_VALUE`;
a valid or unchanged value alone is never treated as selector proof.

`BolusBenchActivity` is only for a pump physically disconnected from a person. It rejects non-finite,
out-of-limit, non-step and malformed duration/combination requests, stopped/empty pumps, conflicting
immediate/extended delivery, unknown counter ownership, and cancellation without a previously proven
block identity. The attempt is persisted before possible dispatch. An ACK remains
`AcceptedUnverified`; same-link status must prove a strictly newer block sequence and the exact
programmed amount. Cancellation requires that same durable identity to still be actively delivering.
Stable terminal history reconciliation remains mandatory before the evidence can claim delivered
insulin or a final cancellation outcome.

The distributed AndroidAPS artifact remains status-only: `READ_ONLY_MODE`, pump capabilities, plugin
entry points and production therapy policy are unchanged.

## Bolus qualification actions

These actions are exported only to the ADB shell through `android.permission.DUMP`. Use a pump that is
physically disconnected from a person. Complete and review the existing session/counter/profile
qualification first. `start-bolus` validates the exact standard, square (extended) or combination
request, reads running/reservoir and both bolus status blocks, captures a fingerprinted history
baseline, then persists the attempt before possible command dispatch:

```sh
WRITE_ID="bolus-start-$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BolusBenchActivity \
  --es action start-bolus --es write_id "$WRITE_ID" --es units 1.00 --es aaps_max_bolus 1.00
# square bolus: add --es duration_minutes 15
# combination:  add --es duration_minutes 15 --es immediate_units 0.40
```

`validate-bolus` performs the same validation without connecting and reports the shape, total,
duration and combination-immediate amount. Use decimal strings (`--es units 0.10`) to keep requested
amounts exact; `--ef` stores a binary float that still passes representability checks.

The same-link result establishes only `DELIVERING`: a strictly newer block sequence and the exact
pump-programmed amount are required. A standard bolus binds the fast block; square and combination
boluses bind the slow block, whose programmed total is the whole requested amount. The result does
not establish delivered insulin.

Cancellation is allowed only when the persisted proven block identity is still actively delivering
the exact intended programmed amount:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BolusBenchActivity \
  --es action cancel-bolus --es write_id "bolus-cancel-$(uuidgen)"
```

This pump delivers standard boluses at roughly 1 U/s, so a new connection cannot catch a standard
bolus before completion. Square and combination blocks run for minutes and can be cancelled across a
new connection. For a standard bolus, `--ez start_then_cancel true` proves the fast identity,
persists cancellation ownership and dispatches the cancel on the same connection with no retry:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BolusBenchActivity \
  --es action start-bolus --es write_id "$WRITE_ID" --es units 10.00 --es aaps_max_bolus 10.00 \
  --ez start_then_cancel true
```

After completion or cancellation, run terminal reconciliation. This brackets a bounded stable-history
scan, requires the exact baseline sequence and fingerprint, and compares requested, programmed,
pump-status-delivered and pump-history-delivered units. A unique compatible history row is ingestible
as confirmed insulin, but the AAPS attempt becomes terminal only when the proven block-sequence
identity also matches. Type-2 and type-3 completed rows carry the delivered amount, including partial
amounts after cancellation; a type-18 combination row reports the delivered amount at abort. Type-29,
type-30 and type-31 abort-row amounts remain unqualified and cannot create insulin.

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BolusBenchActivity \
  --es action reconcile-bolus --ei max_history_rows 128
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/result.txt
```

## Build and install

```sh
mise x java@21 -- ./gradlew -p pump/ypsopump/tests/write-transport-bench clean assembleDebug --max-workers=2
adb -s "$SERIAL" install -r pump/ypsopump/tests/write-transport-bench/build/outputs/apk/debug/YpsoWriteTransportBench-debug.apk
adb -s "$SERIAL" shell pm grant app.aaps.ypso.writebench android.permission.BLUETOOTH_CONNECT
adb -s "$SERIAL" shell pm grant app.aaps.ypso.writebench android.permission.BLUETOOTH_SCAN
```

Record source revision, APK SHA-256, signer fingerprint, phone model, Android version, pump firmware,
redacted pump identity and initial pump/controller/clock state.

## Observe current history rows and clock fields

Before any Step 08 selector attempt, capture the authenticated event count, two consecutive event
rows and the pump's date/time fields without writing the selector:

```sh
CAPTURE_ID="current-$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action capture-current-history --es write_id "$CAPTURE_ID"
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/result.txt
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/history-captures.jsonl \
  > "history-captures-$CAPTURE_ID.jsonl"
sha256sum "history-captures-$CAPTURE_ID.jsonl"
```

The action accepts the event count only as an exact non-negative GLB and each event value only as an
exact 17-byte payload with a valid trailing CRC. Event-value reads advance the target's persistent
selector, so two consecutive reads cannot prove an unchanged logical head. The action always records
`stable_head_cursor=false` with disposition `ADVANCING_SELECTOR_OBSERVATION_ONLY`; even index 0 followed
by index 1 is evidence about rows, not a cursor suitable for command attribution. Establishing such a
cursor requires a separately reviewed selector repositioning and reconciliation operation. The action
records the first four bytes as `factory_seconds` and leaves time-zone resolution outside the wire
decoder; paired target observations
now establish pump-local wall-clock seconds since 2000-01-01. Pump date/time bytes are retained verbatim
with wall/elapsed phone observations. `history-captures.jsonl` contains decrypted pump data and may identify
the operator's treatment history. Keep it protected like a raw Bluetooth trace; do not publish it.

## Atomic profile acquisition

After selector-ID read-back and strict-next accounting are qualified, acquire active-before, all 48
hourly rows, active-after, pump date/time, and an event-count bracket on one authenticated connection:

```sh
PROFILE_ID="profile-$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action acquire-profile --es write_id "$PROFILE_ID"
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/result.txt
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/profile-captures.jsonl \
  > "profile-captures-$PROFILE_ID.jsonl"
sha256sum "profile-captures-$PROFILE_ID.jsonl"
```

Each selector is strict-next and is reconciled only by an exact same-connection `SETTING_ID`
read-back before its value is collected. The action publishes a capture only when the connection,
generation, reboot, active program, event count, clock and all rows remain coherent for at most five
minutes. Any interruption leaves the current reservation durable and stops without retry.

For the manual switch rejection test, pause after a row and switch A↔B while paused:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action acquire-profile --es write_id "$PROFILE_ID" \
  --ei pause_after_setting 37 --el pause_ms 60000
```

The expected result is rejection (active-before/after mismatch and/or changed event count), with no
profile capture published for that action. Restore the intended profile manually after the test.

## Reviewed ownership handoff to AndroidAPS

After all physical runs are reviewed, create one canonical evidence manifest containing the final
protected journal/evidence/history/profile hashes, APK and signer hashes, run IDs, outcomes, and the
review decision. Review and record its SHA-256 independently. Then export the complete accounting
record; a numeric write floor is deliberately not accepted:

```sh
EVIDENCE_SHA256="64-lowercase-hex-from-independent-review"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action export-ownership-handoff \
  --es reviewed_evidence_sha256 "$EVIDENCE_SHA256"
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/ownership-handoff.json \
  > ownership-handoff.json
sha256sum ownership-handoff.json
```

The file is secret-free but HMAC-authenticated by the installed pump session key. It contains the
complete current epoch record: reboot/read/write floors, verified reservation, write-evidence chain,
retired legacy counter-33 audit record, and experimental attempt flags. It also binds the bench package,
APK signer, APK, and all protected artifact hashes. AndroidAPS requires the separately reviewed file
SHA-256 before parsing, verifies the HMAC with its already-installed pump key, checks pump/key/serial and
epoch identity, rejects unresolved or conflicting local ownership, and commits the imported record under
a fresh AndroidAPS Keystore journal revision. Copying only the numeric write counter is forbidden.

AndroidAPS no longer requires the handoff to make a session writable: an unknown write floor is
reconciled from zero against the pump through the pump-confirmed counter search. The handoff remains
the strongest transfer when the bench holds measured ownership, and it seeds the selector lower-bound
recovery state.

## Import the session; measured floors are optional validation evidence

Force-stop the app and push the canonical `ypso-keys` schema-v1 document as `ypso-keys.json`.
A separately reviewed, independently measured `write-baseline.json` may also be supplied for an
already active validation epoch; it is not a runtime prerequisite for key-only onboarding:

```json
{
  "pump": "EC:2A:F0:XX:XX:XX",
  "key_id": "64-lowercase-hex-SHA256-of-raw-key",
  "reboot": 16,
  "read": 123,
  "write": 45,
  "source": "trace filename/hash and measurement method"
}
```

```sh
adb -s "$SERIAL" shell am force-stop app.aaps.ypso.writebench
adb -s "$SERIAL" push /secure/path/ypso-keys.json /data/local/tmp/ypso-keys.json
adb -s "$SERIAL" push /secure/path/write-baseline.json /data/local/tmp/write-baseline.json
adb -s "$SERIAL" shell run-as app.aaps.ypso.writebench cp /data/local/tmp/ypso-keys.json files/ypso-keys.json
adb -s "$SERIAL" shell run-as app.aaps.ypso.writebench cp /data/local/tmp/write-baseline.json files/write-baseline.json
adb -s "$SERIAL" shell rm /data/local/tmp/ypso-keys.json /data/local/tmp/write-baseline.json
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity --es action install
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/result.txt
```

When supplied, the baseline is epoch-bound and one-time: it cannot replace an established write floor or erase an
unresolved reservation. Protect both input files; `ypso-keys.json` contains the real shared key.
After a validated pump reboot, `observe-reboot` deliberately makes the write floor uncertain. The
bounded new-epoch bootstrap below is the runtime path to establish the reset floor; an external
baseline remains optional independent validation evidence and is not required after adoption.

Without a baseline, `install` records `UNKNOWN_MID_EPOCH`. Run `observe-reboot` before physically
rebooting to learn the current authenticated reboot/read tuple without enabling writes. After the user
performs the reviewed disconnected-pump reboot, run `observe-reboot` again. Only exact authenticated
`old + 1` adoption produces `OBSERVED_NEW_EPOCH`; jumps and lower generations fail closed.

Before reboot, durably record the authenticated current event-selector value. The bootstrap action
must use the same family and rejects an identical payload, making retained-state read-back incapable
of falsely proving acceptance:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action record-bootstrap-reference --es write_id "reference-$(uuidgen)" \
  --es selector_type event --ei selector 0
```

The numeric `selector` extra identifies the family binding for this read-only action; the durable
reference value itself comes from the CRC-valid embedded history index returned by the pump.

The new epoch permits exactly one `bootstrap-new-epoch` selector at counter `1`. The session enforces
that its payload differs from the durable pre-reboot reference so an exact CRC-valid read-back can
prove acceptance. The attempted marker is persisted before dispatch and survives restart/in-place upgrade.
No retry is permitted, including after proven local not-sent. Only explicit consumed reconciliation
changes the bootstrap state to `ESTABLISHED`; unknown or not-consumed evidence leaves writes blocked.

```sh
WRITE_ID="$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action bootstrap-new-epoch --es write_id "$WRITE_ID" \
  --es selector_type event --ei selector 18
```

Family-specific alarm and system indices must come from authenticated read-only family counts, never
from guessing or a shared database index:

The count UUIDs are pinned to the `Alerts.COUNT` and `System.COUNT` mappings in
`SandraK82/ypsopump-research` revision `de7e867241fafd2fb8061ceeecf42af2883b9eb4`; target reads still
decide whether each mapping is usable. Missing, ambiguous, unauthenticated, or non-exact-GLB values fail
closed. For alarm and system families only, select the validated final index as `count - 1`, matching
their pinned reference iteration and target counts; a zero count leaves that selector family blocked.
Event history is different on the measured target: count `3000` is capacity and logical index `0` is
the moving newest head. Never use event `count - 1` as the newest cursor. See
[`docs/history-identity-time.md`](../../docs/history-identity-time.md).

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action read-history-counts --es write_id "counts-$(uuidgen)"
```

Successful non-zero counts are persisted with the authenticated reboot/read tuple, count
characteristic and plaintext response SHA-256. Alarm and system writes are rejected unless their
selector is exactly `count - 1` for durable evidence from the current reboot epoch. Reboot clears the
binding; a zero count removes it and keeps that family blocked.

Before every alarm or system selector row, read the currently selected value without writing. The
numeric extra binds the family only; the result reports the CRC-valid embedded current index and
persists that authenticated value for the current epoch. A row is rejected when the requested
`count - 1` equals the durable pre-row value, so a no-op write cannot masquerade as a changed
selection. The row consumes that observation as immutable reservation evidence, so the next
alarm/system row requires a fresh read after the previous row is reconciled:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action read-selector-state --es write_id "alarm-state-$(uuidgen)" \
  --es selector_type alarm --ei selector 0

adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action read-selector-state --es write_id "system-state-$(uuidgen)" \
  --es selector_type system --ei selector 0
```

This read-only action fails closed while a write awaits reconciliation. If the durable current value
already equals `count - 1`, choose a different reviewed index only when a later authenticated count read
makes it the new last entry; otherwise leave the family unresolved.

For setting ID `1`, read-back accepts an exact GLB or a CRC-valid response containing a GLB and records
it observationally. There is no qualified setting-ID-to-layout mapping, so it never claims a semantic
match. It never writes a setting value.

## Run one selector transaction

Example: select event index 17. Valid selector types are `event`, `alarm`, `system`, and `setting`.

```sh
WRITE_ID="$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action run-selector --es write_id "$WRITE_ID" --es selector_type event --ei selector 17
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/result.txt
adb -s "$SERIAL" exec-out run-as app.aaps.ypso.writebench cat files/write-evidence.jsonl > "evidence-$WRITE_ID.jsonl"
sha256sum "evidence-$WRITE_ID.jsonl"
```

The run performs: bonded connection → AUTH → required CCCD → encrypted exact-GLB event-count prime read → durable
reservation → exact-counter encryption → fragmented serialized write → value read-back. Evidence
contains the reviewed selector intent, callback-visible facts and redacted response-body hashes, not
keys, ciphertext or decrypted pump-response bodies.

The app supplies one Android `Handler` to `connectGatt` and posts characteristic-write processing
back to that same queue. Consequently the next frame or selector-value read cannot call another GATT
operation until the platform's write callback has returned. `RunRequested` records this as
`gatt_callback_dispatch=handler-post-after-callback`.

After successful AUTH and before required CCCD or selector dispatch, every applicable connection
reads master firmware, supervisor firmware and control protocol. Master and supervisor are accepted
by the inclusive minimum rule `>= V05.00.52`; there is no exact-firmware allowlist. The observed
control protocol must be canonical ASCII `1.3\0`.

Injected transport faults are available for software-only evidence and must be labelled injected:

```sh
# Disconnect immediately after local dispatch of frame 2.
--ei disconnect_after_frame 2

# Ignore callback 1 to emulate a lost Android callback; the whole-write deadline leaves uncertainty.
--ei ignore_callback_frame 1 --el deadline_ms 8000

# Delay a labelled duplicate of callback 1 until immediately before callback 2 is processed.
# The detected collision quarantines both callbacks without advancing either frame and leaves
# durable uncertainty; it does not invent a frame identifier.
--ei duplicate_callback_after_frame 1

# Deliver one labelled synthetic duplicate after the final real callback (legacy alias).
--ez duplicate_final_callback true
```

Use external Android Bluetooth/HCI capture for real transport traces. Hash and protect identifying
captures separately. Inbound control notifications are also recorded as raw unsigned bytes plus
length/hash, characteristic and observed firmware; they are evidence, not an automatic error
classifier.

### One-shot ambiguity convergence without reboot

The separate `recover-settings-counter` experiment permits one event selector after a reviewed
unresolved settings convergence. It reserves the next value above both attempted settings counters;
acceptance across the resulting gap is a hypothesis under test, not a pre-established pump limit.
The bench first reads the current event row and rejects selecting that row or its next descending
iterator row. Exact authenticated changed-index read-back is required for semantic acceptance.
Journal v15 preserves the complete unresolved predecessor binding; no old outcome is
rewritten as accepted. A not-sent attempt restores that binding. An ambiguous dispatched recovery
blocks another recovery. This selector-only experiment does not permit therapy commands, settings
value writes, counter scanning or journal reseeding.

After that event recovery itself remains reviewed unknown, `jump-settings-counter` permits one
explicit event-selector candidate at `4096`, provided the earlier attempted counter is lower. This
separately tests whether the assumed low write position is stale. Its three-level predecessor chain
preserves both settings attempts and the first event recovery. Exact changed-index read-back, not
the numeric callback, establishes its result. The target accepted this jump and subsequent settings
reads; it does not establish that every arbitrary jump is accepted. These qualification transitions
remain bench-only and must not become therapy retry policy.

If one strict-next **event or settings** selector at counter `N` remains `POSSIBLY_SENT` or `ACKED`, first preserve
and review its evidence bundle and record exactly one hash-bound `UNKNOWN` reconciliation record. When
that record is the sole exact match for the durable reservation, `converge-ambiguity` reserves exactly
`N + 1` with a new operation ID. Event convergence requires a different event payload; settings
convergence requires the exact same setting ID so the value can be read immediately on the same link:

```sh
WRITE_ID="converge-$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action converge-ambiguity --es write_id "$WRITE_ID" \
  --es selector_type event --ei selector 18
```

Choose an event index different from the unresolved predecessor payload and from the immediately
authenticated current selector value. There is no counter extra: the candidate is mechanically fixed
to the unresolved reservation's counter plus one. If the actual pump floor is `N - 1`, this is the
already measured `floor + 2`; if it is `N`, this is strict-next. No counter is scanned.

For settings, use the same `selector_type setting` and selector value as the unresolved predecessor.
The guard rejects a changed setting ID or selector family. This is still a non-mutating selector retry,
not a setting-value write.

The reservation durably binds the unresolved predecessor's operation ID, reservation ID, phase,
counter, characteristic, purpose, plaintext hash, exact prior floor, candidate mode and reviewed
evidence hash. The epoch's convergence marker is persisted before dispatch and permits only one
attempt. Proven not-sent or reviewed rejected/not-consumed evidence restores the exact unresolved
predecessor reservation. Acceptance or consumed rejection establishes counter `N + 1` while retaining
the older `UNKNOWN` evidence. Do not continue to the duplicate probe unless authenticated semantic
read-back and reviewed counter evidence qualify the convergence write as accepted.

Discovering a harness sequencing defect after a consumed convergence attempt does not authorize a
third selector write. If counters `N` and `N + 1` both remain unresolved, `N + 2` may be strict-next,
`floor + 2`, or an unmeasured `floor + 3`; preserve the journal and obtain counter-disposition or
equivalent target-qualified evidence first.

For the epoch-21 counter-2 uncertainty, this action therefore reserves exactly counter `3`; it replaces
the previously proposed clean-reboot procedure.

### One-shot duplicate-counter probe

Duplicate-counter behavior is measured only after a complete event-selector write has been explicitly
reconciled as accepted. The accepted predecessor may be strict-next or the bounded ambiguity-
convergence write above. The probe reuses that exact verified counter once in the current reboot epoch,
requires a new operation ID and a different event-selector payload, and durably binds the accepted
predecessor's operation ID, reservation ID, counter, characteristic, purpose, plaintext hash, prior
floor, candidate mode and accepted-evidence hash. For convergence, that accepted binding also includes
the nested unresolved counter-`N` binding. It is not a retry of an unresolved command and it cannot
target alarm, system, settings, bolus, TBR or any configuration value.

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action duplicate-counter-probe --es write_id "duplicate-$(uuidgen)" \
  --es selector_type event --ei selector 18
```

Use an event index different from the fully verified predecessor and confirm the current authenticated
cursor before the probe. The epoch marker is persisted before dispatch, so local not-sent, rejection,
ambiguity, restart and in-place install cannot permit a second duplicate probe. Successful fragment
callbacks remain `AcceptedUnverified`; classify duplicate acceptance only from authenticated semantic
read-back and reviewed counter evidence. An ambiguous result blocks later writes. Exact-next reboot
adoption may retire it only after immutable unresolved evidence matches the full duplicate and accepted-
predecessor bindings.

## Bounded target actions

These actions exist only to execute the required matrix without editing the journal or scanning
counters. Run one case, preserve evidence, and reconcile before any later write.

### Required readiness stages

`readiness-probe` omits exactly one local readiness fact, then asks the real coordinator to start the
selector. For the AUTH omission only, the other two local facts are explicitly injected and labelled
without claiming corresponding pump behavior; the probe performs no AUTH, CCCD or encrypted read.
The CCCD omission performs real AUTH and a real encrypted read but no descriptor write. Expected
result is `OUTCOME:NotSent`, with no reservation and no selector dispatch:

```sh
for STAGE in auth cccd read; do
  WRITE_ID="$(uuidgen)"
  adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
    --es action readiness-probe --es write_id "$WRITE_ID" \
    --es omit_stage "$STAGE" --es selector_type event --ei selector 17
done
```

This proves the artifact's fail-closed readiness boundary. A real AUTH or CCCD callback failure, if
observed naturally, is separately recorded with layer, characteristic, firmware and raw status; do
not induce it by changing the pump identity or sending an unreviewed AUTH payload.

Every coordinator result is appended as `CoordinatorOutcome`, including failures before transport
startup. `NotSent` also reports its failure layer, detail and optional counter in `result.txt`; do not
retry a selector from a bare outcome string or infer pump rejection from a local session failure.

If all selector frames were locally dispatched and only the final GATT callback is non-zero, the
write remains `PossiblyApplied`, but the app performs one selector-value read on that same connection
after the write callback has returned and before closing the connection. This is an experimental
timing variant, not a proven Nordic requirement, and captures semantic evidence without treating
the numeric callback as acceptance or rejection. Earlier-frame failures still close without read-back
because a complete selector request was not observed.

### One forward-gap candidate

After one reconciled **accepted** strict-next selector, `--ei forward_gap 1` reserves exactly the
candidate two above the established floor (`floor + 2`), skipping one value. Larger offsets are
rejected locally.

```sh
WRITE_ID="$(uuidgen)"
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action run-selector --es write_id "$WRITE_ID" --es selector_type event --ei selector 17 \
  --ei forward_gap 1
```

The durable reservation stores the exact prior floor. Only reviewed evidence that the candidate was
rejected and the counter was not consumed may restore that floor. Any other result remains blocked or
advances only according to explicit reconciliation. The gap-attempt marker is persisted before any
platform dispatch and cannot be cleared by not-sent or rejected reconciliation, process restart,
in-place reinstall/upgrade that preserves app data or a new measured baseline in the same epoch. An
uninstall removes the sealed journal and Android Keystore anchor, so it cannot prove the gap marker is
unused and is not a supported recovery path. Only authenticated reboot adoption starts a new epoch.
There is no loop, decrement probe or scan.

### Read-only observation while unresolved

An unresolved selector prevents every later selector write, but its value can be read on a new
authenticated connection for semantic evidence. Supply the exact pending write ID/type/value:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action observe-selector --es write_id "$WRITE_ID" --es selector_type event --ei selector 17
```

The action verifies the pending reservation's destination, purpose and plaintext hash before reading.
It performs no selector write and does not reconcile automatically.

Resolved and unresolved evidence retains the exact operation, reservation, counter, selector
characteristic, purpose, plaintext SHA-256, prior write floor and candidate mode even if a
rejected/not-consumed reconciliation clears the live reservation.

Each `PersistedReconciliation` row also records the resolved prior floor/candidate mode and the
post-reconciliation reboot/read/write snapshot. Export and hash the JSONL after reconciliation; for a
rejected/not-consumed result this row is the final evidence that the exact prior floor was restored.

### Reboot observation

First record the authenticated event-selector reference described above. A reviewed unresolved write
may remain only when immutable unresolved evidence fully binds that reservation; otherwise there must
be no unresolved write. Place the disconnected bench pump through the reviewed reboot procedure, then
run one observation:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action observe-reboot --es write_id "reboot-$(uuidgen)"
```

Only an authenticated next generation (`old + 1`) with a positive read counter is adopted, under the
same `>= V05.00.52` and control `1.3` capability policy. The response is discarded, the connection is
closed, and the write floor becomes uncertain in `OBSERVED_NEW_EPOCH`. Lower generations, jumps, zero
counters, unsupported capability identity, or an unresolved write without fully bound reviewed
evidence fail closed. Never infer that the floor reset to zero: execute the one-time counter-1
bootstrap and reconcile it only from exact semantic/counter evidence.

## Explicit reconciliation

Do not reconcile from GATT status alone. First establish selector effect and any counter disposition
needed to resolve the durable reservation with the bounded target procedure. Build one reviewed evidence
bundle containing the exported JSONL,
target trace/probe records, and operator conclusion, then copy the exact bytes into the app:

```sh
tar -cf "reviewed-evidence-$WRITE_ID.tar" "evidence-$WRITE_ID.jsonl" target-trace/ operator-conclusion.md
EVIDENCE_SHA256="$(sha256sum "reviewed-evidence-$WRITE_ID.tar" | cut -d' ' -f1)"
adb -s "$SERIAL" push "reviewed-evidence-$WRITE_ID.tar" /data/local/tmp/reconciliation-evidence.bin
adb -s "$SERIAL" shell run-as app.aaps.ypso.writebench \
  cp /data/local/tmp/reconciliation-evidence.bin files/reconciliation-evidence.bin
adb -s "$SERIAL" shell rm /data/local/tmp/reconciliation-evidence.bin

adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action reconcile --es write_id "$WRITE_ID" \
  --es semantic accepted --es counter accepted \
  --es evidence_sha256 "$EVIDENCE_SHA256" \
  --es detail "event value read-back index=17; trace SHA-256=...; next-counter probe contract=..."
```

The app independently hashes `reconciliation-evidence.bin`, requires it to match
`evidence_sha256`, and persists that binding with the reconciliation. The operator conclusion is
still reviewed evidence, not a semantic result inferred by the app from the hash.

For a measured rejection use `--es semantic rejected` with `--es counter consumed` or
`--es counter not-consumed`. Use `semantic unknown` and `counter none` only to append uncertainty;
it does not unblock another write.

## Step 07 scope and hand-off

The Step 07 target run must establish the counter and transport rules that can change production
design: exact selector encoding/read-back, strict-next and bounded-gap behavior, duplicate-counter
behavior, reboot bootstrap, readiness and error provenance. These establish that callbacks and counter
errors cannot authorize a resend, and that only one distinct `N + 1` recovery/cancellation can remain
within the measured window after an ambiguous `N`. Repeating each generic selector interruption has no
additional production decision value.

Setting/profile selection is completed with the coherent-profile work because only that ticket can
qualify setting IDs and value layouts. Bolus/TBR interruption, lost ACK, cancellation and effect
reconciliation are completed with those command implementations, using their status and stable history
identity. A selector fault cannot qualify insulin or basal attribution.

Suggested order minimizes irreversible uncertainty: metadata/capture preflight → readiness omission
probes → one strict-next event → qualified history selector families → bounded +2 candidate → one
same-counter duplicate measurement → reboot bootstrap. At every physical-state or semantic decision,
stop and obtain the operator's explicit confirmation before recording the conclusion or reconciling.

`force-stop` may not survive a system broadcast. For controller-free physical rows, temporarily run
`pm disable-user --user 0 info.nightscout.androidaps` after force-stopping AAPS, and verify no AAPS,
mylife or bench process remains. Re-enable the unchanged package with
`pm enable --user 0 info.nightscout.androidaps` during cleanup; do not uninstall or clear its data.

The candidate intentionally has no control that fabricates a pump-originated AUTH/CCCD rejection or a
physical radio/link failure. Never relabel an injected callback or app-requested disconnect as physical
target evidence. An unobserved generic selector-fault row does not block Step 07 once the production
decision above is established; carry forward only command-specific gaps that can change profile,
bolus, TBR, cancellation or final-artifact behavior.

## Cleanup

Preserve evidence, then force-stop or uninstall only `app.aaps.ypso.writebench`. Remove private input
files and verify the normal controller resumes from the reconciled counter state. Never delete or reset
the normal AAPS journal as recovery.
