#!/usr/bin/env python3
"""Emülatör duman ve uçtan uca testi.

APK'yı kurar, açar, giriş formunu doldurur ve ekranları gezer. Her adımda ekran görüntüsü (PNG) ve görünür metinler
toplanır; sonunda uygulamanın çökmediği (süreç canlı, logcat'te FATAL EXCEPTION yok) doğrulanır.

--demo: CI'da ana makinede çalışan demo Soulseek ağına (sahte sunucu + paylaşım yapan demo eşler) 10.0.2.2:2242
üzerinden bağlanır; gerçek arama sonuçları, indirme, sohbet ve göz atma telefonda ve tablette denenir.
--demo olmadan sunucu 127.0.0.1'dir (bağlantı kurulmaz; gerçek ağda hesap açılmaz).

Kullanım: tools/smoke.py <apk> <çıktı klasörü> [--demo]
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.soulseekplus"
ACTIVITY = PKG + "/.ui.MainActivity"
USER = "slskplus_smoke"


def adb(*args, check=False, timeout=60, binary=False):
    try:
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


class Smoke:
    def __init__(self, out):
        self.out = out
        self.report = []
        self.step = 0
        self.failures = []
        os.makedirs(out, exist_ok=True)

    # ------------------------------------------------------------------ temel

    def log(self, line):
        print(line, flush=True)
        self.report.append(line)

    def fail(self, what):
        self.failures.append(what)
        self.log("  ! " + what)

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

    def nodes(self, root=None):
        root = root if root is not None else self.dump()
        return [] if root is None else list(root.iter("node"))

    def texts(self, root):
        seen = []
        for n in self.nodes(root):
            for key in ("text", "content-desc"):
                t = (n.get(key) or "").strip()
                if t and t not in seen:
                    seen.append(t)
        return seen

    def screen(self):
        out = adb("shell", "wm", "size").strip().splitlines()
        m = re.search(r"(\d+)x(\d+)", out[-1]) if out else None
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    def find(self, label, exact=True, sidebar=False, root=None):
        """[label] metni ya da içerik açıklaması olan ilk düğüm (büyük/küçük harf duyarsız eşleşme exact=False)."""
        matches = []
        for n in self.nodes(root):
            for key in ("text", "content-desc"):
                t = (n.get(key) or "").strip()
                ok = (t == label) if exact else (label.lower() in t.lower())
                if ok and self.bounds(n):
                    matches.append(n)
                    break
        if sidebar:
            w, h = self.screen()
            matches = [n for n in matches if self.bounds(n)[0] < w * 0.25 and self.bounds(n)[1] > h * 0.09]
        return matches[0] if matches else None

    def find_any(self, labels, exact=False, sidebar=False):
        root = self.dump()
        for label in labels:
            n = self.find(label, exact, sidebar, root)
            if n is not None:
                return n
        return None

    def tap_node(self, node, wait=1.5):
        x, y = self.bounds(node)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(wait)

    def long_press_node(self, node, wait=1.5):
        x, y = self.bounds(node)
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
        time.sleep(wait)

    def tap_label(self, label, exact=True, sidebar=False, required=False):
        n = self.find(label, exact, sidebar)
        if n is None:
            (self.fail if required else self.log)(f"  - '{label}' bulunamadı")
            return False
        self.tap_node(n)
        return True

    def tap_any(self, labels, exact=False, required=False):
        n = self.find_any(labels, exact)
        if n is None:
            (self.fail if required else self.log)(f"  - {labels} bulunamadı")
            return False
        self.tap_node(n)
        return True

    def wait_text(self, label, timeout=30, exact=False):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.find(label, exact) is not None:
                return True
            time.sleep(1.5)
        return False

    def edit_fields(self):
        fields = [n for n in self.nodes() if n.get("class") == "android.widget.EditText"]
        return sorted(fields, key=lambda n: (self.bounds(n) or (0, 0))[1])

    def type_into(self, node, text, submit=False):
        self.tap_node(node, wait=0.6)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(40):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", text.replace(" ", "%s"))
        time.sleep(0.6)
        if submit:
            adb("shell", "input", "keyevent", "KEYCODE_ENTER")
            time.sleep(0.8)

    def hide_keyboard(self):
        if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)

    def back(self, wait=1.2):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(wait)

    def snap(self, name):
        self.step += 1
        fname = f"{self.step:02d}-{name}.png"
        png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(os.path.join(self.out, fname), "wb") as f:
            f.write(png)
        texts = self.texts(self.dump())
        self.log(f"[{fname}] metinler: " + " | ".join(texts[:70]))

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

    # ------------------------------------------------------------------ uygulama akışları

    def nav(self, label, wide):
        """Gezinme: genişte kenar çubuğu; telefonda alt çubuk, yoksa "More/Daha fazla" ekranı."""
        if wide:
            return self.tap_label(label, sidebar=True)
        w, h = self.screen()
        root = self.dump()
        bottom = [n for n in self.nodes(root) if (n.get("text") or "") == label and self.bounds(n) and self.bounds(n)[1] > h * 0.85]
        if bottom:
            self.tap_node(bottom[0])
            return True
        more = [n for n in self.nodes(root) if (n.get("text") or "") in ("More", "Daha fazla") and self.bounds(n) and self.bounds(n)[1] > h * 0.85]
        if more:
            self.tap_node(more[0])
            return self.tap_label(label)
        self.log(f"  - gezinme '{label}' bulunamadı")
        return False

    def login(self, host):
        fields = self.edit_fields()
        self.log(f"  düzenlenebilir alan sayısı: {len(fields)}")
        if len(fields) < 2:
            self.fail("giriş formu bulunamadı")
            return
        self.type_into(fields[0], USER)
        self.type_into(self.edit_fields()[1], "smoke-pass")
        self.hide_keyboard()
        if self.tap_label("Server options"):
            more = self.edit_fields()
            if len(more) >= 3:
                self.type_into(more[2], host)
                self.hide_keyboard()
        self.snap("phone-account-filled")
        self.tap_label("Connect", required=True)

    def choose_download_folder(self):
        """Ayarlar → İndirme klasörü → Seç → SAF seçicide Music → Bu klasörü kullan → İzin ver."""
        if not self.nav("Settings", wide=False):
            return False
        for _ in range(8):
            if self.find("Download folder") is not None:
                break
            adb("shell", "input", "swipe", "540", "1700", "540", "700", "350")
            time.sleep(1)
        n = self.find("Choose")
        if n is None:
            self.fail("indirme klasörü 'Choose' düğmesi bulunamadı")
            return False
        self.tap_node(n, wait=3)
        self.snap("saf-picker")
        if self.find("Music") is None:
            self.tap_any(["Show roots"])
            self.tap_any(["sdk_gphone", "Android SDK", "Internal storage", "SDK"], exact=False)
        if not self.tap_label("Music"):
            self.fail("SAF seçicide Music bulunamadı")
            self.back()
            return False
        time.sleep(1)
        if not self.tap_any(["USE THIS FOLDER", "Use this folder"], exact=True):
            self.fail("'Use this folder' bulunamadı")
            self.back()
            return False
        self.tap_any(["ALLOW", "Allow"], exact=True)
        time.sleep(2)
        self.snap("phone-settings-download-folder")
        return True

    def phone_demo(self):
        if not self.wait_text(USER, timeout=45):
            self.fail("demo sunucusuna giriş yapılamadı (durum çubuğunda kullanıcı adı yok)")
        # Arama
        self.nav("Search", wide=False)
        fields = self.edit_fields()
        if fields:
            self.type_into(fields[0], "megadeth", submit=True)
            self.hide_keyboard()
        time.sleep(7)
        self.snap("phone-search-results")
        if self.find("Rust in Peace", exact=False) is None:
            self.fail("arama sonuçlarında demo albüm yok")
        # Filtre paneli
        if self.tap_any(["Filters"], exact=False):
            self.snap("phone-search-filters")
            self.tap_any(["Close"], exact=True)
        # Grup aç + dosya menüsü
        n = self.find("Rust in Peace", exact=False)
        if n is not None:
            self.tap_node(n)
            self.snap("phone-search-group-open")
            f = self.find(".flac", exact=False)
            if f is not None:
                self.tap_node(f)
                self.snap("phone-search-file-menu")
                self.back()
                f = self.find(".flac", exact=False)
                if f is not None:
                    self.long_press_node(f)
                    self.snap("phone-search-selection")
                    self.tap_any(["Close"], exact=True)
        # Görünüm seçenekleri (üst çubuk ⋮ → View options)
        if self.tap_any(["More", "More options", "Daha fazla"], exact=True):
            self.snap("phone-search-overflow")
            if self.tap_any(["View options"], exact=True):
                self.snap("phone-search-view-options")
                self.tap_any(["Close"], exact=True)
            else:
                self.back()

        # İndirme klasörü + klasör indirme
        folder_ok = self.choose_download_folder()
        self.nav("Search", wide=False)
        fields = self.edit_fields()
        if fields:
            self.type_into(fields[0], "boards of canada", submit=True)
            self.hide_keyboard()
        time.sleep(6)
        g = self.find("Music Has the Right", exact=False)
        if g is not None:
            self.long_press_node(g)
            self.snap("phone-search-folder-menu")
            if not self.tap_any(["Download whole folder"], exact=True):
                self.back()
        else:
            self.fail("küçük dosyalı demo klasörü aramada yok")
        time.sleep(4)
        self.nav("Transfers", wide=False)
        time.sleep(3)
        self.snap("phone-transfers-active")
        if folder_ok and self.find("%", exact=False) is None:
            self.log("  - transferlerde yüzde görünmüyor")
        time.sleep(12)
        self.snap("phone-transfers-later")
        t = self.find(".mp3", exact=False)
        if t is not None:
            self.tap_node(t)
            self.snap("phone-transfer-menu")
            self.back()

        # Sohbet: demo eşin özel mesajı
        self.nav("Chat", wide=False)
        self.snap("phone-chat")
        if self.tap_label("vinyl_archive"):
            self.snap("phone-chat-private")
            self.back()

        # Göz at
        if self.nav("Browse", wide=False):
            fields = self.edit_fields()
            if fields:
                self.type_into(fields[0], "jazz_corner", submit=True)
                self.hide_keyboard()
                time.sleep(4)
            self.snap("phone-browse")

        for label in ("Wishlist", "Users", "Shares", "User info", "About"):
            if self.nav(label, wide=False):
                self.snap("phone-" + label.lower().replace(" ", "-"))

    def tablet_pass(self, demo):
        adb("shell", "wm", "size", "2560x1600")
        adb("shell", "wm", "density", "280")
        time.sleep(4)
        for label in ("Search", "Transfers", "Wishlist", "Chat", "Users", "Browse", "User info", "Shares", "About", "Settings"):
            if self.tap_label(label, sidebar=True):
                self.snap("tablet-" + label.lower().replace(" ", "-"))
        if self.tap_label("Search", sidebar=True):
            fields = self.edit_fields()
            if fields:
                self.type_into(fields[0], "megadeth" if demo else "aphex twin", submit=True)
                self.hide_keyboard()
                time.sleep(6 if demo else 2)
                self.snap("tablet-search-query")


def main():
    apk, out = sys.argv[1], sys.argv[2]
    demo = "--demo" in sys.argv[3:]
    s = Smoke(out)
    adb("install", "-r", "-g", apk, check=True, timeout=300)
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")

    # --- Telefon: ilk açılış ve giriş
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=120)
    time.sleep(6)
    s.snap("phone-first-run")
    s.login("10.0.2.2" if demo else "127.0.0.1")
    time.sleep(5)
    s.snap("phone-home")
    if demo:
        s.phone_demo()
    else:
        for label in ("Transfers", "Chat"):
            if s.nav(label, wide=False):
                s.snap(f"phone-{label.lower()}")

    # --- Tablet / DeX benzeri geniş pencere
    s.tablet_pass(demo)

    # --- slsk:// bağlantısı
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d",
        "slsk://someuser/Music/Album/01%20Track.flac", PKG, timeout=60)
    time.sleep(3)
    s.snap("tablet-slsk-link")
    s.back()

    # --- Türkçe arayüz (uygulamaya özel dil, API 33+)
    adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "tr-TR")
    time.sleep(2)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, timeout=120)
    time.sleep(6)
    s.snap("tablet-tr-home")
    for label in ("Transferler", "Ayarlar"):
        if s.tap_label(label, sidebar=True):
            s.snap("tablet-tr-" + label.lower())
    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")
    time.sleep(3)
    s.snap("phone-tr-home")
    adb("shell", "cmd", "locale", "set-app-locales", PKG, "--locales", "en-US")

    alive = s.alive()
    crash, full = s.crashes()
    with open(os.path.join(out, "logcat.txt"), "w") as f:
        f.write("\n".join(l for l in full.splitlines() if PKG in l or "AndroidRuntime" in l or "Slsk+" in l))
    ok = True
    if not alive:
        s.log("SONUÇ: süreç canlı değil")
        ok = False
    if crash:
        s.log("SONUÇ: çökme bulundu:\n" + "\n".join(crash))
        ok = False
    if s.failures:
        s.log("UYARILAR: " + "; ".join(s.failures))
    s.log("SONUÇ: " + ("BAŞARILI" if ok else "BAŞARISIZ"))
    with open(os.path.join(out, "report.txt"), "w") as f:
        f.write("\n".join(s.report) + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
