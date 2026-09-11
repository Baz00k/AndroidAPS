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

Injected transport faults are available for software-only evidence and must be labelled injected:

```sh
# Disconnect immediately after local dispatch of frame 2.
--ei disconnect_after_frame 2

# Ignore callback 1 to emulate a lost Android callback; the whole-write deadline leaves uncertainty.
--ei ignore_callback_frame 1 --el deadline_ms 8000
```

Use external Android Bluetooth/HCI capture for real transport traces. Hash and protect identifying
captures separately.

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

## Cleanup

Preserve evidence, then force-stop or uninstall only `app.aaps.ypso.writebench`. Remove private input
files and verify the normal controller resumes from the reconciled counter state. Never delete or reset
the normal AAPS journal as recovery.
