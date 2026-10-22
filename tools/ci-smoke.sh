#!/usr/bin/env bash
# CI emülatör adımı: her şeyi smoke.log'a yazar ve asla erken düşmez; sonuç smoke.log'daki işaretlerle belirlenir.
exec > smoke.log 2>&1
set -x
APK="$1"
adb devices
adb shell getprop sys.boot_completed
adb forward tcp:2234 tcp:2234 || echo "adb forward failed"
nc -z 127.0.0.1 2242 && echo "demo server reachable on host" || echo "demo server NOT reachable"
python3 tools/smoke.py "$APK" smoke-out --demo || echo "SMOKE_FAILED"
exit 0
