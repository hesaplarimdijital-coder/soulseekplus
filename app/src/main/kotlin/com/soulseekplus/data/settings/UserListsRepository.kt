package com.soulseekplus.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.soulseekplus.core.chat.ChatMutator
import com.soulseekplus.core.chat.UserListAction
import com.soulseekplus.core.model.UserGroup
import com.soulseekplus.core.model.UserLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Arkadaş, yasaklı, yoksayılan listeleri ve kullanıcı gruplarının kalıcı deposu (`SharedPreferences("user_lists")`).
 * Her değişiklik anında yayımlanır; motor ve paylaşım dizini [lists] akışını izler.
 */
class UserListsRepository(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _lists = MutableStateFlow(load())

    val lists: StateFlow<UserLists> = _lists.asStateFlow()

    // ------------------------------------------------------------------ arkadaşlar / yasaklı / yoksayılan

    fun addBuddy(user: String) = edit(user) { l, u -> l.copy(buddies = l.buddies + u) }

    fun removeBuddy(user: String) = edit(user) { l, u ->
        l.copy(
            buddies = l.buddies - u,
            buddyNotes = l.buddyNotes - u,
        )
    }

    fun ban(user: String) = edit(user) { l, u -> l.copy(banned = l.banned + u) }

    fun unban(user: String) = edit(user) { l, u -> l.copy(banned = l.banned - u) }

    fun ignore(user: String) = edit(user) { l, u -> l.copy(ignored = l.ignored + u) }

    fun unignore(user: String) = edit(user) { l, u -> l.copy(ignored = l.ignored - u) }

    fun setBuddyNote(user: String, note: String) = edit(user) { l, u ->
        val trimmed = note.trim()
        l.copy(buddyNotes = if (trimmed.isEmpty()) l.buddyNotes - u else l.buddyNotes + (u to trimmed))
    }

    // ------------------------------------------------------------------ gruplar

    /** Yeni grup oluşturur. Ad boşsa ya da zaten varsa false. */
    fun createGroup(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val current = _lists.value
            if (current.groups.any { it.name == clean }) return false
            commit(current.copy(groups = current.groups + UserGroup(clean, emptySet())))
        }
        return true
    }

    fun deleteGroup(name: String) = update { l -> l.copy(groups = l.groups.filter { it.name != name }) }

    fun renameGroup(old: String, new: String): Boolean {
        val clean = new.trim()
        if (clean.isEmpty()) return false
        synchronized(lock) {
            val current = _lists.value
            if (current.groups.none { it.name == old }) return false
            if (clean != old && current.groups.any { it.name == clean }) return false
            commit(current.copy(groups = current.groups.map { if (it.name == old) it.copy(name = clean) else it }))
        }
        return true
    }

    fun addToGroup(group: String, user: String) {
        val clean = user.trim()
        if (clean.isEmpty()) return
        update { l -> l.copy(groups = l.groups.map { if (it.name == group) it.copy(members = it.members + clean) else it }) }
    }

    fun removeFromGroup(group: String, user: String) =
        update { l -> l.copy(groups = l.groups.map { if (it.name == group) it.copy(members = it.members - user) else it }) }

    /** Sohbet komutlarının ([ChatMutator]) bu depoyu değiştirmesi için bağdaştırıcı. */
    fun chatMutator(settings: SettingsRepository): ChatMutator = object : ChatMutator {
        override fun apply(action: UserListAction) {
            when (action) {
                is UserListAction.AddBuddy -> addBuddy(action.username)
                is UserListAction.RemoveBuddy -> removeBuddy(action.username)
                is UserListAction.Ban -> ban(action.username)
                is UserListAction.Unban -> unban(action.username)
                is UserListAction.Ignore -> ignore(action.username)
                is UserListAction.Unignore -> unignore(action.username)
            }
        }

        override fun setAlias(name: String, expansion: String?) = settings.setAlias(name, expansion)
    }

    // ------------------------------------------------------------------ iç

    private fun edit(user: String, transform: (UserLists, String) -> UserLists) {
        val clean = user.trim()
        if (clean.isEmpty()) return
        update { transform(it, clean) }
    }

    private fun update(transform: (UserLists) -> UserLists) {
        synchronized(lock) {
            val current = _lists.value
            val next = transform(current)
            if (next != current) commit(next)
        }
    }

    private fun commit(next: UserLists) {
        prefs.edit()
            .putString(KEY_BUDDIES, JSONArray(next.buddies.sorted()).toString())
            .putString(KEY_BANNED, JSONArray(next.banned.sorted()).toString())
            .putString(KEY_IGNORED, JSONArray(next.ignored.sorted()).toString())
            .putString(KEY_NOTES, JSONObject(next.buddyNotes as Map<*, *>).toString())
            .putString(
                KEY_GROUPS,
                JSONArray().also { array ->
                    for (g in next.groups) {
                        array.put(JSONObject().put("name", g.name).put("members", JSONArray(g.members.sorted())))
                    }
                }.toString(),
            )
            .apply()
        _lists.value = next
    }

    private fun load(): UserLists = try {
        UserLists(
            buddies = readSet(KEY_BUDDIES),
            banned = readSet(KEY_BANNED),
            ignored = readSet(KEY_IGNORED),
            groups = readGroups(),
            buddyNotes = readNotes(),
        )
    } catch (e: JSONException) {
        UserLists.EMPTY
    }

    private fun readSet(key: String): Set<String> {
        val json = prefs.getString(key, null) ?: return emptySet()
        val array = JSONArray(json)
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    private fun readGroups(): List<UserGroup> {
        val json = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        val array = JSONArray(json)
        val out = ArrayList<UserGroup>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isEmpty()) continue
            val members = obj.optJSONArray("members")
            val set = LinkedHashSet<String>()
            if (members != null) for (j in 0 until members.length()) members.optString(j).takeIf { it.isNotEmpty() }?.let(set::add)
            out += UserGroup(name, set)
        }
        return out
    }

    private fun readNotes(): Map<String, String> {
        val json = prefs.getString(KEY_NOTES, null) ?: return emptyMap()
        val obj = JSONObject(json)
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k)
        }
        return out
    }

    companion object {
        private const val FILE = "user_lists"
        private const val KEY_BUDDIES = "buddies"
        private const val KEY_BANNED = "banned"
        private const val KEY_IGNORED = "ignored"
        private const val KEY_GROUPS = "groups"
        private const val KEY_NOTES = "notes"
    }
}
