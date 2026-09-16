# Serialized non-therapy Bluetooth write bench

This is a separate Android application and UID. It compiles the driver's real session owner,
crypto, framing, selector policy, readiness model, serialized transport and coordinator. It has
no AAPS plugin or therapy/configuration API. Its only app-initiated remote writes are:

- MD5 access authentication;
- control-notification CCCD enable (`0x0001`);
- exact 8-byte GLB selectors for event, alarm, system history and setting ID.

Complaint history is excluded because the references conflict and no target-verified UUID exists.
Setting **values**, date/time, history clearing, bolus and TBR are not writable from this artifact.
The exported operator activity requires the platform `android.permission.DUMP` permission held by
the ADB shell, preventing ordinary installed applications from invoking its actions.

## Safety boundary

Use only with a target pump in the reviewed non-therapy bench state. A selector still mutates
protocol and counter state. The app never retries a possibly effective write, never scans counters,
and never interprets bare 134/138/139 as a safe recovery instruction. A GATT-successful fragmented
write remains `AcceptedUnverified`; the app captures a value read-back but requires explicit,
measured reconciliation before another selector can run.

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

If one strict-next **event** selector at counter `N` remains `POSSIBLY_SENT` or `ACKED`, first preserve
and review its evidence bundle and record exactly one hash-bound `UNKNOWN` reconciliation record. When
that record is the sole exact match for the durable reservation, `converge-ambiguity` reserves exactly
`N + 1` with a new operation ID and different event payload:

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

The reservation durably binds the unresolved predecessor's operation ID, reservation ID, phase,
counter, characteristic, purpose, plaintext hash, exact prior floor, candidate mode and reviewed
evidence hash. The epoch's convergence marker is persisted before dispatch and permits only one
attempt. Proven not-sent or reviewed rejected/not-consumed evidence restores the exact unresolved
predecessor reservation. Acceptance or consumed rejection establishes counter `N + 1` while retaining
the older `UNKNOWN` evidence. Do not continue to the duplicate probe unless authenticated semantic
read-back and reviewed counter evidence qualify the convergence write as accepted.

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
