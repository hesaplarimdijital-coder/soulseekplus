package com.soulseekplus.data.shares

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.shares.AudioHeaderParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

/** Taramadan çıkan dosya. [attributesRead] başlığı okunmuş (ya da okunamadığı kesinleşmiş) demektir. */
internal class ScanFile(
    val docId: String,
    val name: String,
    val size: Long,
    val modified: Long,
    var attributes: FileAttributes,
    var attributesRead: Boolean,
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Bir klasör ve doğrudan içindeki dosyalar; [path] köke göre `\` ayırıcılı (kök için boş). */
internal class ScanFolder(val path: String, val files: MutableList<ScanFile>)

/**
 * Bir SAF ağacını gezer. Her klasör tek cursor sorgusuyla listelenir; saklı dosyalar (`.` ile başlayan),
 * boş dosyalar ve alt klasörü olmayan boş klasörler atlanır. Bloklayıcı işleri IO dispatcher'ında yapar.
 */
internal class ShareScanner(context: Context, private val logger: Logger) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /**
     * [treeUri] altındaki tüm dosyalar. Kök listelenemiyorsa (izin kaybı, çıkarılmış kart) null.
     * [previous]: önceki taramadaki dosyalar (belge kimliğine göre); boyut ve değişiklik zamanı aynıysa
     * ses öznitelikleri yeniden okunmaz. [onProgress] yaklaşık 64 dosyada bir çağrılır.
     */
    suspend fun scan(
        treeUri: Uri,
        previous: Map<String, ScanFile>,
        onProgress: (folders: Int, files: Int, currentFolder: String) -> Unit,
    ): List<ScanFolder>? = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = ArrayList<ScanFolder>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to "")
        var fileCount = 0
        var lastReport = 0
        var visited = 0
        var first = true

        while (stack.isNotEmpty()) {
            ensureActive()
            val (docId, relPath) = stack.removeLast()
            val children = try {
                SafCursor.queryChildren(resolver, treeUri, docId)
            } catch (e: Exception) {
                logger.warn(TAG, "Klasör listelenemedi: $relPath", e)
                null
            }
            if (children == null) {
                if (first) return@withContext null
                logger.warn(TAG, "Klasör atlandı (cursor yok): $relPath")
                continue
            }
            first = false
            visited++

            val files = ArrayList<ScanFile>()
            val dirs = ArrayList<SafDoc>()
            for (child in children) {
                val name = child.name.replace('\\', '_').replace('/', '_')
                if (name.isEmpty() || name.startsWith('.')) continue
                if (child.isDirectory) {
                    if (depth(relPath) < MAX_DEPTH) dirs += child.copy(name = name)
                } else if (child.size > 0) {
                    val old = previous[child.docId]
                    val reuse = old != null && old.size == child.size && old.modified == child.lastModified
                    files += ScanFile(
                        docId = child.docId,
                        name = name,
                        size = child.size,
                        modified = child.lastModified,
                        attributes = if (reuse) old!!.attributes else FileAttributes.EMPTY,
                        attributesRead = reuse && old!!.attributesRead,
                    )
                }
            }
            if (files.isNotEmpty()) {
                files.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                out += ScanFolder(relPath, files)
                fileCount += files.size
            }
            // Ters sırayla yığına koyarız ki klasörler ada göre sırayla işlensin.
            dirs.sortWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
            for (dir in dirs) {
                stack.addLast(dir.docId to (if (relPath.isEmpty()) dir.name else relPath + "\\" + dir.name))
            }
            if (fileCount - lastReport >= REPORT_EVERY || visited % 8 == 0) {
                lastReport = fileCount
                onProgress(out.size, fileCount, relPath)
            }
        }
        onProgress(out.size, fileCount, "")
        out
    }

    /**
     * Dosyanın başlığından ses özniteliklerini okur. Desteklenmeyen/okunamayan dosyada [FileAttributes.EMPTY];
     * dosya hiç açılamazsa null (sonra yeniden denenir).
     */
    fun readAttributes(treeUri: Uri, file: ScanFile): FileAttributes? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.docId)
        return try {
            val stream = resolver.openInputStream(uri) ?: return null
            BufferedInputStream(stream, BUFFER).use { input ->
                AudioHeaderParser.parse(file.extension, file.size, input) ?: FileAttributes.EMPTY
            }
        } catch (e: Exception) {
            logger.debug(TAG, "Başlık okunamadı: ${file.name}: ${e.message}")
            null
        }
    }

    private fun depth(relPath: String): Int = if (relPath.isEmpty()) 0 else relPath.count { it == '\\' } + 1

    companion object {
        private const val TAG = "ShareScan"
        private const val MAX_DEPTH = 48
        private const val REPORT_EVERY = 64
        private const val BUFFER = 16 * 1024

        /** Başlığı okunabilecek uzantı mı. */
        fun isReadable(file: ScanFile): Boolean = file.extension in AudioHeaderParser.SUPPORTED_EXTENSIONS
    }
}
