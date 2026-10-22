package com.soulseekplus.core.search

import com.soulseekplus.core.model.GroupMode
import com.soulseekplus.core.model.SearchHit

/** Sonuç tablosunun sıralanabilir sütunları. */
enum class ResultColumn { USER, FILE, FOLDER, TYPE, SIZE, BITRATE, SAMPLE_RATE, BIT_DEPTH, DURATION, FREE_SLOT, SPEED, QUEUE, COUNTRY }

data class ResultSort(val column: ResultColumn = ResultColumn.SPEED, val ascending: Boolean = false)

enum class GroupKind { FOLDER, USER }

/** Tabloya giden satır: grup başlığı ya da dosya. */
sealed interface ResultRow {
    /** Liste anahtarı; yeniden sıralamada sabit kalır. */
    val key: String

    /** 0: üst düzey, 1: grup altı. */
    val depth: Int
}

/**
 * Grup başlığı satırı (Nicotine+ ağaç görünümü). Toplamlar grubun *tüm* dosyalarını sayar (daraltılmış olsa da).
 * [username] ve [folderPath] kind'a göre dolu: FOLDER → ikisi de; USER → yalnızca username.
 */
data class GroupHeaderRow(
    override val key: String,
    val kind: GroupKind,
    val username: String,
    val folderPath: String?,
    val fileCount: Int,
    val totalSize: Long,
    /** Bu grupta farklı kullanıcı sayısı (FOLDER'da 1, USER'da 1; ileride birleşik gruplar için). */
    val userCount: Int,
    val lockedCount: Int,
    val freeSlots: Boolean,
    val averageSpeed: Int,
    val queueLength: Long,
    val countryCode: String?,
    /** Grubun en yüksek bitrate'i (bilinmiyorsa null). */
    val bestBitrate: Int?,
    /** Grup içinde kayıpsız dosya var mı. */
    val hasLossless: Boolean,
    val expanded: Boolean,
) : ResultRow {
    override val depth: Int get() = 0

    /** Başlıkta gösterilecek ad: FOLDER'da klasör adı, USER'da kullanıcı adı. */
    val title: String get() = if (kind == GroupKind.FOLDER) folderPath.orEmpty() else username
}

data class FileResultRow(
    override val key: String,
    val hit: SearchHit,
    override val depth: Int,
) : ResultRow

object SearchGrouping {

    /** Tek bir sütun için karşılaştırıcı (artan). */
    fun comparator(column: ResultColumn): Comparator<SearchHit> = when (column) {
        ResultColumn.USER -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.username }
        ResultColumn.FILE -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        ResultColumn.FOLDER -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.file.folder }
        ResultColumn.TYPE -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.file.extension }
        ResultColumn.SIZE -> compareBy { it.file.size }
        ResultColumn.BITRATE -> compareBy { it.file.attributes.bitrate ?: -1 }
        ResultColumn.SAMPLE_RATE -> compareBy { it.file.attributes.sampleRate ?: -1 }
        ResultColumn.BIT_DEPTH -> compareBy { it.file.attributes.bitDepth ?: -1 }
        ResultColumn.DURATION -> compareBy { it.file.attributes.durationSeconds ?: -1 }
        ResultColumn.FREE_SLOT -> compareBy { if (it.freeSlots) 1 else 0 }
        ResultColumn.SPEED -> compareBy { it.averageSpeed }
        // Kuyruk uzunluğu küçük olan "daha iyi"; artan sıralamada küçükler önce gelir.
        ResultColumn.QUEUE -> compareBy { if (it.freeSlots) 0L else it.queueLength }
        ResultColumn.COUNTRY -> compareBy<SearchHit, String>(String.CASE_INSENSITIVE_ORDER) { it.countryCode.orEmpty() }
    }

    /** Eşitlikte kararlı bir toplam sıra: seçilen sütun, sonra yol, sonra kullanıcı. */
    fun comparator(sort: ResultSort): Comparator<SearchHit> {
        val primary = comparator(sort.column).let { if (sort.ascending) it else it.reversed() }
        return primary
            .thenComparing(comparator(ResultColumn.FOLDER))
            .thenComparing(comparator(ResultColumn.FILE))
            .thenComparing(comparator(ResultColumn.USER))
    }

    /**
     * Tablo satırlarını üretir.
     * - FLAT: yalnızca dosya satırları, [sort]'a göre.
     * - USER: kullanıcı başlığı + dosyaları. FOLDER: (kullanıcı, klasör) başlığı + dosyaları.
     *
     * Gruplar kendi içindeki sıralamada ilk (en iyi) dosyaya göre; SIZE sütununda toplam boyuta, USER/FOLDER/FILE
     * sütunlarında ada göre sıralanır. [isExpanded] başlık anahtarı için açık mı sorar; kapalı grubun dosyaları yayımlanmaz.
     */
    fun rows(
        hits: List<SearchHit>,
        mode: GroupMode,
        sort: ResultSort = ResultSort(),
        isExpanded: (String) -> Boolean = { true },
    ): List<ResultRow> {
        val fileOrder = comparator(sort)
        if (mode == GroupMode.FLAT) {
            return hits.sortedWith(fileOrder).map { FileResultRow(it.key, it, 0) }
        }
        val kind = if (mode == GroupMode.FOLDER) GroupKind.FOLDER else GroupKind.USER
        val buckets = LinkedHashMap<String, MutableList<SearchHit>>()
        for (hit in hits) {
            val key = groupKey(kind, hit)
            buckets.getOrPut(key) { ArrayList() }.add(hit)
        }
        val groups = buckets.map { (key, members) ->
            members.sortWith(fileOrder)
            Group(key, members)
        }
        val groupOrder: Comparator<Group> = when (sort.column) {
            ResultColumn.SIZE -> compareBy<Group> { g -> g.members.sumOf { it.file.size } }
            ResultColumn.USER -> compareBy<Group, String>(String.CASE_INSENSITIVE_ORDER) { it.members[0].username }
            ResultColumn.FOLDER, ResultColumn.FILE ->
                if (kind == GroupKind.FOLDER) compareBy<Group, String>(String.CASE_INSENSITIVE_ORDER) { it.members[0].file.folder }
                else compareBy<Group, String>(String.CASE_INSENSITIVE_ORDER) { it.members[0].username }
            else -> Comparator { a, b -> fileOrder.compare(a.members[0], b.members[0]) }
        }.let { if (sort.ascending || sort.column !in ORDER_BY_GROUP_FIELD) it else it.reversed() }
        val ordered = groups.sortedWith(groupOrder.thenBy { it.key })

        val out = ArrayList<ResultRow>(hits.size + ordered.size)
        for (group in ordered) {
            val expanded = isExpanded(group.key)
            out += header(kind, group, expanded)
            if (expanded) group.members.mapTo(out) { FileResultRow(it.key, it, 1) }
        }
        return out
    }

    /** Başlık anahtarı (collapsed kümesinde tutulur). */
    fun groupKey(kind: GroupKind, hit: SearchHit): String =
        if (kind == GroupKind.FOLDER) "g:f:${hit.username}\u0000${hit.file.folder}" else "g:u:${hit.username}"

    /** Bir başlık satırına ait dosyaları (grup daraltılmış olsa bile) bulur. */
    fun membersOf(header: GroupHeaderRow, hits: List<SearchHit>): List<SearchHit> =
        hits.filter { groupKey(header.kind, it) == header.key }

    private val ORDER_BY_GROUP_FIELD = setOf(ResultColumn.SIZE, ResultColumn.USER, ResultColumn.FOLDER, ResultColumn.FILE)

    private class Group(val key: String, val members: MutableList<SearchHit>)

    private fun header(kind: GroupKind, group: Group, expanded: Boolean): GroupHeaderRow {
        val first = group.members[0]
        var total = 0L
        var locked = 0
        var best: Int? = null
        var lossless = false
        for (m in group.members) {
            total += m.file.size
            if (m.locked) locked++
            m.file.attributes.bitrate?.let { if (best == null || it > best!!) best = it }
            if (m.file.isLossless) lossless = true
        }
        return GroupHeaderRow(
            key = group.key,
            kind = kind,
            username = first.username,
            folderPath = if (kind == GroupKind.FOLDER) first.file.folder else null,
            fileCount = group.members.size,
            totalSize = total,
            userCount = 1,
            lockedCount = locked,
            freeSlots = first.freeSlots,
            averageSpeed = first.averageSpeed,
            queueLength = first.queueLength,
            countryCode = first.countryCode,
            bestBitrate = best,
            hasLossless = lossless,
            expanded = expanded,
        )
    }
}
