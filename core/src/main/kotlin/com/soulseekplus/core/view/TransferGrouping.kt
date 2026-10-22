package com.soulseekplus.core.view

import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.model.TransferState

/** Transfer tablosu süzgeç kategorileri. */
enum class TransferCategory {
    ALL,
    ACTIVE,
    WAITING,
    FINISHED,
    FAILED;

    fun matches(t: Transfer): Boolean = when (this) {
        ALL -> true
        ACTIVE -> t.state.isActive
        WAITING -> t.state == TransferState.QUEUED || t.state == TransferState.QUEUED_REMOTE ||
            t.state == TransferState.PAUSED || t.state == TransferState.USER_OFFLINE
        FINISHED -> t.state == TransferState.FINISHED
        FAILED -> t.state == TransferState.FAILED || t.state == TransferState.DENIED || t.state == TransferState.CANCELLED
    }
}

enum class TransferGroupMode { NONE, USER, FOLDER }

enum class TransferColumn { USER, FILE, FOLDER, SIZE, PROGRESS, SPEED, STATE, QUEUE, ADDED }

data class TransferSort(val column: TransferColumn = TransferColumn.ADDED, val ascending: Boolean = true)

sealed interface TransferRow {
    val key: String
}

data class TransferGroupHeader(
    override val key: String,
    val mode: TransferGroupMode,
    val username: String,
    val folder: String?,
    val count: Int,
    val finished: Int,
    val active: Int,
    val failed: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speed: Long,
    val expanded: Boolean,
) : TransferRow {
    val progress: Float get() = if (totalBytes <= 0) 0f else (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val title: String get() = if (mode == TransferGroupMode.FOLDER) folder.orEmpty() else username
}

data class TransferItemRow(val transfer: Transfer, val depth: Int) : TransferRow {
    override val key: String get() = transfer.id
}

/** İndirme/yükleme listesini süzer, sıralar, kullanıcıya ya da klasöre göre gruplar. Saftır. */
object TransferGrouping {

    fun comparator(sort: TransferSort): Comparator<Transfer> {
        val base: Comparator<Transfer> = when (sort.column) {
            TransferColumn.USER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.username }
            TransferColumn.FILE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName }
            TransferColumn.FOLDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.folder }
            TransferColumn.SIZE -> compareBy { it.size }
            TransferColumn.PROGRESS -> compareBy { it.progress }
            TransferColumn.SPEED -> compareBy { it.speedBytesPerSecond }
            TransferColumn.STATE -> compareBy { it.state.ordinal }
            TransferColumn.QUEUE -> compareBy { it.queuePosition ?: Int.MAX_VALUE }
            TransferColumn.ADDED -> compareBy { it.addedAtMillis }
        }
        val directed = if (sort.ascending) base else base.reversed()
        return directed.thenComparing(compareBy<Transfer> { it.addedAtMillis }).thenComparing(compareBy { it.id })
    }

    fun filter(transfers: List<Transfer>, category: TransferCategory, query: String): List<Transfer> {
        val q = query.trim().lowercase()
        return transfers.filter { t ->
            category.matches(t) && (q.isEmpty() || t.virtualPath.lowercase().contains(q) || t.username.lowercase().contains(q))
        }
    }

    fun groupKey(mode: TransferGroupMode, t: Transfer): String = when (mode) {
        TransferGroupMode.USER -> "g:u:${t.username}"
        TransferGroupMode.FOLDER -> "g:f:${t.username}\u0000${t.folder}"
        TransferGroupMode.NONE -> ""
    }

    fun rows(
        transfers: List<Transfer>,
        mode: TransferGroupMode,
        sort: TransferSort,
        isExpanded: (String) -> Boolean = { true },
    ): List<TransferRow> {
        val order = comparator(sort)
        val sorted = transfers.sortedWith(order)
        if (mode == TransferGroupMode.NONE) return sorted.map { TransferItemRow(it, 0) }
        val buckets = LinkedHashMap<String, MutableList<Transfer>>()
        for (t in sorted) buckets.getOrPut(groupKey(mode, t)) { ArrayList() }.add(t)
        val out = ArrayList<TransferRow>(sorted.size + buckets.size)
        for ((key, members) in buckets) {
            val expanded = isExpanded(key)
            val first = members[0]
            out += TransferGroupHeader(
                key = key,
                mode = mode,
                username = first.username,
                folder = if (mode == TransferGroupMode.FOLDER) first.folder else null,
                count = members.size,
                finished = members.count { it.state == TransferState.FINISHED },
                active = members.count { it.state.isActive },
                failed = members.count { it.state == TransferState.FAILED || it.state == TransferState.DENIED },
                totalBytes = members.sumOf { it.size },
                transferredBytes = members.sumOf { if (it.state == TransferState.FINISHED) it.size else it.bytesTransferred },
                speed = members.sumOf { it.speedBytesPerSecond },
                expanded = expanded,
            )
            if (expanded) members.mapTo(out) { TransferItemRow(it, 1) }
        }
        return out
    }

    /** Başlık satırına ait tüm transferler (grup kapalı olsa da). */
    fun membersOf(header: TransferGroupHeader, transfers: List<Transfer>): List<Transfer> =
        transfers.filter { groupKey(header.mode, it) == header.key }
}
