package com.soulseekplus.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.soulseekplus.R
import com.soulseekplus.SoulseekApp
import com.soulseekplus.core.chat.ChatRepository
import com.soulseekplus.core.chat.ChatTarget
import com.soulseekplus.core.chat.CommandResult
import com.soulseekplus.core.chat.UserListAction
import com.soulseekplus.ui.AppActions

/** Sohbet ekranının görünüm durumu: açık sohbet, taslaklar, komut geri bildirimi, oda listesi. */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val container = SoulseekApp.container(application)
    val repo: ChatRepository get() = container.chat

    var selected by mutableStateOf<ChatTarget?>(null)
        private set

    /** Sunucu oda listesi gösteriliyor mu. */
    var showRoomList by mutableStateOf(false)
        private set

    /** Odada üye listesi paneli açık mı. */
    var showUsers by mutableStateOf(false)

    /** Son komutun yerelleştirilmiş geri bildirimi (sohbet kutusunun üstünde gösterilir). */
    var feedback by mutableStateOf<String?>(null)

    /** `/help` sonucu; iletişim kutusunda gösterilir. */
    var help by mutableStateOf<CommandResult.Help?>(null)

    var aliasList by mutableStateOf<Map<String, String>?>(null)

    private val drafts = mutableStateMapOf<String, String>()

    fun draft(target: ChatTarget?): String = target?.let { drafts[keyOf(it)] }.orEmpty()

    fun setDraft(target: ChatTarget?, text: String) {
        if (target != null) drafts[keyOf(target)] = text
    }

    fun select(target: ChatTarget?) {
        selected = target
        showRoomList = false
        feedback = null
        if (target is ChatTarget.Private) {
            repo.openPrivate(target.username)
            container.client.watchUser(target.username)
        }
        // Etkin hedef ekran görünürken ChatScreen tarafından bildirilir; burada yalnızca okundu sayılır.
        if (target != null) repo.markRead(target)
    }

    fun openPrivate(username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        select(ChatTarget.Private(name))
    }

    /** Odaya katılır (değilse) ve açar. */
    fun openRoom(room: String, private: Boolean = false) {
        val name = room.trim()
        if (name.isEmpty()) return
        if (name !in repo.rooms.value) repo.joinRoom(name, private)
        select(ChatTarget.Room(name))
    }

    fun openRoomList() {
        showRoomList = true
        selected = null
        repo.refreshRoomList()
    }

    fun closeRoomList() {
        showRoomList = false
    }

    fun leaveRoom(room: String) {
        repo.leaveRoom(room)
        if ((selected as? ChatTarget.Room)?.name == room) selected = null
    }

    fun closePrivate(username: String) {
        repo.closePrivate(username)
        if ((selected as? ChatTarget.Private)?.username == username) selected = null
    }

    /** Sohbet kutusuna yazılanı gönderir (komutları da çalıştırır). */
    fun submit(text: String, actions: AppActions) {
        val target = selected
        val res = getApplication<Application>().resources
        val result = repo.submit(text, target)
        feedback = null
        when (result) {
            CommandResult.Empty -> Unit
            CommandResult.Sent -> setDraft(target, "")
            is CommandResult.Executed -> {
                setDraft(target, "")
                feedback = when (result.command) {
                    "away" -> res.getString(R.string.chat_fb_away)
                    "back" -> res.getString(R.string.chat_fb_back)
                    else -> null
                }
            }
            is CommandResult.UserListChanged -> {
                if (!result.applied) apply(result.action)
                setDraft(target, "")
                feedback = userListFeedback(result.action)
            }
            is CommandResult.AliasChanged -> {
                if (!result.applied) container.settings.setAlias(result.name, result.expansion)
                setDraft(target, "")
                feedback = if (result.expansion == null) res.getString(R.string.chat_fb_alias_removed, result.name)
                else res.getString(R.string.chat_fb_alias_set, result.name, result.expansion)
            }
            is CommandResult.AliasList -> {
                setDraft(target, "")
                if (result.aliases.isEmpty()) feedback = res.getString(R.string.chat_fb_no_aliases) else aliasList = result.aliases
            }
            is CommandResult.Help -> {
                setDraft(target, "")
                help = result
            }
            is CommandResult.InvalidUsage -> feedback = res.getString(R.string.chat_fb_usage, result.command, result.usage)
            is CommandResult.UnknownCommand -> feedback = res.getString(R.string.chat_fb_unknown, result.name)
            is CommandResult.NoTarget -> feedback = res.getString(R.string.chat_fb_no_target)
            is CommandResult.InvalidAlias -> feedback = res.getString(R.string.chat_fb_bad_alias, result.name)
        }
    }

    private fun apply(action: UserListAction) {
        val lists = container.userLists
        when (action) {
            is UserListAction.AddBuddy -> lists.addBuddy(action.username)
            is UserListAction.RemoveBuddy -> lists.removeBuddy(action.username)
            is UserListAction.Ban -> lists.ban(action.username)
            is UserListAction.Unban -> lists.unban(action.username)
            is UserListAction.Ignore -> lists.ignore(action.username)
            is UserListAction.Unignore -> lists.unignore(action.username)
        }
    }

    private fun userListFeedback(action: UserListAction): String {
        val res = getApplication<Application>().resources
        val id = when (action) {
            is UserListAction.AddBuddy -> R.string.chat_fb_buddy_added
            is UserListAction.RemoveBuddy -> R.string.chat_fb_buddy_removed
            is UserListAction.Ban -> R.string.chat_fb_banned
            is UserListAction.Unban -> R.string.chat_fb_unbanned
            is UserListAction.Ignore -> R.string.chat_fb_ignored
            is UserListAction.Unignore -> R.string.chat_fb_unignored
        }
        return res.getString(id, action.username)
    }

    private fun keyOf(target: ChatTarget): String = when (target) {
        is ChatTarget.Room -> "r:" + target.name
        is ChatTarget.Private -> "p:" + target.username
    }
}
