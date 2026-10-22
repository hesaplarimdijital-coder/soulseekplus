package com.soulseekplus.data.shares

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.soulseekplus.core.api.Logger
import com.soulseekplus.core.api.UploadSource
import com.soulseekplus.core.shares.UploadOpener
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Sanal yoldan (`takma-ad\klasör\dosya`) SAF belgesini bulup yükleme kaynağı üretir.
 * [trees]: küçük harfli takma ad → ağaç URI'si. Dosyanın var olup olmadığı ve gerçek boyutu açılışta sorgulanır.
 */
internal class SafUploadOpener(
    context: Context,
    private val trees: Map<String, Uri>,
    private val logger: Logger,
) : UploadOpener {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun open(virtualPath: String, ref: String): UploadSource? {
        val alias = virtualPath.substringBefore('\\').lowercase()
        val tree = trees[alias] ?: return null
        val doc = try {
            SafCursor.queryDocument(resolver, tree, ref)
        } catch (e: Exception) {
            logger.warn(TAG, "Belge sorgulanamadı: $virtualPath", e)
            null
        } ?: return null
        if (doc.isDirectory) return null
        return DocumentSource(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, ref), doc.size)
    }

    private class DocumentSource(
        private val resolver: ContentResolver,
        private val uri: Uri,
        knownSize: Long,
    ) : UploadSource {

        override val size: Long = if (knownSize >= 0) knownSize else statSize()

        override fun open(offset: Long): InputStream {
            val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
            try {
                val stream = FileInputStream(pfd.fileDescriptor)
                if (offset > 0) seek(stream, offset)
                return object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            pfd.close()
                        }
                    }
                }
            } catch (e: Throwable) {
                try {
                    pfd.close()
                } catch (ignored: IOException) {
                    // asıl hata önemli
                }
                throw e
            }
        }

        /** Dosya tanıtıcısında O(1) konumlama; konumlanamayan (boru tabanlı) sağlayıcılarda atlayarak. */
        private fun seek(stream: FileInputStream, offset: Long) {
            try {
                stream.channel.position(offset)
                return
            } catch (e: IOException) {
                // Konumlanamıyor: aşağıda okuyarak atlanır.
            }
            var remaining = offset
            val scratch = ByteArray(8192)
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    val read = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                    if (read < 0) return
                    remaining -= read
                }
            }
        }

        private fun statSize(): Long = try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize.coerceAtLeast(0L) } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private companion object {
        const val TAG = "ShareOpen"
    }
}
