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
JAVA_HOME=/path/to/jdk21 PATH="$JAVA_HOME/bin:$PATH" \
  ./gradlew -p pump/ypsopump/tests/write-transport-bench clean assembleDebug --max-workers=2
adb -s "$SERIAL" install -r pump/ypsopump/tests/write-transport-bench/build/outputs/apk/debug/YpsoWriteTransportBench-debug.apk
adb -s "$SERIAL" shell pm grant app.aaps.ypso.writebench android.permission.BLUETOOTH_CONNECT
adb -s "$SERIAL" shell pm grant app.aaps.ypso.writebench android.permission.BLUETOOTH_SCAN
```

Record source revision, APK SHA-256, signer fingerprint, phone model, Android version, pump firmware,
redacted pump identity and initial pump/controller/clock state.

## Import the session and measured floors

Force-stop the app. Push the canonical `ypso-keys` schema-v1 document as `ypso-keys.json` and a
separately reviewed, independently measured baseline as `write-baseline.json`:

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

The baseline is epoch-bound and one-time: it cannot replace an established write floor or erase an
unresolved reservation. Protect both input files; `ypso-keys.json` contains the real shared key.
After a validated pump reboot, `observe-reboot` deliberately makes the write floor uncertain; a new
independently measured baseline for that adopted epoch may then establish it. This is not a reset of
an existing floor.

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

The run performs: bonded connection → AUTH → required CCCD → encrypted prime read → durable
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

With no unresolved write, place the disconnected bench pump through the reviewed reboot procedure,
then run one observation:

```sh
adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.writebench/.BenchActivity \
  --es action observe-reboot --es write_id "reboot-$(uuidgen)"
```

Only an authenticated next generation (`old + 1`) with a positive read counter is adopted, under the
same `>= V05.00.52` and control `1.3` capability policy. The response is discarded, the connection is
closed, and the write floor becomes uncertain. Lower generations, jumps, zero counters, unsupported
capability identity, or any unresolved write fail closed. Independently measure the new epoch's write
floor before installing a new `write-baseline.json`; never infer that it reset to zero.

## Explicit reconciliation

Do not reconcile from GATT status alone. First establish selector effect and counter consumption with
the bounded target procedure. Build one reviewed evidence bundle containing the exported JSONL,
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

## Required target evidence before issue closure

Repeat against the exact candidate for GLB/integrity behavior, authorization/CCCD failure, strict-next
versus gaps, each rejection's counter consumption, reboot, interruption at every fragment, lost ACK,
duplicate callback and error-layer classification. Record expected/observed pump and app effects,
initial/final counters, trace hashes, cleanup and hand-back. Injected cases do not substitute for
unobserved physical cases.

Suggested order minimizes irreversible uncertainty: metadata/capture preflight → readiness omission
probes → one strict-next event → remaining selector families → bounded +2 candidate → fragment/lost
ACK/duplicate/disconnect cases one at a time → reboot last. At every physical-state or semantic
decision, stop and obtain the operator's explicit confirmation before recording the conclusion or
reconciling.

The candidate intentionally has no control that fabricates a pump-originated AUTH/CCCD rejection or a
physical radio/link failure. If external capture and the target setup cannot safely produce a required
physical row, record it as **unobserved/blocking** and stop closure; never relabel an injected callback
or app-requested disconnect as physical target evidence.

## Cleanup

Preserve evidence, then force-stop or uninstall only `app.aaps.ypso.writebench`. Remove private input
files and verify the normal controller resumes from the reconciled counter state. Never delete or reset
the normal AAPS journal as recovery.
