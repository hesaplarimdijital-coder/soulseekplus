package com.soulseekplus.core.shares

import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.ShareProvider
import com.soulseekplus.core.api.ShareSearchResult
import com.soulseekplus.core.api.UploadResolution
import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.ShareLevel
import com.soulseekplus.core.model.ShareStats
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder
import com.soulseekplus.core.model.UserLists
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.BitSet

/**
 * Paylaşımlarımızı motora sunar ([ShareProvider]): kelime dizini ile gelen aramaları hızlı eşleştirir
 * (docs/slsk-protocol.md §9), 3 kademeli izni (herkes / arkadaşlar / grup) uygular, göz atma ağacını ve klasör
 * içeriğini isteyene göre kurar, yükleme isteklerini çözer.
 *
 * Değişmez anlık görüntü atomik olarak değiştirilir; tüm okuma metotları kilitsizdir ve her iş parçacığından çağrılabilir.
 * [update] ağır kısmı (dizin kurma) çağıran iş parçacığında yapar; aynı [ShareSnapshot] örneğiyle çağrılırsa dizin
 * yeniden kurulmaz, yalnızca kullanıcı listeleri değişir.
 */
class ShareIndex(private val logger: Logger) : ShareProvider {

    /**
     * İsteyenin erişemediği (arkadaşa/gruba özel) dosyaları kilitli olarak göster. Nicotine+ bunu "reveal buddy shares"
     * ayarına bağlar; Seeker gibi varsayılan olarak açıktır. Kapalıyken kilitli listeler her zaman boştur.
     */
    @Volatile
    var revealRestricted: Boolean = true

    /** Sunucunun yasakladığı arama ifadeleri (küçük harf; ExcludedSearchPhrases). Yolunda geçen dosyalar sonuçtan çıkarılır. */
    @Volatile
    var excludedPhrases: List<String> = emptyList()

    /** Göz atma yanıtındaki [BrowseResult.username] (kendi kullanıcı adımız; bilinmiyorsa boş). */
    @Volatile
    var ownUsername: String = ""

    private val _stats = MutableStateFlow(ShareStats.EMPTY)
    override val stats: StateFlow<ShareStats> = _stats.asStateFlow()

    @Volatile
    private var data: IndexData = IndexData.EMPTY

    @Volatile
    private var lists: ListsView = ListsView.of(UserLists.EMPTY)

    /** Paylaşım ya da kullanıcı listeleri değişince çağrılır. */
    @Synchronized
    fun update(snapshot: ShareSnapshot, userLists: UserLists) {
        lists = ListsView.of(userLists)
        if (data.source !== snapshot) {
            val started = System.nanoTime()
            val built = IndexData.build(snapshot)
            data = built
            _stats.value = ShareStats(built.folderCount, built.files.size)
            logger.info(TAG, "Paylaşım dizini kuruldu: ${built.folderCount} klasör, ${built.files.size} dosya, " +
                "${built.words.size} kelime (${(System.nanoTime() - started) / 1_000_000} ms)")
        }
    }

    // ------------------------------------------------------------------ izin

    private class ListsView(val buddies: Set<String>, val banned: Set<String>, val groups: Map<String, Set<String>>) {
        companion object {
            fun of(lists: UserLists) = ListsView(lists.buddies, lists.banned, lists.groups.associate { it.name to it.members })
        }
    }

    private fun allowed(view: ListsView, requester: String, level: ShareLevel, group: String?): Boolean = when (level) {
        ShareLevel.PUBLIC -> true
        ShareLevel.BUDDIES -> requester in view.buddies
        ShareLevel.GROUP -> group != null && requester in (view.groups[group] ?: emptySet())
    }

    // ------------------------------------------------------------------ arama

    override fun search(query: String, requester: String, maxResults: Int): ShareSearchResult {
        if (maxResults <= 0 || query.isBlank()) return ShareSearchResult.EMPTY
        val view = lists
        if (requester in view.banned) return ShareSearchResult.EMPTY
        val snapshot = data
        if (snapshot.files.isEmpty()) return ShareSearchResult.EMPTY

        val lower = query.lowercase()
        val excluded = LinkedHashSet<String>()
        val partial = LinkedHashSet<String>()
        if ('-' in lower || '*' in lower) {
            for (word in lower.split(WHITESPACE)) {
                if (word.isEmpty()) continue
                when (word[0]) {
                    '-' -> excluded.addAll(SearchWords.split(word))
                    '*' -> partial.addAll(SearchWords.split(word))
                }
            }
        }
        val included = LinkedHashSet(SearchWords.split(lower))
        included.removeAll(excluded)
        included.removeAll(partial)
        if (included.isEmpty()) return ShareSearchResult.EMPTY

        val matches = snapshot.match(included, partial, excluded) ?: return ShareSearchResult.EMPTY
        val phrases = excludedPhrases

        val files = ArrayList<SharedFile>()
        val locked = ArrayList<SharedFile>()
        val reveal = revealRestricted
        for (index in matches) {
            if (files.size + locked.size >= maxResults) break
            val entry = snapshot.files[index]
            if (phrases.isNotEmpty() && phrases.any { it.isNotEmpty() && entry.lowerPath.contains(it) }) continue
            if (allowed(view, requester, entry.level, entry.group)) files.add(entry.shared)
            else if (reveal) locked.add(entry.shared)
        }
        return if (files.isEmpty() && locked.isEmpty()) ShareSearchResult.EMPTY else ShareSearchResult(files, locked)
    }

    // ------------------------------------------------------------------ göz atma

    override fun browse(requester: String): BrowseResult {
        val view = lists
        if (requester in view.banned) return BrowseResult(ownUsername, emptyList())
        val snapshot = data
        val open = ArrayList<SharedFolder>()
        val closed = ArrayList<SharedFolder>()
        val reveal = revealRestricted
        for (folder in snapshot.folders) {
            if (allowed(view, requester, folder.level, folder.group)) open.add(folder.toShared(snapshot))
            else if (reveal) closed.add(folder.toShared(snapshot))
        }
        return BrowseResult(ownUsername, open, closed)
    }

    override fun folderContents(requester: String, folderPath: String): List<SharedFolder> {
        val view = lists
        if (requester in view.banned) return emptyList()
        val snapshot = data
        val normalized = normalizePath(folderPath) ?: return emptyList()
        if (normalized.isEmpty()) return emptyList()
        val exact = snapshot.folderByPath[normalized] ?: snapshot.folderByLower[normalized.lowercase()]
        val base = exact?.path ?: normalized
        val baseLower = base.lowercase()
        val prefix = baseLower + "\\"
        val out = ArrayList<SharedFolder>()
        for (folder in snapshot.folders) {
            if (folder.lowerPath != baseLower && !folder.lowerPath.startsWith(prefix)) continue
            if (allowed(view, requester, folder.level, folder.group)) out.add(folder.toShared(snapshot))
        }
        return out
    }

    // ------------------------------------------------------------------ yükleme

    override fun resolveUpload(requester: String, virtualPath: String): UploadResolution {
        val view = lists
        if (requester in view.banned) return UploadResolution.Denied(REASON_BANNED)
        val snapshot = data
        val normalized = normalizePath(virtualPath) ?: return UploadResolution.NotShared
        val index = snapshot.fileByPath[normalized] ?: snapshot.fileByLower[normalized.lowercase()] ?: return UploadResolution.NotShared
        val entry = snapshot.files[index]
        if (!allowed(view, requester, entry.level, entry.group)) return UploadResolution.Denied(REASON_NOT_SHARED)
        val source = snapshot.source.opener.open(entry.shared.path, entry.ref) ?: return UploadResolution.NotShared
        return UploadResolution.Allowed(entry.shared, source)
    }

    /** `/` → `\`, çift/sondaki ayırıcılar atılır; `..` ya da `.` bileşeni varsa null (yol geçişi engeli). */
    private fun normalizePath(path: String): String? {
        val parts = path.replace('/', '\\').split('\\').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) return null
        return parts.joinToString("\\")
    }

    // ------------------------------------------------------------------ dizin verisi

    private class IndexedFile(
        val shared: SharedFile,
        val lowerPath: String,
        val level: ShareLevel,
        val group: String?,
        val ref: String,
    )

    private class IndexedFolder(
        val path: String,
        val level: ShareLevel,
        val group: String?,
        /** [IndexData.files] içindeki indeksler (dosya adına göre sıralı). */
        val fileIndices: IntArray,
    ) {
        val lowerPath: String = path.lowercase()

        fun toShared(data: IndexData) = SharedFolder(path, List(fileIndices.size) { data.files[fileIndices[it]].shared })
    }

    private class IndexData(
        val source: ShareSnapshot,
        /** Sanal yola göre sıralı. */
        val files: List<IndexedFile>,
        val folders: List<IndexedFolder>,
        val words: Map<String, IntArray>,
        val fileByPath: Map<String, Int>,
        val fileByLower: Map<String, Int>,
        val folderByPath: Map<String, IndexedFolder>,
        val folderByLower: Map<String, IndexedFolder>,
    ) {
        val folderCount: Int get() = folders.size

        /** Protokol §9 adım 6: tam kelimelerin kesişimi, kısmi (sonek) kelimeler, hariç tutulanlar. */
        fun match(included: Set<String>, partial: Set<String>, excluded: Set<String>): IntArray? {
            val lists = ArrayList<IntArray>(included.size)
            for (word in included) lists.add(words[word] ?: return null)
            lists.sortBy { it.size }
            var result = lists[0]
            for (i in 1 until lists.size) {
                result = intersect(result, lists[i])
                if (result.isEmpty()) return null
            }
            for (p in partial) {
                val inResult = BitSet()
                for (idx in result) inResult.set(idx)
                val union = BitSet()
                for ((word, postings) in words) {
                    if (word.length < p.length || !word.endsWith(p)) continue
                    for (idx in postings) if (inResult.get(idx)) union.set(idx)
                }
                if (union.isEmpty) return null
                result = toArray(union)
            }
            if (excluded.isNotEmpty()) {
                val remove = BitSet()
                for (word in excluded) words[word]?.let { for (idx in it) remove.set(idx) }
                if (!remove.isEmpty) {
                    var kept = 0
                    val out = IntArray(result.size)
                    for (idx in result) if (!remove.get(idx)) out[kept++] = idx
                    if (kept == 0) return null
                    result = out.copyOf(kept)
                }
            }
            return result
        }

        private fun intersect(small: IntArray, large: IntArray): IntArray {
            val out = IntArray(small.size)
            var n = 0
            for (idx in small) {
                if (java.util.Arrays.binarySearch(large, idx) >= 0) out[n++] = idx
            }
            return if (n == out.size) out else out.copyOf(n)
        }

        private fun toArray(bits: BitSet): IntArray {
            val out = IntArray(bits.cardinality())
            var i = 0
            var next = bits.nextSetBit(0)
            while (next >= 0) {
                out[i++] = next
                next = bits.nextSetBit(next + 1)
            }
            return out
        }

        companion object {
            val EMPTY = build(ShareSnapshot.EMPTY)

            fun build(snapshot: ShareSnapshot): IndexData {
                // 1) tüm dosyaları sanal yolla topla
                class Raw(val file: IndexedFile, val folder: String)

                val raws = ArrayList<Raw>()
                for (root in snapshot.roots) {
                    val alias = ShareSnapshot.sanitizeAlias(root.alias)
                    for (folder in root.folders) {
                        val rel = folder.path.replace('/', '\\').trim('\\')
                        val virtualFolder = ShareSnapshot.virtualFolderPath(alias, rel)
                        for (f in folder.files) {
                            if (f.name.isEmpty() || '\\' in f.name || '/' in f.name) continue
                            val path = "$virtualFolder\\${f.name}"
                            val shared = SharedFile(path = path, size = f.size, attributes = f.attributes)
                            raws.add(Raw(IndexedFile(shared, path.lowercase(), root.level, root.group, f.ref), virtualFolder))
                        }
                    }
                }
                raws.sortBy { it.file.lowerPath }

                // 2) dosya listesi, yol tabloları, kelime dizini
                val files = ArrayList<IndexedFile>(raws.size)
                val byPath = HashMap<String, Int>(raws.size * 2)
                val byLower = HashMap<String, Int>(raws.size * 2)
                val postings = HashMap<String, IntBuilder>()
                val folderFiles = LinkedHashMap<String, ArrayList<Int>>()
                for ((i, raw) in raws.withIndex()) {
                    files.add(raw.file)
                    byPath[raw.file.shared.path] = i
                    byLower[raw.file.lowerPath] = i
                    for (word in SearchWords.indexWords(raw.file.shared.path)) {
                        postings.getOrPut(word) { IntBuilder() }.add(i)
                    }
                    folderFiles.getOrPut(raw.folder) { ArrayList() }.add(i)
                }
                val words = HashMap<String, IntArray>(postings.size * 2)
                for ((word, builder) in postings) words[word] = builder.toArray()

                // 3) klasörler (yola göre sıralı); bir klasörün tüm dosyaları aynı kökten gelir → tek kademe
                val folders = ArrayList<IndexedFolder>(folderFiles.size)
                for ((path, indices) in folderFiles) {
                    val sample = files[indices[0]]
                    val sorted = indices.sortedBy { files[it].shared.name.lowercase() }.toIntArray()
                    folders.add(IndexedFolder(path, sample.level, sample.group, sorted))
                }
                folders.sortBy { it.lowerPath }
                val folderByPath = HashMap<String, IndexedFolder>(folders.size * 2)
                val folderByLower = HashMap<String, IndexedFolder>(folders.size * 2)
                for (folder in folders) {
                    folderByPath[folder.path] = folder
                    folderByLower[folder.path.lowercase()] = folder
                }
                return IndexData(snapshot, files, folders, words, byPath, byLower, folderByPath, folderByLower)
            }
        }
    }

    /** Büyüyen int dizisi (kelime başına tahsis yükünü düşük tutar). */
    private class IntBuilder {
        private var data = IntArray(4)
        private var size = 0
        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    companion object {
        private const val TAG = "ShareIndex"
        const val REASON_NOT_SHARED = "File not shared."
        const val REASON_BANNED = "Banned"
        private val WHITESPACE = Regex("\\s+")
    }
}
