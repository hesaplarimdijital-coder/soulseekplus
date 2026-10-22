package com.soulseekplus.core.view

import com.soulseekplus.core.model.BrowseResult
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder

/**
 * Bir kullanıcının paylaşım listesinden kurulan klasör ağacı (göz atma ekranı). Soulseek yalnızca düz klasör listesi
 * gönderir; ara klasörler yol bileşenlerinden türetilir. Açık ve kilitli klasörler tek ağaçta birleşir, kilitli olanlar
 * işaretlidir. Değişmezdir; iş parçacığı güvenlidir.
 */
class BrowseTree(result: BrowseResult) {

    class Node internal constructor(
        /** Tam sanal yol (`Root\Sub\Leaf`). */
        val path: String,
        val name: String,
        /** Klasörün kendi girdisi kilitli (ya da yalnızca kilitli içerik taşıyor). */
        val locked: Boolean,
        /** Doğrudan içindeki dosyalar (ada göre sıralı). */
        val files: List<SharedFile>,
        children: List<Node>,
    ) {
        val children: List<Node> = children

        /** Bu klasördeki ve altındakilerin toplam dosya sayısı. */
        val totalFiles: Int = files.size + children.sumOf { it.totalFiles }

        /** Bu klasördeki ve altındakilerin toplam boyutu. */
        val totalSize: Long = files.sumOf { it.size } + children.sumOf { it.totalSize }
    }

    companion object {
        /** Kabul edilen en derin klasör (bileşen sayısı); daha derin girdiler gösterilmez. */
        const val MAX_DEPTH = 256
    }

    /** Arama eşleşmesi. */
    data class Match(val file: SharedFile, val folderPath: String, val locked: Boolean)

    val roots: List<Node>
    val username: String = result.username
    val fileCount: Int = result.fileCount
    val folderCount: Int

    private val byPath: Map<String, Node>
    private val lockedPaths: Set<String>

    init {
        class Builder(val path: String) {
            var files: List<SharedFile> = emptyList()
            var locked = false
            var hasOpenContent = false
            val children = LinkedHashMap<String, Builder>()
        }

        val rootBuilders = LinkedHashMap<String, Builder>()
        val all = HashMap<String, Builder>()
        fun ensure(path: String): Builder {
            all[path]?.let { return it }
            val builder = Builder(path)
            all[path] = builder
            val parent = path.substringBeforeLast('\\', "")
            if (parent.isEmpty()) rootBuilders[path] = builder else ensure(parent).children[path] = builder
            return builder
        }

        fun add(folder: SharedFolder, locked: Boolean) {
            val path = folder.path.trim('\\')
            if (path.isEmpty()) return
            // Güvenilmeyen veri: ağaç kurma/gezinme özyinelemelidir; on binlerce bileşenli bir yol yığın taşmasıyla
            // (StackOverflowError, uygulama çöker) sonuçlanırdı. Gerçek paylaşımlar bu derinliğe yaklaşmaz.
            if (path.count { it == '\\' } >= MAX_DEPTH) return
            val builder = ensure(path)
            builder.files = (builder.files + folder.files).sortedBy { it.name.lowercase() }
            if (locked) builder.locked = true else builder.hasOpenContent = true
        }
        result.folders.forEach { add(it, locked = false) }
        result.lockedFolders.forEach { add(it, locked = true) }

        val nodes = HashMap<String, Node>()
        fun build(b: Builder): Node {
            val kids = b.children.values.sortedBy { it.path.substringAfterLast('\\').lowercase() }.map { build(it) }
            // Yalnızca kilitli içerik taşıyan klasör kilitli sayılır; ara klasör kilitli çocuklara rağmen açıktır.
            val locked = b.locked && !b.hasOpenContent
            val node = Node(b.path, b.path.substringAfterLast('\\'), locked, b.files, kids)
            nodes[b.path] = node
            return node
        }
        roots = rootBuilders.values.sortedBy { it.path.lowercase() }.map { build(it) }
        byPath = nodes
        folderCount = nodes.size
        lockedPaths = nodes.values.filter { it.locked }.mapTo(HashSet()) { it.path }
    }

    fun node(path: String): Node? = byPath[path.trim('\\')]

    fun isLocked(path: String): Boolean {
        var p = path.trim('\\')
        while (p.isNotEmpty()) {
            if (p in lockedPaths) return true
            p = p.substringBeforeLast('\\', "")
        }
        return false
    }

    /** Klasör ve tüm alt klasörlerindeki dosyalar. */
    fun filesRecursive(path: String): List<SharedFile> {
        val node = node(path) ?: return emptyList()
        val out = ArrayList<SharedFile>(node.totalFiles)
        fun walk(n: Node) {
            out.addAll(n.files)
            n.children.forEach { walk(it) }
        }
        walk(node)
        return out
    }

    /** Tüm klasör yolları (alfabetik). */
    fun allFolderPaths(): List<String> = byPath.keys.sortedBy { it.lowercase() }

    /**
     * Dosya yolunda tüm sözcüklerin (küçük harf, boşlukla ayrılmış) geçtiği dosyalar. [limit] kadar sonuç.
     * Sözcük yoksa boş liste.
     */
    fun search(query: String, limit: Int = 5000): List<Match> {
        val words = query.lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        for (node in byPath.values.sortedBy { it.path.lowercase() }) {
            val folderLower = node.path.lowercase()
            for (file in node.files) {
                val text = folderLower + "\\" + file.name.lowercase()
                if (words.all { text.contains(it) }) {
                    out.add(Match(file, node.path, isLocked(node.path)))
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }
}
