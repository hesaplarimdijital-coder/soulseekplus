package com.soulseekplus.core.chat

import com.soulseekplus.core.model.ChatMessage
import com.soulseekplus.core.model.SearchScope

/** Bir sohbet penceresi: oda ya da özel sohbet. */
sealed interface ChatTarget {
    data class Room(val name: String) : ChatTarget
    data class Private(val username: String) : ChatTarget
}

/** Kullanıcı arayüzünün gerçekleştirmesi gereken gezinme/başlatma istekleri ([ChatRepository.uiRequests]). */
sealed interface UiRequest {
    /** Kullanıcının paylaşımlarına göz at; [path] verilmişse o klasöre git. */
    data class OpenBrowse(val username: String, val path: String? = null) : UiRequest

    data class OpenUserInfo(val username: String) : UiRequest

    /** Var olan arama sekmesini göster. */
    data class OpenSearch(val tabId: Int) : UiRequest

    /** Yeni arama başlat (sohbet komutlarından: /search /rsearch /bsearch /usearch). Arayüz `SearchRepository.newSearch` çağırır. */
    data class NewSearch(val query: String, val scope: SearchScope) : UiRequest

    data class OpenPrivateChat(val username: String) : UiRequest

    data class OpenRoom(val room: String) : UiRequest
}

/** Komutların kullanıcı listelerinde yaptığı değişiklik. */
sealed interface UserListAction {
    val username: String

    data class AddBuddy(override val username: String) : UserListAction
    data class RemoveBuddy(override val username: String) : UserListAction
    data class Ban(override val username: String) : UserListAction
    data class Unban(override val username: String) : UserListAction
    data class Ignore(override val username: String) : UserListAction
    data class Unignore(override val username: String) : UserListAction
}

/**
 * Sohbet komutlarının çekirdek dışı durumu değiştirmesi için küçük geri çağrı arayüzü: kullanıcı listeleri ve
 * komut takma adları uygulama katmanında saklanır (`UserListsRepository`, `SettingsRepository`).
 * Uygulama başlarken `ChatRepository.mutator` atanır; atanmamışsa komut sonucu "uygulanmadı" olarak döner ve çağıran uygular.
 */
interface ChatMutator {
    fun apply(action: UserListAction)

    /** [expansion] null ise takma adı siler. */
    fun setAlias(name: String, expansion: String?)
}

/** Sohbet depolarının yayımladığı olaylar (bildirimler ve arayüz uyarıları için). */
sealed interface ChatEvent {
    /** Yeni gelen özel mesaj (yoksayılanlar hariç). [conversationOpen] mesaj geldiğinde o sohbet ekranda açıktı. */
    data class PrivateMessageReceived(
        val username: String,
        val message: ChatMessage,
        val isOfflineMessage: Boolean,
        val conversationOpen: Boolean,
    ) : ChatEvent

    /** Odada kullanıcı adımız geçti. */
    data class RoomMention(val room: String, val message: ChatMessage, val roomOpen: Boolean) : ChatEvent

    /** Odaya katılınamadı. [reason] sunucunun İngilizce açıklamasıdır. */
    data class RoomJoinFailed(val room: String, val reason: String) : ChatEvent
}

enum class CommandGroup { CHAT, ROOMS, PRIVATE, USERS, FILTERS, SEARCH, ALIASES }

/** Yardım listesi girdisi. Açıklama metni arayüzde `command_<name>_desc` kaynağından gelir. */
data class CommandInfo(
    val name: String,
    val aliases: List<String>,
    /** Örn. "<room>", "[user]". */
    val parameters: String,
    val group: CommandGroup,
)

/** [ChatRepository.submit] sonucu; arayüz bunu yerelleştirip geçici geri bildirim olarak gösterir. */
sealed interface CommandResult {
    data object Empty : CommandResult

    /** Mesaj ya da eylem gönderildi. */
    data object Sent : CommandResult

    /** Komut çalıştırıldı; gösterilecek özel bir şey yok. [command] kanonik komut adı, [argument] varsa ilgili kullanıcı/oda. */
    data class Executed(val command: String, val argument: String? = null) : CommandResult

    /** Kullanıcı listesi değişikliği. [applied] false ise [ChatRepository.mutator] atanmamıştı; çağıran uygulamalı. */
    data class UserListChanged(val action: UserListAction, val applied: Boolean) : CommandResult

    data class AliasChanged(val name: String, val expansion: String?, val applied: Boolean) : CommandResult

    data class AliasList(val aliases: Map<String, String>) : CommandResult

    data class Help(val query: String, val commands: List<CommandInfo>) : CommandResult

    data class InvalidUsage(val command: String, val usage: String) : CommandResult

    data class UnknownCommand(val name: String) : CommandResult

    /** Komut/mesaj bir sohbet penceresi gerektiriyor ama yok. */
    data class NoTarget(val command: String) : CommandResult

    /** Takma ad adı yerleşik bir komutla çakışıyor ya da geçersiz. */
    data class InvalidAlias(val name: String) : CommandResult
}
