#!/usr/bin/env bash
# Runs one TbrBenchActivity action on the attached phone and prints its report.
# Usage: tbr-bench.sh observe [history_rows]
#        tbr-bench.sh write <percent> <duration_minutes> [observe_delays_ms]
#        tbr-bench.sh write-during-bolus <percent> <duration_minutes>  (start a bolus on the pump within 60 s)
set -euo pipefail
pkg=app.aaps.ypso.writebench
adb shell am force-stop net.sinovo.mylife.app || true
adb shell am force-stop "$pkg"
adb shell run-as "$pkg" rm -f files/tbr-result.txt
case "${1:-}" in
  observe) extras=(--es action observe --ei history_rows "${2:-6}") ;;
  write) extras=(--es action write-tbr --ei percent "$2" --ei duration_minutes "$3" --es observe_delays_ms "${4:-0,2000,6000}") ;;
  write-during-bolus) extras=(--es action write-tbr --ei percent "$2" --ei duration_minutes "$3" --es observe_delays_ms 0,3000 --ez wait_for_bolus true) ;;
  *) echo "usage: $0 observe [rows] | write <percent> <minutes> [delays]" >&2; exit 2 ;;
esac
adb shell am start -W -n "$pkg/.TbrBenchActivity" "${extras[@]}" >/dev/null
for _ in $(seq 1 120); do
  sleep 2
  if adb shell run-as "$pkg" test -f files/tbr-result.txt; then break; fi
done
adb shell run-as "$pkg" cat files/tbr-result.txt
