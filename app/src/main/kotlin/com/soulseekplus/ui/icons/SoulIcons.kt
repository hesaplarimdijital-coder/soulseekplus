package com.soulseekplus.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import desktopkit.components.DeskIcons

/**
 * Soulseek+ simgeleri. DeskIcons standardında: 16 x 16 ızgara, 1.6 çizgi kalınlığı, yuvarlak uç ve birleşimler.
 * Kitte karşılığı olan simgeler (Download için Downloads, Pause, Resume, Close, Delete...) doğrudan `DeskIcons`tan alınır.
 */
object SoulIcons {

    // ------------------------------------------------------------------ P2P ağ düğümü (bağlantı durumu)

    /** Bağlı: dolu merkez düğüm ve üç eşe giden sürekli bağ. */
    val P2pConnected: ImageVector by lazy {
        DeskIcons.build(
            "P2pConnected",
            DeskIcons.fill("M8 5.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z"),
            DeskIcons.stroke("M3 2.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M13 2.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M8 12.2a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M4.1 4.9L6.3 6.7M11.9 4.9L9.7 6.7M8 10.2V12.2"),
        )
    }

    /** Bağlanıyor: boş merkez düğüm ve noktalı bağlar. */
    val P2pConnecting: ImageVector by lazy {
        DeskIcons.build(
            "P2pConnecting",
            DeskIcons.stroke("M8 5.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z"),
            DeskIcons.stroke("M3 2.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M13 2.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M8 12.2a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M4.4 5.2h.01M5.5 6.1h.01M11.6 5.2h.01M10.5 6.1h.01M8 11.1h.01"),
        )
    }

    /** Çevrim dışı: boş düğümler ve üstlerinden geçen çizgi. */
    val P2pOffline: ImageVector by lazy {
        DeskIcons.build(
            "P2pOffline",
            DeskIcons.stroke("M8 5.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z"),
            DeskIcons.stroke("M3 2.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M13 2.7a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M8 12.2a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 1 1 0-2.6z"),
            DeskIcons.stroke("M1.8 14.2L14.2 1.8"),
        )
    }

    // ------------------------------------------------------------------ kilit

    val LockClosed: ImageVector by lazy {
        DeskIcons.build(
            "LockClosed",
            DeskIcons.stroke("M4.5 7.5h7a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M5.5 7.5V5.5a2.5 2.5 0 0 1 5 0v2"),
            DeskIcons.stroke("M8 10.3h.01"),
        )
    }

    val LockOpen: ImageVector by lazy {
        DeskIcons.build(
            "LockOpen",
            DeskIcons.stroke("M4.5 7.5h7a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M5.5 7.5V5.5a2.5 2.5 0 0 1 4.8-1"),
            DeskIcons.stroke("M8 10.3h.01"),
        )
    }

    // ------------------------------------------------------------------ yükleme yuvası

    /** Boş yükleme yuvası: tepsiye giren ok ve onay. */
    val SlotFree: ImageVector by lazy {
        DeskIcons.build(
            "SlotFree",
            DeskIcons.stroke("M2.5 9.5v3a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-3"),
            DeskIcons.stroke("M5.6 6.4l1.8 1.8 3.2-3.6"),
        )
    }

    /** Dolu yuva (kuyruk var): tepsi ve bekleme çizgileri. */
    val SlotBusy: ImageVector by lazy {
        DeskIcons.build(
            "SlotBusy",
            DeskIcons.stroke("M2.5 9.5v3a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-3"),
            DeskIcons.stroke("M5.5 5.5h5"),
        )
    }

    // ------------------------------------------------------------------ istek listesi

    val WishlistStar: ImageVector by lazy {
        DeskIcons.build("WishlistStar", DeskIcons.stroke("M8 2l1.8 3.7 4 .6-2.9 2.8.7 4L8 11.2l-3.6 1.9.7-4-2.9-2.8 4-.6z"))
    }

    /** Radar: periyodik istek listesi taraması. */
    val WishlistRadar: ImageVector by lazy {
        DeskIcons.build(
            "WishlistRadar",
            DeskIcons.stroke("M2 8a6 6 0 1 1 12 0a6 6 0 1 1-12 0z"),
            DeskIcons.stroke("M5.2 8a2.8 2.8 0 0 1 2.8-2.8"),
            DeskIcons.stroke("M8 8l3.4-3.4"),
            DeskIcons.stroke("M8 8h.01"),
        )
    }

    // ------------------------------------------------------------------ arama sonucu rozetleri

    /** Kayıpsız ses rozeti: altıgen ve onay. */
    val Lossless: ImageVector by lazy {
        DeskIcons.build(
            "Lossless",
            DeskIcons.stroke("M8 1.8l5 2.9v5.6l-5 2.9-5-2.9V4.7z"),
            DeskIcons.stroke("M5.8 8.2l1.6 1.6 3-3.4"),
        )
    }

    val SpeedGauge: ImageVector by lazy {
        DeskIcons.build(
            "SpeedGauge",
            DeskIcons.stroke("M2.5 11.5a5.5 5.5 0 1 1 11 0"),
            DeskIcons.stroke("M8 11.5l2.6-3.8"),
            DeskIcons.stroke("M8 11.5h.01"),
        )
    }

    // ------------------------------------------------------------------ sohbet

    /** Tek konuşma balonu (özel sohbet). */
    val Chat: ImageVector by lazy {
        DeskIcons.build(
            "Chat",
            DeskIcons.stroke("M3.5 2.5h9a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H8L5 13v-2.5H3.5a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z"),
        )
    }

    /** İki balon (oda). */
    val ChatRoom: ImageVector by lazy {
        DeskIcons.build(
            "ChatRoom",
            DeskIcons.stroke("M2.5 2.5h7a1 1 0 0 1 1 1v3.5a1 1 0 0 1-1 1H6L3.8 9.2V7H2.5a1 1 0 0 1-1-1V3.5a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M10.5 6.5h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-.8v2L10.2 11.5H7.5a1 1 0 0 1-1-1V9.5"),
        )
    }

    val Send: ImageVector by lazy {
        DeskIcons.build("Send", DeskIcons.stroke("M2 8l12-5.5L9.6 14 7.8 9.2z"), DeskIcons.stroke("M7.8 9.2L14 2.5"))
    }

    // ------------------------------------------------------------------ port eşleme

    /** UPnP / NAT-PMP eşlendi: yönlendirici ve yayılan sinyal. */
    val PortMapped: ImageVector by lazy {
        DeskIcons.build(
            "PortMapped",
            DeskIcons.stroke("M2.5 9.5h11a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M5 11.5h.01M7.5 11.5h.01"),
            DeskIcons.stroke("M5.8 7a3.2 3.2 0 0 1 4.4 0"),
            DeskIcons.stroke("M4 5.2a5.6 5.6 0 0 1 8 0"),
        )
    }

    /** Eşleme başarısız: yönlendirici ve çarpı. */
    val PortFailed: ImageVector by lazy {
        DeskIcons.build(
            "PortFailed",
            DeskIcons.stroke("M2.5 9.5h11a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-2a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M5 11.5h.01M7.5 11.5h.01"),
            DeskIcons.stroke("M6 3l4 4M10 3L6 7"),
        )
    }

    // ------------------------------------------------------------------ transferler

    val Download: ImageVector by lazy {
        DeskIcons.build("Download", DeskIcons.stroke("M8 2.2v7.3M5.2 6.8L8 9.6l2.8-2.8"), DeskIcons.stroke("M3 12.8h10"))
    }

    val Upload: ImageVector by lazy {
        DeskIcons.build("Upload", DeskIcons.stroke("M8 10V2.7M5.2 5.4L8 2.6l2.8 2.8"), DeskIcons.stroke("M3 13.3h10"))
    }

    val Retry: ImageVector by lazy {
        DeskIcons.build("Retry", DeskIcons.stroke("M13 8a5 5 0 1 1-1.5-3.55"), DeskIcons.stroke("M13 2.8v3h-3"))
    }

    val FolderDownload: ImageVector by lazy {
        DeskIcons.build(
            "FolderDownload",
            DeskIcons.stroke("M2.5 4.5a1 1 0 0 1 1-1h3L8 5h4.5a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z"),
            DeskIcons.stroke("M8 6.8v3.9M6.2 9l1.8 1.8L9.8 9"),
        )
    }

    /** Paylaşılan klasör (yukarı okla). */
    val ShareFolder: ImageVector by lazy {
        DeskIcons.build(
            "ShareFolder",
            DeskIcons.stroke("M2.5 4.5a1 1 0 0 1 1-1h3L8 5h4.5a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1z"),
            DeskIcons.stroke("M8 10.8V7M6.2 8.6L8 6.8l1.8 1.8"),
        )
    }

    // ------------------------------------------------------------------ kullanıcılar

    val User: ImageVector by lazy {
        DeskIcons.build(
            "User",
            DeskIcons.stroke("M8 2.6a2.6 2.6 0 1 1 0 5.2a2.6 2.6 0 1 1 0-5.2z"),
            DeskIcons.stroke("M2.8 13.6a5.2 5.2 0 0 1 10.4 0"),
        )
    }

    /** Arkadaş: kullanıcı ve artı. */
    val Buddy: ImageVector by lazy {
        DeskIcons.build(
            "Buddy",
            DeskIcons.stroke("M6.5 3a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z"),
            DeskIcons.stroke("M1.8 13.5a4.7 4.7 0 0 1 9.4 0"),
            DeskIcons.stroke("M13 4.2v3.2M11.4 5.8h3.2"),
        )
    }

    /** Gruplar: iki kullanıcı. */
    val Group: ImageVector by lazy {
        DeskIcons.build(
            "Group",
            DeskIcons.stroke("M6 3a2.4 2.4 0 1 1 0 4.8a2.4 2.4 0 1 1 0-4.8z"),
            DeskIcons.stroke("M1.5 13.3a4.5 4.5 0 0 1 9 0"),
            DeskIcons.stroke("M10.6 3.4a2.2 2.2 0 0 1 0 4.2M12.2 9.2a4.2 4.2 0 0 1 2.3 4.1"),
        )
    }

    /** Yasaklı: üstü çizili daire. */
    val Ban: ImageVector by lazy {
        DeskIcons.build(
            "Ban",
            DeskIcons.stroke("M2.5 8a5.5 5.5 0 1 1 11 0a5.5 5.5 0 1 1-11 0z"),
            DeskIcons.stroke("M4.1 4.1l7.8 7.8"),
        )
    }

    /** Yoksayılan: üstü çizili göz. */
    val Ignore: ImageVector by lazy {
        DeskIcons.build(
            "Ignore",
            DeskIcons.stroke("M1.8 8s2.2-4 6.2-4 6.2 4 6.2 4-2.2 4-6.2 4-6.2-4-6.2-4z"),
            DeskIcons.stroke("M8 6.6a1.4 1.4 0 1 1 0 2.8a1.4 1.4 0 1 1 0-2.8z"),
            DeskIcons.stroke("M3 13.2L13 2.8"),
        )
    }

    // ------------------------------------------------------------------ genel

    val Star: ImageVector get() = WishlistStar

    val Globe: ImageVector by lazy {
        DeskIcons.build(
            "Globe",
            DeskIcons.stroke("M2.5 8a5.5 5.5 0 1 1 11 0a5.5 5.5 0 1 1-11 0z"),
            DeskIcons.stroke("M2.5 8h11"),
            DeskIcons.stroke("M8 2.5c-2.2 1.9-2.2 9.1 0 11M8 2.5c2.2 1.9 2.2 9.1 0 11"),
        )
    }

    val Filter: ImageVector by lazy {
        DeskIcons.build("Filter", DeskIcons.stroke("M2.5 3h11L9.6 8.2v4.1l-3.2 1.2V8.2z"))
    }

    val Edit: ImageVector by lazy {
        DeskIcons.build("Edit", DeskIcons.stroke("M10.8 2.7l2.5 2.5-7.6 7.6-3.2.7.7-3.2z"), DeskIcons.stroke("M9.4 4.1l2.5 2.5"))
    }

    val Copy: ImageVector by lazy {
        DeskIcons.build(
            "Copy",
            DeskIcons.stroke("M6.5 5.5h6a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M3.5 10.5H3.2a1 1 0 0 1-1-1V3.5a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v.3"),
        )
    }

    val Link: ImageVector by lazy {
        DeskIcons.build(
            "Link",
            DeskIcons.stroke("M6.9 9.1a2.4 2.4 0 0 0 3.4 0l2.1-2.1a2.4 2.4 0 0 0-3.4-3.4l-.7.7"),
            DeskIcons.stroke("M9.1 6.9a2.4 2.4 0 0 0-3.4 0L3.6 9a2.4 2.4 0 0 0 3.4 3.4l.7-.7"),
        )
    }

    val Stop: ImageVector by lazy { DeskIcons.build("Stop", DeskIcons.fill("M4.5 4.5h7v7h-7z")) }

    /** Tarama yarıçapı: büyüteç içinde klasör ağacı (kullanıcı gözatma). */
    val BrowseUser: ImageVector by lazy {
        DeskIcons.build(
            "BrowseUser",
            DeskIcons.stroke("M2.5 3.5h4l1 1.5h6v7.5h-11z"),
            DeskIcons.stroke("M6 8.6a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1-3 0z"),
            DeskIcons.stroke("M5 11.6a3 3 0 0 1 5 0"),
        )
    }

    /** Dosya: köşesi kıvrık sayfa. */
    val File: ImageVector by lazy {
        DeskIcons.build(
            "File",
            DeskIcons.stroke("M4 2h5l3.5 3.5V13a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z"),
            DeskIcons.stroke("M9 2v3.5h3.5"),
        )
    }

    /** Dişli-olmayan "ağ" göstergesi: yukarı ve aşağı ok (hızlar için). */
    val Updown: ImageVector by lazy {
        DeskIcons.build("Updown", DeskIcons.stroke("M5 13V3M2.6 5.4L5 3l2.4 2.4"), DeskIcons.stroke("M11 3v10M8.6 10.6L11 13l2.4-2.4"))
    }
}
