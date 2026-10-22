package com.soulseekplus.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.soulseekplus.core.model.WishlistItem
import com.soulseekplus.core.search.WishlistStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * İstek listesi deposu: öğeler `SharedPreferences("wishlist")` içinde JSON; görülen sonuç anahtarları (binlerce satır
 * olabilir) tercih dosyasını şişirmesin diye sorgu başına küçük bir metin dosyasında (`files/wishlist/<özet>.txt`).
 */
class PrefsWishlistStore(context: Context) : WishlistStore {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("wishlist", Context.MODE_PRIVATE)
    private val dir: File = File(context.applicationContext.filesDir, "wishlist")

    override fun loadItems(): List<WishlistItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<WishlistItem>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val query = o.optString("q").trim()
                if (query.isEmpty()) continue
                out += WishlistItem(
                    query = query,
                    addedAtMillis = o.optLong("added", 0L),
                    lastSearchedAtMillis = if (o.has("last")) o.optLong("last") else null,
                    newHitCount = o.optInt("new", 0),
                    enabled = o.optBoolean("on", true),
                )
            }
            out
        } catch (e: JSONException) {
            emptyList()
        }
    }

    override fun saveItems(items: List<WishlistItem>) {
        val array = JSONArray()
        for (item in items) {
            val o = JSONObject()
                .put("q", item.query)
                .put("added", item.addedAtMillis)
                .put("new", item.newHitCount)
                .put("on", item.enabled)
            item.lastSearchedAtMillis?.let { o.put("last", it) }
            array.put(o)
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    /**
     * Bekleyen yazmalar: depo, istek listesi kilidi tutulurken çağrılır; disk yazması burada yapılmaz, sorgu başına en
     * son liste tek iş parçacıklı yürütücüde birleştirilerek yazılır (art arda gelen yanıtlar tek yazmaya iner).
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wishlist-store").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    override fun loadSeenKeys(query: String): List<String> {
        pending[query]?.let { return it }
        val file = fileFor(query)
        if (!file.isFile) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        } catch (e: IOException) {
            emptyList()
        }
    }

    override fun saveSeenKeys(query: String, keys: List<String>) {
        val first = pending.put(query, keys.toList()) == null
        if (first) {
            writer.execute {
                val latest = pending.remove(query) ?: return@execute
                writeSeenKeys(query, latest)
            }
        }
    }

    private fun writeSeenKeys(query: String, keys: List<String>) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val target = fileFor(query)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(keys.joinToString("\n"), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        } catch (e: IOException) {
            // Görülen anahtarlar kaybolursa bir sonraki taramada eski sonuçlar yeniden "yeni" görünür; kritik değil.
        }
    }

    override fun deleteSeenKeys(query: String) {
        pending.remove(query)
        // Sıra korunur: daha önce kuyruğa giren yazma bu silmeden sonra dosyayı geri yaratmaz.
        writer.execute { fileFor(query).delete() }
    }

    private fun fileFor(query: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(query.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.txt")
    }

    private companion object {
        const val KEY_ITEMS = "items"
    }
}
