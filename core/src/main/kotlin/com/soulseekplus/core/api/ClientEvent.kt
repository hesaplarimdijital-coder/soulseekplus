package com.soulseekplus.core.api

import com.soulseekplus.core.model.RoomInfo
import com.soulseekplus.core.model.RoomUser
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.UserStats
import com.soulseekplus.core.model.UserStatus

/**
 * Motorun yayımladığı olaylar ([SoulseekClient.events]). Sohbet, arama, bildirim depoları bunlardan durum kurar.
 * Olaylar sıcak akıştır (replay yok); abone olmayan kaçırır. Kalıcı durum gereken yerde depo kendi biriktirir.
 */
sealed interface ClientEvent {

    // ---------------------------------------------------------------- oturum

    /** Giriş başarılı oldu (yeniden bağlanmalar dahil her girişte). */
    data class LoggedIn(val username: String) : ClientEvent

    /** Aynı hesapla başka yerden giriş yapıldı; sunucu bizi düşürdü. Otomatik yeniden bağlanma yapılmaz. */
    data object Relogged : ClientEvent

    /** Sunucu yönetici mesajı (AdminMessage). */
    data class AdminMessage(val text: String) : ClientEvent

    /** Kullanıcıya gösterilecek genel hata/uyarı (bağlantı, port, depolama...). */
    data class Notice(val text: String, val isError: Boolean = false) : ClientEvent

    // ---------------------------------------------------------------- kullanıcılar

    data class UserStatusChanged(val username: String, val status: UserStatus, val privileged: Boolean) : ClientEvent

    data class UserStatsChanged(val username: String, val stats: UserStats) : ClientEvent

    // ---------------------------------------------------------------- sohbet odaları

    /** Sunucu oda listesi (RoomList). */
    data class RoomList(val rooms: List<RoomInfo>) : ClientEvent

    /** Odaya katıldık; mevcut üyeler. */
    data class RoomJoined(
        val room: String,
        val users: List<RoomUser>,
        val isPrivate: Boolean,
        val owner: String? = null,
        val operators: List<String> = emptyList(),
    ) : ClientEvent

    data class RoomLeft(val room: String) : ClientEvent

    data class UserJoinedRoom(val room: String, val user: RoomUser) : ClientEvent

    data class UserLeftRoom(val room: String, val username: String) : ClientEvent

    data class RoomMessage(val room: String, val username: String, val text: String, val timestampMillis: Long) : ClientEvent

    /** Odaya katılırken gelen tüm duvar yazıları (RoomTickerState). */
    data class RoomTickers(val room: String, val tickers: Map<String, String>) : ClientEvent

    data class RoomTickerAdded(val room: String, val username: String, val text: String) : ClientEvent

    data class RoomTickerRemoved(val room: String, val username: String) : ClientEvent

    /** Oda oluşturulamadı / katılınamadı (CantCreateRoom). */
    data class RoomJoinFailed(val room: String, val reason: String) : ClientEvent

    // ---------------------------------------------------------------- özel mesaj

    /**
     * Gelen özel mesaj (MessageUser). Motor sunucuya MessageAcked'i kendisi gönderir.
     * [isAdmin] sunucunun "new message" bayrağı değil, yönetici mesajı işaretidir. Yoksayılan kullanıcıların mesajları yayımlanmaz.
     */
    data class PrivateMessage(
        val id: Int,
        val username: String,
        val text: String,
        val timestampMillis: Long,
        val isAdmin: Boolean,
        /** Çevrimdışıyken gönderilip girişte teslim edilen eski mesaj. */
        val isOfflineMessage: Boolean,
    ) : ClientEvent

    /** Kendi gönderdiğimiz özel mesaj (gönderildi). Sohbet deposu kaydeder. */
    data class PrivateMessageSent(val username: String, val text: String, val timestampMillis: Long) : ClientEvent

    // ---------------------------------------------------------------- arama / istek listesi

    /** Sunucunun bildirdiği istek listesi arama aralığı değişti (saniye). */
    data class WishlistInterval(val seconds: Int) : ClientEvent

    // ---------------------------------------------------------------- transferler

    /** Transfer bitti (bildirim için). Durum akışı ayrıca [SoulseekClient.transfers]'ta. */
    data class TransferFinished(val transfer: Transfer) : ClientEvent

    /** Transfer kalıcı olarak başarısız oldu ya da reddedildi. */
    data class TransferFailed(val transfer: Transfer) : ClientEvent

    /** Biri bizim paylaşımlarımızı taradı (bilgi amaçlı). */
    data class SharesBrowsedBy(val username: String) : ClientEvent
}
