package com.soulseekplus.data.shares

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.ShareLevel
import com.soulseekplus.core.model.ShareStats
import com.soulseekplus.core.model.UserLists
import com.soulseekplus.core.shares.ShareFile
import com.soulseekplus.core.shares.ShareFolder
import com.soulseekplus.core.shares.ShareIndex
import com.soulseekplus.core.shares.ShareRoot
import com.soulseekplus.core.shares.ShareSnapshot
import com.soulseekplus.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Arayüzde gösterilen paylaşım kökü özeti. */
data class ShareRootInfo(
    val id: String,
    val treeUri: String,
    val alias: String,
    val level: ShareLevel,
    val group: String?,
    val folderCount: Int,
    val fileCount: Int,
    val totalBytes: Long,
    /** Son başarılı tarama (ms); 0 = henüz taranmadı. */
    val scannedAtMillis: Long,
    /** SAF izni sürüyor ve son tarama kökü okuyabildi. Değilse kök paylaşılmaz (önbellek korunur). */
    val accessible: Boolean,
)

enum class ScanPhase { IDLE, LISTING, READING_TAGS }

/** Tarama ilerlemesi: [folders]/[files] listeleme sayacı, [tagsDone]/[tagsTotal] ses başlığı okuma sayacı. */
data class ScanProgress(
    val phase: ScanPhase = ScanPhase.IDLE,
    val rootAlias: String = "",
    val folders: Int = 0,
    val files: Int = 0,
    val currentFolder: String = "",
    val tagsDone: Int = 0,
    val tagsTotal: Int = 0,
) {
    val active: Boolean get() = phase != ScanPhase.IDLE

    companion object {
        val IDLE = ScanProgress()
    }
}

/**
 * Paylaşımların uygulama tarafı: SAF kökleri, Room önbelleği ve [ShareIndex] ile köprü.
 *
 * - [start]: önbellekten yükler (disk taraması yapmaz), anlık görüntüyü dizine verir; hiç taranmamış kökleri tarar.
 *   Kullanıcı listeleri değişince dizini yeniden besler (arkadaş/grup erişimi anında yansır).
 * - Kök ekleme/çıkarma/yeniden tarama arka planda çalışır; sonuçlar [roots] ve [progress] akışlarında görünür.
 * - Yükleme için dosya açma işi [SafUploadOpener]'dadır (dosya tanıtıcısında O(1) konumlama).
 */
class ShareRepository(
    context: Context,
    private val index: ShareIndex,
    private val userLists: StateFlow<UserLists>,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {

    private val appContext: Context = context.applicationContext
    private val resolver = appContext.contentResolver
    private val database by lazy { SharesDatabase.get(appContext) }
    private val dao: SharesDao get() = database.dao()
    private val scanner = ShareScanner(appContext, logger)

    /** [loaded] ve yayınlanan anlık görüntüyü korur. */
    private val stateMutex = Mutex()
    private val loaded = LinkedHashMap<String, LoadedRoot>()

    /** Taramalar sırayla çalışır. */
    private val scanMutex = Mutex()
    private val scanJobs = HashSet<Job>()

    private val pushLock = Any()

    @Volatile
    private var snapshot: ShareSnapshot = ShareSnapshot.EMPTY

    @Volatile
    private var started = false

    private val _roots = MutableStateFlow<List<ShareRootInfo>>(emptyList())
    private val _progress = MutableStateFlow(ScanProgress.IDLE)
    private val _loaded = MutableStateFlow(false)

    /** Kökler (sıralı). */
    val roots: StateFlow<List<ShareRootInfo>> = _roots.asStateFlow()

    /** Çalışan taramanın ilerlemesi; boştayken [ScanProgress.IDLE]. */
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /** Önbellek okundu ve ilk anlık görüntü dizine verildi. */
    val cacheLoaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Sunucuya bildirilen paylaşım istatistiği (dizinden). */
    val stats: StateFlow<ShareStats> get() = index.stats

    /** Room önbelleğinden yükler; hiç taranmamış kökleri tarar. Bir kez çağrılır. */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            userLists.collect { push() }
        }
        scope.launch(Dispatchers.IO) {
            val unscanned = try {
                loadCache()
            } catch (e: Exception) {
                logger.error(TAG, "Paylaşım önbelleği okunamadı", e)
                emptyList()
            }
            _loaded.value = true
            for (id in unscanned) rescan(id)
        }
    }

    // ------------------------------------------------------------------ kök yönetimi

    /**
     * Seçilen klasör ağacını paylaşıma ekler (`ACTION_OPEN_DOCUMENT_TREE` sonucu). Kalıcı okuma izni alınır;
     * alınamazsa false. Ekleme ve ilk tarama arka planda sürer.
     */
    fun addRoot(treeUri: Uri, level: ShareLevel = ShareLevel.PUBLIC, group: String? = null, alias: String? = null): Boolean {
        try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            logger.warn(TAG, "Kalıcı izin alınamadı: $treeUri", e)
            return false
        }
        scope.launch(Dispatchers.IO) {
            val id = stateMutex.withLock {
                if (loaded.values.any { it.config.treeUri == treeUri.toString() }) return@withLock null
                val name = alias?.takeIf { it.isNotBlank() } ?: displayName(treeUri)
                val entity = RootEntity(
                    id = UUID.randomUUID().toString(),
                    treeUri = treeUri.toString(),
                    alias = uniqueAlias(name, null),
                    level = level.name,
                    groupName = group?.takeIf { level == ShareLevel.GROUP && it.isNotBlank() },
                    scannedAtMillis = 0L,
                    position = (loaded.values.maxOfOrNull { it.config.position } ?: -1) + 1,
                )
                dao.upsertRoot(entity)
                loaded[entity.id] = loadedOf(entity, emptyList(), accessible = true)
                publishLocked()
                entity.id
            }
            if (id != null) rescan(id)
        }
        return true
    }

    /** Takma adı, erişim kademesini ve grubu değiştirir (yeniden tarama gerekmez). */
    fun updateRoot(id: String, alias: String, level: ShareLevel, group: String?) {
        scope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                val current = loaded[id] ?: return@withLock
                val updated = current.config.copy(
                    alias = uniqueAlias(alias, id),
                    level = level.name,
                    groupName = group?.takeIf { level == ShareLevel.GROUP && it.isNotBlank() },
                )
                dao.upsertRoot(updated)
                loaded[id] = current.copy(config = updated)
                publishLocked()
            }
        }
    }

    /** Kökü paylaşımdan kaldırır, önbelleğini siler ve (başka kök kullanmıyorsa) kalıcı izni bırakır. */
    fun removeRoot(id: String) {
        scope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                val removed = loaded.remove(id) ?: return@withLock
                dao.removeRoot(id)
                if (loaded.values.none { it.config.treeUri == removed.config.treeUri }) {
                    try {
                        resolver.releasePersistableUriPermission(
                            Uri.parse(removed.config.treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (e: SecurityException) {
                        logger.debug(TAG, "İzin zaten yok: ${e.message}")
                    }
                }
                publishLocked()
            }
        }
    }

    /** [rootId] null ise tüm kökleri yeniden tarar. Taramalar sırayla çalışır. */
    fun rescan(rootId: String? = null) {
        val job = scope.launch(Dispatchers.IO) {
            scanMutex.withLock {
                val ids = if (rootId != null) listOf(rootId) else stateMutex.withLock { loaded.keys.toList() }
                for (id in ids) {
                    try {
                        scanRoot(id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(TAG, "Tarama başarısız: $id", e)
                    }
                }
            }
        }
        synchronized(scanJobs) { scanJobs += job }
        job.invokeOnCompletion {
            synchronized(scanJobs) {
                scanJobs -= job
                if (scanJobs.isEmpty()) _progress.value = ScanProgress.IDLE
            }
        }
    }

    /** Çalışan ve bekleyen taramaları iptal eder; o ana kadar bulunanlar korunur. */
    fun cancelScan() {
        val jobs = synchronized(scanJobs) { scanJobs.toList() }
        jobs.forEach { it.cancel() }
    }

    // ------------------------------------------------------------------ yükleme

    /** Önbelleği yükler; hiç taranmamış kök kimliklerini döndürür. */
    private suspend fun loadCache(): List<String> {
        val entities = dao.roots()
        val persisted = resolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val fresh = LinkedHashMap<String, LoadedRoot>()
        for (entity in entities) {
            fresh[entity.id] = loadedOf(entity, loadFolders(entity.id), accessible = entity.treeUri in persisted)
        }
        stateMutex.withLock {
            for ((id, root) in fresh) if (id !in loaded) loaded[id] = root
            publishLocked()
        }
        return entities.filter { it.scannedAtMillis == 0L }.map { it.id }
    }

    private suspend fun loadFolders(rootId: String): List<ShareFolder> {
        val folders = ArrayList<ShareFolder>()
        var currentPath: String? = null
        var bucket = ArrayList<ShareFile>()
        var afterId = 0L
        while (true) {
            val page = dao.filesPage(rootId, afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                if (row.folder != currentPath) {
                    if (currentPath != null) folders += ShareFolder(currentPath, bucket)
                    currentPath = row.folder
                    bucket = ArrayList()
                }
                bucket += ShareFile(row.name, row.size, attributesOf(row), row.docId)
            }
            afterId = page.last().id
        }
        if (currentPath != null) folders += ShareFolder(currentPath, bucket)
        return folders
    }

    private suspend fun loadPrevious(rootId: String): Map<String, ScanFile> {
        val out = HashMap<String, ScanFile>()
        var afterId = 0L
        while (true) {
            val page = dao.filesPage(rootId, afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            for (row in page) {
                out[row.docId] = ScanFile(row.docId, row.name, row.size, row.modified, attributesOf(row), row.attrsRead)
            }
            afterId = page.last().id
        }
        return out
    }

    // ------------------------------------------------------------------ tarama

    private suspend fun scanRoot(rootId: String) {
        val entry = stateMutex.withLock { loaded[rootId] } ?: return
        val config = entry.config
        val treeUri = Uri.parse(config.treeUri)
        _progress.value = ScanProgress(ScanPhase.LISTING, config.alias)

        val previous = if (config.scannedAtMillis > 0) loadPrevious(rootId) else emptyMap()
        val scanned = scanner.scan(treeUri, previous) { folders, files, current ->
            _progress.value = ScanProgress(ScanPhase.LISTING, config.alias, folders, files, current)
        }
        if (scanned == null) {
            logger.warn(TAG, "Kök okunamadı, önbellek korunuyor: ${config.alias}")
            stateMutex.withLock {
                val current = loaded[rootId] ?: return@withLock
                loaded[rootId] = current.copy(accessible = false)
                publishLocked()
            }
            return
        }

        val now = System.currentTimeMillis()
        dao.replaceFiles(rootId, entitiesOf(rootId, scanned), now)
        val stillPresent = stateMutex.withLock {
            val current = loaded[rootId] ?: return@withLock false
            loaded[rootId] = loadedOf(current.config.copy(scannedAtMillis = now), foldersOf(scanned), accessible = true)
            publishLocked()
            true
        }
        if (!stillPresent) {
            // Tarama sürerken kök kaldırılmış: yetim satırları temizle.
            dao.removeRoot(rootId)
            return
        }
        if (SettingsRepository.readAppPrefs(appContext).readAudioAttributes) {
            readTags(rootId, treeUri, config.alias, scanned)
        }
    }

    /** Başlığı henüz okunmamış FLAC/WAV/MP3 dosyalarının özniteliklerini okur; yarıda kesilse bile bulunanlar korunur. */
    private suspend fun readTags(rootId: String, treeUri: Uri, alias: String, scanned: List<ScanFolder>) {
        val todo = ArrayList<ScanFile>()
        for (folder in scanned) for (file in folder.files) {
            if (!file.attributesRead && ShareScanner.isReadable(file)) todo += file
        }
        if (todo.isEmpty()) return
        var changed = 0
        val pending = ArrayList<AttributeUpdate>()
        try {
            for ((i, file) in todo.withIndex()) {
                currentCoroutineContext().ensureActive()
                val attrs = scanner.readAttributes(treeUri, file)
                if (attrs != null) {
                    file.attributes = attrs
                    file.attributesRead = true
                    changed++
                    pending += AttributeUpdate(file.docId, attrs.bitrate, attrs.durationSeconds, attrs.vbr, attrs.sampleRate, attrs.bitDepth)
                }
                if (pending.size >= FLUSH_EVERY) {
                    dao.updateAttributeBatch(rootId, pending.toList())
                    pending.clear()
                }
                if (i % 8 == 0) _progress.value = ScanProgress(ScanPhase.READING_TAGS, alias, tagsDone = i, tagsTotal = todo.size)
            }
        } finally {
            withContext(NonCancellable) {
                if (pending.isNotEmpty()) dao.updateAttributeBatch(rootId, pending.toList())
                if (changed > 0) {
                    stateMutex.withLock {
                        val current = loaded[rootId] ?: return@withLock
                        loaded[rootId] = loadedOf(current.config, foldersOf(scanned), current.accessible)
                        publishLocked()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ anlık görüntü

    /** [stateMutex] tutulurken çağrılır. */
    private fun publishLocked() {
        val all = loaded.values.toList()
        _roots.value = all.map { it.toInfo() }
        val active = all.filter { it.accessible }
        val roots = active.map {
            ShareRoot(
                id = it.config.id,
                alias = it.config.alias,
                level = levelOf(it.config.level),
                group = it.config.groupName,
                folders = it.folders,
            )
        }
        val trees = active.associate { it.config.alias.lowercase() to Uri.parse(it.config.treeUri) }
        snapshot = ShareSnapshot(roots, SafUploadOpener(appContext, trees, logger))
        push()
    }

    private fun push() {
        synchronized(pushLock) {
            try {
                index.update(snapshot, userLists.value)
            } catch (e: Exception) {
                logger.error(TAG, "Paylaşım dizini güncellenemedi", e)
            }
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun uniqueAlias(wanted: String, excludeId: String?): String {
        val base = ShareSnapshot.sanitizeAlias(wanted)
        val taken = loaded.values.filter { it.config.id != excludeId }.map { it.config.alias.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    private fun displayName(treeUri: Uri): String {
        val fromProvider = try {
            DocumentFile.fromTreeUri(appContext, treeUri)?.name
        } catch (e: Exception) {
            null
        }
        if (!fromProvider.isNullOrBlank()) return fromProvider
        val id = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            ""
        }
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { "Shared" }
    }

    private fun attributesOf(row: FileEntity): FileAttributes =
        if (row.bitrate == null && row.durationSeconds == null && row.vbr == null && row.sampleRate == null && row.bitDepth == null) {
            FileAttributes.EMPTY
        } else {
            FileAttributes(row.bitrate, row.durationSeconds, row.vbr, row.sampleRate, row.bitDepth)
        }

    private fun entitiesOf(rootId: String, folders: List<ScanFolder>): List<FileEntity> {
        val out = ArrayList<FileEntity>()
        for (folder in folders) for (file in folder.files) {
            val a = file.attributes
            out += FileEntity(
                rootId = rootId,
                folder = folder.path,
                name = file.name,
                size = file.size,
                modified = file.modified,
                docId = file.docId,
                bitrate = a.bitrate,
                durationSeconds = a.durationSeconds,
                vbr = a.vbr,
                sampleRate = a.sampleRate,
                bitDepth = a.bitDepth,
                attrsRead = file.attributesRead,
            )
        }
        return out
    }

    private fun foldersOf(scanned: List<ScanFolder>): List<ShareFolder> =
        scanned.map { folder ->
            ShareFolder(folder.path, folder.files.map { ShareFile(it.name, it.size, it.attributes, it.docId) })
        }

    private fun levelOf(name: String): ShareLevel =
        try {
            ShareLevel.valueOf(name)
        } catch (e: IllegalArgumentException) {
            ShareLevel.PUBLIC
        }

    private fun loadedOf(config: RootEntity, folders: List<ShareFolder>, accessible: Boolean): LoadedRoot {
        var files = 0
        var bytes = 0L
        for (folder in folders) {
            files += folder.files.size
            for (file in folder.files) bytes += file.size
        }
        return LoadedRoot(config, folders, accessible, files, bytes)
    }

    private data class LoadedRoot(
        val config: RootEntity,
        val folders: List<ShareFolder>,
        val accessible: Boolean,
        val fileCount: Int,
        val totalBytes: Long,
    ) {
        fun toInfo(): ShareRootInfo = ShareRootInfo(
            id = config.id,
            treeUri = config.treeUri,
            alias = config.alias,
            level = try {
                ShareLevel.valueOf(config.level)
            } catch (e: IllegalArgumentException) {
                ShareLevel.PUBLIC
            },
            group = config.groupName,
            folderCount = folders.size,
            fileCount = fileCount,
            totalBytes = totalBytes,
            scannedAtMillis = config.scannedAtMillis,
            accessible = accessible,
        )
    }

    private companion object {
        const val TAG = "Shares"
        const val PAGE_SIZE = 5000
        const val FLUSH_EVERY = 400
    }
}
