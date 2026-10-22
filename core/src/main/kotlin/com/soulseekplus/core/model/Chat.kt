package com.soulseekplus.core.model

/** Oda ya da özel sohbetteki tek mesaj. */
data class ChatMessage(
    val timestampMillis: Long,
    val username: String,
    val text: String,
    /** Kendi gönderdiğimiz mesaj. */
    val isOwn: Boolean = false,
    /** `/me` eylem mesajı. */
    val isAction: Boolean = false,
    /** Sistem/sunucu bildirimi (katıldı/ayrıldı, yönetici mesajı vb.). */
    val isSystem: Boolean = false,
    /** Mesaj kullanıcı adımızı içeriyor (vurgu). */
    val mentionsMe: Boolean = false,
)

/** Sunucunun oda listesindeki bir oda. */
data class RoomInfo(
    val name: String,
    val userCount: Int,
    val isPrivate: Boolean = false,
    val isOwned: Boolean = false,
    val isOperated: Boolean = false,
)

/** Odadaki bir üye. */
data class RoomUser(
    val username: String,
    val status: UserStatus,
    val stats: UserStats,
    val slotsFull: Boolean = false,
    val countryCode: String? = null,
)

/** Katılınmış bir odanın kullanıcı arayüzü durumu. */
data class RoomState(
    val name: String,
    val users: List<RoomUser> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    /** Oda duvar yazıları (ticker): kullanıcı → yazı. */
    val tickers: Map<String, String> = emptyMap(),
    val isPrivate: Boolean = false,
    val unread: Int = 0,
)

/** Bir kullanıcıyla özel sohbet. */
data class PrivateConversation(
    val username: String,
    val messages: List<ChatMessage> = emptyList(),
    val unread: Int = 0,
)
