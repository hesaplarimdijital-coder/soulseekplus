#!/usr/bin/env bash
# CI emülatör adımı: her şeyi smoke.log'a yazar ve asla erken düşmez; sonuç smoke.log'daki işaretlerle belirlenir.
exec > smoke.log 2>&1
set -x
APK="$1"
adb devices
# Tanı: donanım hızlandırma ve yük (yavaş emülatör ile uygulama kaynaklı yavaşlığı ayırt etmek için).
ls -l /dev/kvm || true
nproc; uptime; free -m
adb shell uptime
adb shell top -b -n 1 -m 15 || true
adb shell getprop sys.boot_completed
# adb forward kullanılmaz: ana makine tarafında bağlantıyı hemen kabul edip uygulamaya ulaşamadığında veriyi sessizce
# yuttuğu için eşlerin "doğrudan bağlantı"sı yanıltıcı biçimde başarılı görünür. Emülatör, NAT arkasındaki bir telefon
# gibi davranır: eşler ona yalnızca dolaylı yoldan (sunucu ConnectToPeer + PierceFirewall) ulaşır.
# Açılıştan hemen sonra yüklü emülatörde Pixel Launcher gibi sistem uygulamaları ANR verip "isn't responding"
# penceresi açıyor; pencere odağı çalınca yazılan metin kayboluyor ve test girişte takılıyor. Pencereleri kapat
# (uygulamanın kendi ANR'si smoke.py'de logcat'ten yakalanır) ve sistemin biraz durulmasını bekle.
adb shell settings put global hide_error_dialogs 1 || true
for i in $(seq 1 30); do
  load=$(adb shell cat /proc/loadavg | cut -d' ' -f1)
  awk -v l="$load" 'BEGIN { exit !(l < 4.0) }' && break
  sleep 2
done
adb shell uptime
adb shell setprop log.tag.SlskPlus DEBUG
adb logcat -G 16M || true
nc -z 127.0.0.1 2242 && echo "demo server reachable on host" || echo "demo server NOT reachable"
python3 tools/smoke.py "$APK" smoke-out --demo || echo "SMOKE_FAILED"
uptime
adb shell top -b -n 1 -m 15 || true
exit 0
