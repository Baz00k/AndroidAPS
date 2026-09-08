#!/usr/bin/env bash
# Regression check: app consumption must execute the library guard, not only library assembly.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
for variant in Loop Debug; do
    library_variant=Debug
    if [[ "$variant" == Loop ]]; then library_variant=Release; fi
    graph="$("$root/gradlew" -p "$root" ":app:assembleFull$variant" --dry-run --console=plain --max-workers=2)"
    expected=":pump:ypsopump:verifyFull${library_variant}GattWriteOwnership SKIPPED"
    if ! grep -Fxq "$expected" <<< "$graph"; then
        echo "Missing ownership guard in app Full$variant task graph" >&2
        exit 1
    fi
    echo "Full$variant app consumption includes the Full$library_variant ownership guard"
done
