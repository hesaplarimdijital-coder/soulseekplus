package com.soulseekplus.core.model

/** Sunucunun bildirdiği kullanıcı durumu (protokol kodları 0/1/2). */
enum class UserStatus(val code: Int) {
    OFFLINE(0),
    AWAY(1),
    ONLINE(2);

    companion object {
        fun fromCode(code: Int): UserStatus = entries.firstOrNull { it.code == code } ?: OFFLINE
    }
}

/** GetUserStats / JoinRoom ile gelen kullanıcı istatistikleri. [averageSpeed] bayt/saniye. */
data class UserStats(
    val averageSpeed: Int = 0,
    val uploadCount: Long = 0,
    val files: Int = 0,
    val folders: Int = 0,
)

/** İzlenen bir kullanıcının bilinen anlık hâli. */
data class UserPresence(
    val username: String,
    val status: UserStatus = UserStatus.OFFLINE,
    val privileged: Boolean = false,
    val stats: UserStats? = null,
    /** ISO 3166-1 alfa-2 ülke kodu (bilinmiyorsa null). Oda listesinden ya da IP'den çözülür. */
    val countryCode: String? = null,
)

/** UserInfoResponse (eşten gelen kullanıcı profili). */
data class UserInfo(
    val username: String,
    val description: String,
    val picture: ByteArray?,
    val uploadSlots: Int,
    val queueSize: Int,
    val slotsAvailable: Boolean,
    /** Yükleme izni: 0 hiç kimse, 1 herkes, 2 arkadaşlar, 3 tanınan kullanıcılar (null: bildirilmedi). */
    val uploadPermission: Int?,
) {
    override fun equals(other: Any?): Boolean =
        other is UserInfo && other.username == username && other.description == description &&
            other.uploadSlots == uploadSlots && other.queueSize == queueSize &&
            other.slotsAvailable == slotsAvailable && other.uploadPermission == uploadPermission &&
            picture.contentEquals(other.picture)

    override fun hashCode(): Int =
        listOf(username, description, uploadSlots, queueSize, slotsAvailable, uploadPermission).hashCode() * 31 +
            (picture?.contentHashCode() ?: 0)
}

/** Özel kullanıcı grubu (3. kademe paylaşım için). */
data class UserGroup(val name: String, val members: Set<String>)

/**
 * Kullanıcı listeleri: arkadaşlar, yasaklılar, yoksayılanlar ve gruplar.
 * Kullanıcı adları büyük/küçük harf duyarlıdır (Soulseek'te öyledir).
 */
data class UserLists(
    val buddies: Set<String> = emptySet(),
    val banned: Set<String> = emptySet(),
    val ignored: Set<String> = emptySet(),
    val groups: List<UserGroup> = emptyList(),
    /** Arkadaşlara verilen ek notlar (UI için). */
    val buddyNotes: Map<String, String> = emptyMap(),
) {
    fun isBuddy(user: String) = user in buddies
    fun isBanned(user: String) = user in banned
    fun isIgnored(user: String) = user in ignored
    fun groupsOf(user: String): List<String> = groups.filter { user in it.members }.map { it.name }

    companion object {
        val EMPTY = UserLists()
    }
}
