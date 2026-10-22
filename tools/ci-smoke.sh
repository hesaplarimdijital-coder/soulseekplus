#!/usr/bin/env bash
# CI emülatör adımı: her şeyi smoke.log'a yazar ve asla erken düşmez; sonuç smoke.log'daki işaretlerle belirlenir.
exec > smoke.log 2>&1
set -x
APK="$1"
adb devices
adb shell getprop sys.boot_completed
# adb forward kullanılmaz: ana makine tarafında bağlantıyı hemen kabul edip uygulamaya ulaşamadığında veriyi sessizce
# yuttuğu için eşlerin "doğrudan bağlantı"sı yanıltıcı biçimde başarılı görünür. Emülatör, NAT arkasındaki bir telefon
# gibi davranır: eşler ona yalnızca dolaylı yoldan (sunucu ConnectToPeer + PierceFirewall) ulaşır.
adb shell setprop log.tag.SlskPlus DEBUG
adb logcat -G 16M || true
nc -z 127.0.0.1 2242 && echo "demo server reachable on host" || echo "demo server NOT reachable"
python3 tools/smoke.py "$APK" smoke-out --demo || echo "SMOKE_FAILED"
exit 0
