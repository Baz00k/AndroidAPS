# Fork build notes

Both FullLoop and FullDebug currently use the debug signing key, allowing `adb install -r` to preserve
app data. If KSP reports stale generated types after a large refactor, run `./gradlew :app:clean` and rebuild.
