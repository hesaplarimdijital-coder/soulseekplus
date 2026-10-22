# Soulseek+

Android tabletler, telefonlar ve Samsung DeX için Soulseek istemcisi. [SeekerAndroid](https://github.com/jackBonadies/SeekerAndroid)'in
Android altyapısını (SAF depolama, ön plan servisi, ağ değişiminde bağlantı kurtarma) ve
[Nicotine+](https://github.com/nicotine-plus/nicotine-plus)'ın protokol derinliğini (dağıtık arama ağı, gelişmiş filtreler,
Round-Robin/FIFO yükleme kuyruğu, 3 kademeli paylaşım) tek uygulamada birleştirir. Arayüz
[desktop-kit](https://github.com/hesaplarimdijital-coder/desktop-kit) üzerine kuruludur: dokunmatik, klavye, fare ve sağ tık
aynı uygulamada çalışır.

## İndir

Her `main` derlemesi [nightly sürümüne](https://github.com/hesaplarimdijital-coder/soulseekplus/releases/tag/nightly)
imzalı APK olarak yüklenir. Aynı APK `ci/apk` dalında da bulunur.

## Özellikler

- **Arama:** çok sekmeli arama (ağ, oda, arkadaşlar, tek kullanıcı), klasör / kullanıcı / düz liste gruplama, düzenli ifade,
  bitrate, kayıpsız, örnekleme hızı, bit derinliği, boyut, uzantı, ülke, boş yuva, kuyruk ve hız filtreleri; tek dokunuşla hızlı
  filtre çipleri.
- **İstek listesi:** sunucunun bildirdiği aralıkla otomatik arama, yalnızca yeni düşen sonuçları rozetleme ve bildirim.
- **Transferler:** klasörü tek seferde indirme, `.part` dosyasına yazma ve kopunca kaldığı bayttan devam, kullanıcı çevrimiçi
  olunca otomatik yeniden deneme, uzak kuyruk sırası takibi; yüklemede Round-Robin / FIFO, arkadaş önceliği ve hız sınırı.
- **Paylaşım:** dahili bellek, SD kart ve USB OTG klasörleri; sanal takma ad; herkese açık / yalnızca arkadaşlar / seçili grup
  kademeleri; dizin önbelleği sayesinde açılışta yeniden tarama yok.
- **Ağ:** saf Kotlin, engellemeyen (NIO) protokol motoru; doğrudan ve dolaylı (PierceFirewall) eş bağlantısı; dağıtık arama
  ağı; UPnP IGD ve NAT-PMP ile otomatik port açma; Wi-Fi ↔ mobil geçişte bağlantı kurtarma.
- **Sosyal:** sohbet odaları, oda duvar yazıları, özel mesajlar, `/komut` sistemi, kullanıcı profili ve paylaşım ağacını gezme,
  `slsk://` bağlantıları, ülke bayrakları.
- **Ses önizleme:** inen ya da paylaşılan dosyaları durum çubuğundaki mini oynatıcıda dinleme.

## Derleme

```
./gradlew :core:test :app:assembleRelease
```

Gereksinimler: JDK 17, Android SDK 35. `:core` saf Kotlin/JVM modülüdür ve Android SDK olmadan da doğrulanabilir:
`tools/verify-core.sh`. Mimari ve modül sözleşmeleri: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Protokol özeti:
[docs/slsk-protocol.md](docs/slsk-protocol.md).

### İmzalama

CI, depoda `SIGNING_KEYSTORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` sırları varsa sürüm
APK'sını o anahtarla imzalar. Sırlar yoksa önbellekte tutulan sabit bir hata ayıklama anahtarı kullanılır; ardışık
derlemeler aynı imzayı taşır ve birbirinin üzerine kurulur.

## Lisans

GPL-3.0 (bkz. [LICENSE](LICENSE)). Nicotine+ ve SeekerAndroid de GPL-3.0'dır.

Bu ürün <https://lite.ip2location.com> adresinden edinilen IP2Location LITE verilerini içerir (CC BY-SA 4.0,
[licenses/IP2Location-LITE.md](licenses/IP2Location-LITE.md)). IBM Plex yazı tipleri SIL OFL 1.1 ile lisanslıdır.
