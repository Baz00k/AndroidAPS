# Session journal process-death bench

This separate Android application compiles the driver's actual crypto/session sources.
It has its own UID and synthetic records, no Bluetooth permissions and no access to AAPS
data. Use a dedicated test installation; `reset` destroys this bench application's journal
and anchors. It must never be adapted to reset a real pump session.

## Build and install

From the repository root, using JDK 21 and a configured Android SDK:

```sh
./gradlew -p pump/ypsopump/tests/session-bench assembleDebug --max-workers=2
adb -s "$SERIAL" install -r pump/ypsopump/tests/session-bench/build/outputs/apk/debug/YpsoSessionBench-debug.apk
```

Record the source revision, APK SHA-256, signer fingerprint, phone model and Android version.
Unlock the phone before invoking the activity. Always specify the intended device serial.

## Procedure for each checkpoint

1. Force-stop the bench and initialize the synthetic read floor to 100:

   ```sh
   adb -s "$SERIAL" shell am force-stop app.aaps.ypso.sessionbench
   adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.sessionbench/.BenchActivity --es action reset
   adb -s "$SERIAL" exec-out run-as app.aaps.ypso.sessionbench cat files/result.txt
   ```

   Require `BASELINE:100` before proceeding. Activity startup completion does not itself
   prove the operation completed; inspect the result and logcat.

2. Commit read floor 101 with the selected checkpoint:

   ```sh
   adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.sessionbench/.BenchActivity --es action commit --es fault "$CHECKPOINT"
   adb -s "$SERIAL" logcat -d -v threadtime -s YpsoSessionBench:I ActivityManager:I '*:S'
   ```

   Preserve the `KILL:<checkpoint>` log and process-death observation. Android may restart
   the activity and overwrite `result.txt`, so the final result file alone is insufficient
   proof that the intended checkpoint was reached.

3. Force-stop and launch a fresh inspector process:

   ```sh
   adb -s "$SERIAL" shell am force-stop app.aaps.ypso.sessionbench
   adb -s "$SERIAL" shell am start -W -n app.aaps.ypso.sessionbench/.BenchActivity --es action inspect
   adb -s "$SERIAL" exec-out run-as app.aaps.ypso.sessionbench cat files/result.txt
   ```

4. Record the result, process IDs, complete trace hash and any unexpected activity restart.
   Repeat from `reset` for the next checkpoint.

| Checkpoint | Acceptable fresh-inspector result |
|---|---|
| `before-create` | `READ:100`: no mutation and no successful commit return |
| `after-create`, `before-delete`, `after-delete`, `before-truncate` | `UNAVAILABLE:...` |
| `after-truncate`, `partial-write` | `UNAVAILABLE:...` |
| `before-sync` | `READ:101` or `UNAVAILABLE:...`; never `READ:100` |
| `after-sync` | `READ:101` |

In the recorded bench run, both before-sync and after-sync recovered 101.
This establishes observed process-death behavior, not sudden-power-loss durability.
The checkpoint signal is synced before killing the process; that can affect timing and
does not emulate an uncontrolled power failure. No claim about physical pump effects
follows from this synthetic storage test.

## Cleanup

After preserving evidence, force-stop or uninstall **only** `app.aaps.ypso.sessionbench`.
Return the normal AAPS application to the foreground and confirm its working status.
