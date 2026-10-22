package com.soulseekplus.core.model

/** Sunucu bağlantısının durumu. */
sealed interface ConnectionState {
    /** Bağlı değil. [reason] kullanıcıya gösterilecek son sebep (ör. "Yanlış şifre"), yoksa null. */
    data class Disconnected(val reason: String? = null) : ConnectionState

    data object Connecting : ConnectionState

    /** Giriş başarılı. */
    data class LoggedIn(
        val username: String,
        /** Sunucunun gördüğü dış IP adresimiz. */
        val publicIp: String?,
        val privileged: Boolean,
        val greeting: String,
        val away: Boolean = false,
    ) : ConnectionState

    /** Bağlantı koptu; üstel geri çekilme ile yeniden denenecek. */
    data class Reconnecting(val attempt: Int, val retryAtMillis: Long, val reason: String?) : ConnectionState
}

/** Port yönlendirme (UPnP IGD / NAT-PMP) durumu. */
sealed interface PortMappingState {
    data object Disabled : PortMappingState
    data object Discovering : PortMappingState

    /** Port açıldı. [method] "UPnP" ya da "NAT-PMP". */
    data class Mapped(
        val method: String,
        val externalIp: String?,
        val externalPort: Int,
        val internalPort: Int,
        val leaseExpiresAtMillis: Long?,
    ) : PortMappingState

    data class Failed(val reason: String) : PortMappingState
}

/** Dağıtık arama ağındaki konumumuz. */
data class DistributedState(
    val parentUsername: String? = null,
    val branchLevel: Int = 0,
    val branchRoot: String? = null,
    val childCount: Int = 0,
    val acceptingChildren: Boolean = false,
)

/** Sunucuya gerçek bir dinleme portuyla erişilebilirlik (sunucunun eşlerin bize doğrudan bağlanabildiğini düşündüğü). */
data class NetworkStatus(
    val listening: Boolean = false,
    val listenPort: Int = 0,
    val onMetered: Boolean = false,
    val connectedPeers: Int = 0,
)
