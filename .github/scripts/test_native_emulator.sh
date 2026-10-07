#!/usr/bin/env bash
set -euo pipefail

# Disposable emulator only: installation/instrumentation can remove application data.
page_size="$1"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb shell getconf PAGE_SIZE | tr -d '\r')" = "$page_size"
adb shell getprop ro.build.fingerprint
if [ "$page_size" = 16384 ]; then
    adb shell setprop bionic.linker.16kb.app_compat.enabled false
    adb shell setprop pm.16kb.app_compat.disabled true
    test "$(adb shell getprop bionic.linker.16kb.app_compat.enabled | tr -d '\r')" = false
    test "$(adb shell getprop pm.16kb.app_compat.disabled | tr -d '\r')" = true
fi

./gradlew :app:installFullLoop -PaapsTargetAbi=x86_64 --console=plain
./gradlew :native-tests:connectedFullLoopAndroidTest -PaapsTargetAbi=x86_64 \
    -Pandroid.testInstrumentationRunnerArguments.expectedPageSize="$page_size" \
    --no-parallel --console=plain

# Keep performance tracing on the existing 4 KB job. The 16 KB image has a Perfetto
# FTRACE_STATUS_PARTIAL_PAGE_READ failure. Strict 16 KB FullLoop startup is also
# blocked by JNA/libsodium (#164/#165); only shipped Libre 3 JNI is tested there.
if [ "$page_size" = 4096 ]; then
    ./gradlew :database:impl:connectedFullDebugAndroidTest :benchmark:connectedFullLoopAndroidTest \
        -PaapsTargetAbi=x86_64 \
        -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
        --no-parallel --continue --console=plain
fi
