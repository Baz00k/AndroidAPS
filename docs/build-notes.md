# Fork build notes

FullLoop and FullDebug use the debug signing key. An `adb install -r` update requires the same
application ID and actual signing certificate; it is not a backup or a proven downgrade/recovery path.
If KSP reports stale generated types after a large refactor, run `./gradlew :app:clean` and rebuild.

## Tests and startup benchmarking

With JDK 21 and the Android SDK, run `./gradlew unitTest` for Android FullDebug/Debug and JVM
module tests, then `./gradlew -p buildSrc test` for build-tool tests. CI archives native Gradle reports.
Select the dedicated emulator by its serial (for example, `emulator-5554`):

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :benchmark:connectedFullLoopAndroidTest -PaapsTargetAbi=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```
The connected task installs/uninstalls the target package: use a fresh dedicated emulator, never an
installation with patient records or pairing credentials. It measures five unconfigured FullLoop
cold launches; emulator timing checks the harness, not hardware performance or clinical safety.
CI can run this benchmark through manual dispatch. Build/unit-test success does not replace the
required affected-flow simulator evidence before a modernization change is merged or released.
