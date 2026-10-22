#!/usr/bin/env python3
"""Emülatör duman testi: APK'yı kurar, açar, giriş formunu doldurur, ekranları gezer.

Her adımda ekran görüntüsü (PNG) ve görünür metinler toplanır; sonunda uygulamanın çökmediği (süreç canlı,
logcat'te FATAL EXCEPTION yok) doğrulanır. Gerçek Soulseek ağında hesap açılmasın diye sunucu 127.0.0.1'dir.

Kullanım: tools/smoke.py <apk> <çıktı klasörü>
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.soulseekplus"
ACTIVITY = PKG + "/.ui.MainActivity"


def adb(*args, check=False, timeout=60, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


class Smoke:
    def __init__(self, out):
        self.out = out
        self.report = []
        self.step = 0
        os.makedirs(out, exist_ok=True)

    def log(self, line):
        print(line, flush=True)
        self.report.append(line)

    def dump(self):
        for _ in range(3):
            adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=30)
            raw = adb("shell", "cat", "/sdcard/ui.xml")
            if raw.strip().startswith("<?xml"):
                try:
                    return ET.fromstring(raw[raw.index("<hierarchy"):])
                except (ET.ParseError, ValueError):
                    pass
            time.sleep(1)
        return None

    @staticmethod
    def bounds(node):
        m = re.findall(r"\d+", node.get("bounds", ""))
        if len(m) != 4:
            return None
        x1, y1, x2, y2 = map(int, m)
        return (x1 + x2) // 2, (y1 + y2) // 2

    def nodes(self, root):
        return [] if root is None else list(root.iter("node"))

    def texts(self, root):
        seen = []
        for n in self.nodes(root):
            for key in ("text", "content-desc"):
                t = (n.get(key) or "").strip()
                if t and t not in seen:
                    seen.append(t)
        return seen

    def find(self, label, exact=True):
        root = self.dump()
        for n in self.nodes(root):
            for key in ("text", "content-desc"):
                t = (n.get(key) or "").strip()
                if (t == label) if exact else (label.lower() in t.lower()):
                    return n
        return None

    def tap_label(self, label, exact=True):
        n = self.find(label, exact)
        if n is None:
            self.log(f"  ! '{label}' bulunamadı")
            return False
        x, y = self.bounds(n)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)
        return True

    def edit_fields(self):
        root = self.dump()
        fields = [n for n in self.nodes(root) if n.get("class") == "android.widget.EditText"]
        return sorted(fields, key=lambda n: (self.bounds(n) or (0, 0))[1])

    def type_into(self, node, text):
        x, y = self.bounds(node)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.6)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(40):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", text.replace(" ", "%s"))
        time.sleep(0.6)

    def snap(self, name):
        self.step += 1
        fname = f"{self.step:02d}-{name}.png"
        png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(os.path.join(self.out, fname), "wb") as f:
            f.write(png)
        texts = self.texts(self.dump())
        self.log(f"[{fname}] metinler: " + " | ".join(texts[:60]))

    def alive(self):
        return adb("shell", "pidof", PKG).strip() != ""

    def crashes(self):
        log = adb("logcat", "-d", "-v", "brief", timeout=120)
        lines = log.splitlines()
        out = []
        for i, line in enumerate(lines):
            if "FATAL EXCEPTION" in line or ("AndroidRuntime" in line and "E/" in line):
                out.extend(lines[i:i + 40])
                break
        return out, log


def main():
    apk, out = sys.argv[1], sys.argv[2]
    s = Smoke(out)
    ok = True
    adb("install", "-r", "-g", apk, check=True, timeout=300)
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")

    # --- Telefon boyutu: ilk açılış ve hesap formu
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=120)
    time.sleep(6)
    s.snap("phone-first-run")
    fields = s.edit_fields()
    s.log(f"  düzenlenebilir alan sayısı: {len(fields)}")
    if len(fields) >= 2:
        s.type_into(fields[0], "slskplus_smoke")
        s.type_into(s.edit_fields()[1], "smoke-pass")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")  # klavyeyi kapat
        time.sleep(1)
        if s.tap_label("Server options"):
            more = s.edit_fields()
            if len(more) >= 3:
                s.type_into(more[2], "127.0.0.1")
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(1)
        s.snap("phone-account-filled")
        s.tap_label("Connect")
        time.sleep(5)
    s.snap("phone-home")
    for label in ("Transfers", "Chat"):
        if s.tap_label(label):
            s.snap(f"phone-{label.lower()}")

    # --- Tablet / DeX benzeri geniş pencere
    adb("shell", "wm", "size", "2560x1600")
    adb("shell", "wm", "density", "280")
    time.sleep(4)
    s.snap("tablet-home")
    for label in ("Search", "Transfers", "Wishlist", "Chat", "Users", "Browse", "Shares", "Settings", "About"):
        if s.tap_label(label):
            s.snap(f"tablet-{label.lower()}")
    # Arama sekmesi: bağlı değilken arama kutusuna yaz ve gönder
    if s.tap_label("Search"):
        fields = s.edit_fields()
        if fields:
            s.type_into(fields[0], "aphex twin")
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            time.sleep(2)
            s.snap("tablet-search-query")

    # --- slsk:// bağlantısı
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
        "slsk://someuser/Music/Album/01%20Track.flac", PKG, timeout=60)
    time.sleep(3)
    s.snap("tablet-slsk-link")

    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")

    alive = s.alive()
    crash, full = s.crashes()
    with open(os.path.join(out, "logcat.txt"), "w") as f:
        f.write("\n".join(l for l in full.splitlines() if PKG in l or "AndroidRuntime" in l or "Slsk+" in l))
    if not alive:
        s.log("SONUÇ: süreç canlı değil")
        ok = False
    if crash:
        s.log("SONUÇ: çökme bulundu:\n" + "\n".join(crash))
        ok = False
    s.log("SONUÇ: " + ("BAŞARILI" if ok else "BAŞARISIZ"))
    with open(os.path.join(out, "report.txt"), "w") as f:
        f.write("\n".join(s.report) + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
