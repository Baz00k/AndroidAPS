# Fork build notes

Both FullLoop and FullDebug currently use the debug signing key, allowing `adb install -r` to preserve
app data. If KSP reports stale generated types after a large refactor, run `./gradlew :app:clean` and rebuild.

## Repeatable validation evidence

From a committed checkout with Python 3.11+, JDK 21 and the Android SDK installed, run
`python3 tools/validation/baseline.py --output /absolute/new/evidence-directory`.
Use `--help` for diagnostic dirty-tree runs, emulator ABI/metadata and Gradle resource limits.
The runner cleans module outputs, builds the FullDebug/FullLoop phone/watch APKs and FullLoop
benchmark harness, discovers explicit JVM/FullDebug unit-test tasks (including the separate
buildSrc build), and records commands, exit codes, SDK package revisions, XML case counts,
failures/skips, APK hashes, manifest identity and verified signer fingerprints. No-source tasks
are listed; source-file counts are not test-discovery counts. It does not run dosing replays,
other flavors or the entire existing Android instrumentation suite.

The `Validation evidence` CI workflow also exercises cold launch on a fresh API-35 emulator.
Benchmarks stop/relaunch the app and reset its compilation. Use only an isolated emulator or
verified non-patient-connected test hardware, never a daily-therapy installation.
The connected test task installs and uninstalls its target package. Treat existing app data and
Keystore credentials as at risk: use a fresh dedicated fixture, never an installation with records.
For an isolated test emulator, run `:benchmark:connectedFullLoopAndroidTest` with
`-PaapsTargetAbi=x86_64`. Only on an emulator, add
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
The harness targets **FullLoop**, not a lookalike build type or different flavor. Its five cold
launches use `CompilationMode.None`; a fresh installation exercises unconfigured launch,
including first-run permission state, not a configured virtual-pump workload. Emulator timing verifies the harness, not hardware
performance or battery life. Keep image revision, fixture, compilation mode and workload
identical for comparisons; five iterations are not an approved performance tolerance.

Build/test success is not modernization qualification. The runner explicitly leaves promotion
blocked until the required platform/paired smoke evidence, hardware measurements and agreed
tolerances, and update/recovery evidence are supplied in the owning GitHub issues. CI archives
only diagnostic evidence, not signing keys or publicly distributed APKs. Inspect evidence for
sensitive information before sharing a local run.

## Updating and recovery

A matching application ID **and verified signing certificate** are prerequisites for an in-place
update. Preserve the exact signing key securely; a newly generated debug key on another machine
will not match merely because it is called a debug key. The runner records actual certificates;
it does not prove compatibility with an installed app. Phone and watch artifacts must remain
consistently signed. A failed signature check is a stop condition, never a reason to uninstall.

An `adb install -r` update is not a backup or a validated recovery procedure. Settings exports are
not complete database/Android Keystore backups. An older APK cannot be assumed to understand a
newer database schema, and copying app files does not necessarily restore Keystore-backed pairing
credentials. Until the exact schema pair and credential recovery have been exercised on isolated
synthetic fixtures, treat downgrade/restore as **unverified and blocked**; retain data and pursue a
forward repair. Never clear/uninstall a therapy installation or replay stale delivery records to
make a failed update work. This tooling neither authorizes delivery testing nor modifies devices.
