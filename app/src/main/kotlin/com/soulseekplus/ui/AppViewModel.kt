package com.soulseekplus.ui

import android.app.Application
import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.links.SlskLink
import com.soulseekplus.core.links.SlskUri
import com.soulseekplus.core.model.SearchScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Geçici bilgi mesajı; [id] aynı metnin art arda gösterilmesini ayırt eder. */
data class UiMessage(val id: Long, val text: String)

/**
 * Kök durum: gezinme istekleri (slsk://, bildirimler, sohbet komutları), geçici mesajlar ve bağlantı eylemleri.
 * Ekrana özgü durumlar kendi ViewModel'lerindedir.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    val container = SoulseekApp.container(application)

    init {
        // Sohbet komutları (/ban, /add, /alias ...) kalıcı depolara bu köprüyle yazar; kapsayıcı atamadıysa burada atanır.
        if (container.chat.mutator == null) container.chat.mutator = container.userLists.chatMutator(container.settings)
    }

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var messageSeq = 0L
    private var messageJob: Job? = null

    private val _navRequests = MutableSharedFlow<NavRequest>(replay = 0, extraBufferCapacity = 16)
    val navRequests: SharedFlow<NavRequest> = _navRequests.asSharedFlow()

    /** Kompozisyon henüz toplamadan gelen ilk istek (soğuk başlatma) kaybolmasın diye bekletilir. */
    private val pending = ArrayDeque<NavRequest>()
    private var collecting = false

    fun post(@StringRes id: Int, vararg args: Any) = postText(getApplication<Application>().getString(id, *args))

    fun postText(text: String) {
        val m = UiMessage(++messageSeq, text)
        _message.value = m
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MILLIS)
            if (_message.value?.id == m.id) _message.value = null
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** Gezinme isteği gönderir; kök bileşen toplayıcıya bağlanmadıysa bekletilir. */
    fun request(request: NavRequest) {
        if (collecting) _navRequests.tryEmit(request) else synchronized(pending) { pending.addLast(request) }
    }

    /** Kök bileşen toplamaya başlayınca çağrılır: bekleyenleri sırayla yayımlar. */
    fun onCollectorReady() {
        collecting = true
        val drained = synchronized(pending) { pending.toList().also { pending.clear() } }
        drained.forEach { _navRequests.tryEmit(it) }
    }

    fun onCollectorGone() {
        collecting = false
    }

    /** `slsk://` bağlantısı, paylaşılan metin (SEND) ya da bildirim hedefi. */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.toString()?.let(::handleLink)
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    // Paylaşılan metin bir slsk:// bağlantısı olabilir; değilse arama sorgusu olur.
                    val link = SlskUri.findLinks(text).firstOrNull()?.second
                    if (link != null) openLink(link) else request(NavRequest.Search(text.lineSequence().first().take(200), SearchScope.Network))
                }
            }
        }
        when (intent.getStringExtra(LaunchTargets.EXTRA_TARGET)) {
            LaunchTargets.TARGET_PRIVATE_CHAT -> intent.getStringExtra(LaunchTargets.EXTRA_USER)?.let { request(NavRequest.PrivateChat(it)) }
            LaunchTargets.TARGET_ROOM -> intent.getStringExtra(LaunchTargets.EXTRA_ROOM)?.let { request(NavRequest.Room(it)) }
            LaunchTargets.TARGET_WISHLIST -> request(NavRequest.Route(Routes.WISHLIST))
            LaunchTargets.TARGET_TRANSFERS -> request(NavRequest.Route(Routes.TRANSFERS))
        }
        // Aynı intent yeniden işlenmesin (ekran döndürme / onNewIntent sonrası).
        intent.removeExtra(LaunchTargets.EXTRA_TARGET)
        intent.action = Intent.ACTION_MAIN
    }

    private fun handleLink(uri: String) {
        val link = SlskUri.parse(uri)
        if (link != null) openLink(link) else post(com.soulseekplus.R.string.link_invalid)
    }

    fun openLink(link: SlskLink) {
        when (link) {
            is SlskLink.User -> request(NavRequest.UserInfo(link.username))
            is SlskLink.Folder -> request(NavRequest.Browse(link.username, link.path))
            is SlskLink.File -> request(NavRequest.DownloadLink(link.username, link.path))
        }
    }

    // ------------------------------------------------------------------ bağlantı

    fun connect() {
        container.client.connect()
        container.ensureServiceRunning()
    }

    fun disconnect() {
        container.client.disconnect()
    }

    private companion object {
        const val MESSAGE_MILLIS = 3500L
    }
}
