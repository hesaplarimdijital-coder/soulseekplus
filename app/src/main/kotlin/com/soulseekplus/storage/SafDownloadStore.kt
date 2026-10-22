package com.soulseekplus.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.soulseekplus.core.api.DownloadStore
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.model.Transfer
import com.soulseekplus.core.transfers.PathSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * [DownloadStore]'un Storage Access Framework uygulaması (kullanıcının seçtiği ağaç URI'si altında).
 *
 * - Dosya önce `<ad>.<özet>.part` olarak yazılır; [complete] son adına taşır (çakışmada " (1)", " (2)" ... eklenir).
 * - Uzak taraftan gelen klasör/dosya adları [PathSanitizer] ile temizlenir; `..` ve ayırıcılar ad parçası olamaz.
 * - Tüm SAF çağrıları IO dağıtıcısında yapılır. SAF sağlayıcıları çeşitli RuntimeException'lar fırlatabildiği için
 *   hepsi [IOException]'a çevrilir (motor yerel dosya hatası olarak ele alır).
 * - Klasör/dosya oluşturma tek bir [Mutex] altında sıralanır; böylece eşzamanlı iki indirme aynı alt klasörü iki kez
 *   ("Album" ve "Album (1)") oluşturmaz. Kilit yalnızca yerel SAF çağrılarını sıralar, ağ G/Ç'si tutulurken alınmaz.
 */
class SafDownloadStore(
    context: Context,
    private val rootUri: () -> String?,
    private val logger: Logger,
) : DownloadStore {

    private val appContext: Context = context.applicationContext
    private val createLock = Mutex()

    @Volatile
    private var readyCache: ReadyCache? = null

    private class ReadyCache(val root: String?, val ready: Boolean, val atMillis: Long)

    // ---------------------------------------------------------------------------------------- DownloadStore

    /**
     * Kök seçili, kalıcı yazma izni var ve dizin yazılabilir mi. Sonuç kısa süre önbelleğe alınır
     * (motor bunu sık sorar; her çağrı bir ContentResolver sorgusudur).
     */
    override fun isReady(): Boolean {
        val raw = rootUri()?.takeIf { it.isNotBlank() }
        val now = System.currentTimeMillis()
        val cached = readyCache
        if (cached != null && cached.root == raw && now - cached.atMillis in 0..READY_CACHE_MILLIS) return cached.ready
        val ready = try {
            checkReady(raw)
        } catch (e: Exception) {
            logger.warn(TAG, "İndirme klasörü denetlenemedi: ${e.message}")
            false
        }
        readyCache = ReadyCache(raw, ready, now)
        return ready
    }

    override suspend fun partialSize(transfer: Transfer): Long = withContext(Dispatchers.IO) {
        saf {
            val dir = resolveDir(requireRoot(), subfolderOf(transfer), create = false) ?: return@saf 0L
            val part = dir.findFile(partName(transfer))
            if (part != null && part.isFile) part.length().coerceAtLeast(0L) else 0L
        }
    }

    override suspend fun openPartial(transfer: Transfer, offset: Long): OutputStream = withContext(Dispatchers.IO) {
        require(offset >= 0) { "offset < 0" }
        val resolver = appContext.contentResolver
        val target = saf {
            createLock.withLock {
                val dir = resolveDir(requireRoot(), subfolderOf(transfer), create = true)
                    ?: throw IOException("Download folder is not available")
                val name = partName(transfer)
                val existing = dir.findFile(name)?.takeIf { it.isFile }
                val existingSize = existing?.length() ?: 0L
                when {
                    offset > 0 -> {
                        // Devam: yalnızca kısmi dosya tam olarak beklenen boyuttaysa eklenir. Aksi hâlde başka bir
                        // yerden devam etmek dosyayı bozardı; hata verilir, bir sonraki denemede boyut yeniden okunur.
                        if (existing == null || existingSize != offset) {
                            throw IOException("Partial file size changed ($existingSize != $offset)")
                        }
                        Target(existing.uri, "wa")
                    }
                    existing != null -> Target(existing.uri, "wt")
                    else -> {
                        val created = dir.createFile(MIME_BINARY, name)
                            ?: throw IOException("Could not create $name")
                        Target(created.uri, "wt")
                    }
                }
            }
        }
        val raw = try {
            resolver.openOutputStream(target.uri, target.mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("Could not open the partial file: ${e.message}", e)
        } ?: throw IOException("Could not open the partial file")
        BufferedOutputStream(raw, WRITE_BUFFER_BYTES)
    }

    override suspend fun complete(transfer: Transfer): String = withContext(Dispatchers.IO) {
        saf {
            createLock.withLock {
                val dir = resolveDir(requireRoot(), subfolderOf(transfer), create = false)
                    ?: throw IOException("Download folder is not available")
                val part = dir.findFile(partName(transfer))?.takeIf { it.isFile }
                    ?: throw IOException("Partial file is missing")
                val finalName = uniqueName(dir, finalName(transfer))
                if (!part.renameTo(finalName)) throw IOException("Could not rename to $finalName")
                part.uri.toString()
            }
        }
    }

    override suspend fun discard(transfer: Transfer): Unit = withContext(Dispatchers.IO) {
        saf {
            val dir = resolveDir(requireRoot(), subfolderOf(transfer), create = false) ?: return@saf
            val part = dir.findFile(partName(transfer)) ?: return@saf
            if (part.isFile && !part.delete()) {
                logger.warn(TAG, "Kısmi dosya silinemedi: ${part.name}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------- yardımcılar

    private class Target(val uri: Uri, val mode: String)

    private fun checkReady(raw: String?): Boolean {
        if (raw == null) return false
        val uri = Uri.parse(raw)
        val granted = appContext.contentResolver.persistedUriPermissions.any {
            it.isWritePermission && sameTree(it.uri, uri)
        }
        if (!granted) return false
        val root = DocumentFile.fromTreeUri(appContext, uri) ?: return false
        return root.exists() && root.isDirectory && root.canWrite()
    }

    private fun sameTree(a: Uri, b: Uri): Boolean {
        if (a == b) return true
        return try {
            a.authority == b.authority &&
                DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
        } catch (_: Exception) {
            false
        }
    }

    private fun requireRoot(): DocumentFile {
        val raw = rootUri()?.takeIf { it.isNotBlank() } ?: throw IOException("Download folder is not set")
        val root = DocumentFile.fromTreeUri(appContext, Uri.parse(raw))
            ?: throw IOException("Download folder is not valid")
        if (!root.isDirectory) throw IOException("Download folder is not available")
        return root
    }

    /** Alt klasör yolunu (zaten temizlenmiş olsa da) yeniden temizler; yol geçişi imkânsızdır. */
    private fun subfolderOf(transfer: Transfer): String? = PathSanitizer.subfolder(transfer.destinationSubfolder)

    private fun finalName(transfer: Transfer): String = PathSanitizer.segment(transfer.fileName)

    /**
     * `<ad>.<özet>.part`: özet kullanıcı adı + uzak yoldan türetilir; böylece farklı kullanıcılardan gelen aynı adlı
     * dosyalar aynı klasöre inerken birbirinin kısmi dosyasına yazmaz ve devam ofseti doğru dosyadan okunur.
     */
    private fun partName(transfer: Transfer): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest((transfer.username + "\u0000" + transfer.virtualPath).toByteArray(Charsets.UTF_8))
        val tag = digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return finalName(transfer) + "." + tag + PART_SUFFIX
    }

    /** [root] altında [subfolder] (`/` ayrılmış) klasörünü bulur; [create] ise eksik parçaları oluşturur. */
    private fun resolveDir(root: DocumentFile, subfolder: String?, create: Boolean): DocumentFile? {
        var dir = root
        if (subfolder == null) return dir
        for (part in subfolder.split('/')) {
            val existing = dir.findFile(part)
            dir = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> throw IOException("A file is in the way of folder \"$part\"")
                create -> dir.createDirectory(part) ?: throw IOException("Could not create folder \"$part\"")
                else -> return null
            }
        }
        return dir
    }

    /** [name] [dir] içinde yoksa kendisi; varsa "ad (1).uzantı", "ad (2).uzantı" ... (büyük/küçük harf duyarsız). */
    private fun uniqueName(dir: DocumentFile, name: String): String {
        val taken = HashSet<String>()
        for (child in dir.listFiles()) child.name?.let { taken.add(it.lowercase(Locale.ROOT)) }
        if (name.lowercase(Locale.ROOT) !in taken) return name
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS
        val base = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        for (i in 1..MAX_COLLISION_SUFFIX) {
            val candidate = "$base ($i)$ext"
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        }
        throw IOException("Too many files named $name")
    }

    /** SAF sağlayıcılarının fırlattığı RuntimeException'ları (SecurityException, IllegalArgumentException ...) IOException yapar. */
    private inline fun <T> saf(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: e.javaClass.simpleName, e)
    }

    private companion object {
        const val TAG = "SafStore"
        const val PART_SUFFIX = ".part"

        /** "application/octet-stream": sağlayıcı ada uzantı eklemez. */
        const val MIME_BINARY = "application/octet-stream"
        const val READY_CACHE_MILLIS = 3_000L
        const val WRITE_BUFFER_BYTES = 128 * 1024
        const val MAX_EXTENSION_CHARS = 10
        const val MAX_COLLISION_SUFFIX = 9_999
    }
}
