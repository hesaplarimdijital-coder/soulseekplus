package com.soulseekplus.data.shares

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Tek sorguda gelen belge satırı. [size] bilinmiyorsa -1, [lastModified] bilinmiyorsa 0. */
internal data class SafDoc(
    val docId: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * `DocumentsContract` cursor sorguları (ad/boyut/zaman/tür tek sorguda; düğüm başına ek sorgu yok).
 * Bloklayıcıdır; IO dispatcher'ında çağrılmalıdır. Cursor her zaman kapatılır.
 */
internal object SafCursor {
    private const val COL_ID = 0
    private const val COL_NAME = 1
    private const val COL_MIME = 2
    private const val COL_SIZE = 3
    private const val COL_MODIFIED = 4

    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    /** [parentDocId]'in doğrudan alt öğeleri; sağlayıcı cursor vermezse (erişim yok / çıkarılmış kart) null. */
    fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<SafDoc>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        cursor.use { c ->
            val out = ArrayList<SafDoc>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) readRow(c)?.let(out::add)
            return out
        }
    }

    /** Tek belge; yoksa ya da sağlayıcı cursor vermezse null. */
    fun queryDocument(resolver: ContentResolver, treeUri: Uri, docId: String): SafDoc? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        cursor.use { c ->
            return if (c.moveToFirst()) readRow(c) else null
        }
    }

    private fun readRow(c: Cursor): SafDoc? {
        val id = c.getString(COL_ID) ?: return null
        val name = c.getString(COL_NAME) ?: return null
        val mime = if (c.isNull(COL_MIME)) null else c.getString(COL_MIME)
        val isDir = mime == Document.MIME_TYPE_DIR
        val size = when {
            isDir -> 0L
            c.isNull(COL_SIZE) -> -1L
            else -> c.getLong(COL_SIZE)
        }
        val modified = if (c.isNull(COL_MODIFIED)) 0L else c.getLong(COL_MODIFIED)
        return SafDoc(id, name, size, modified, mime)
    }
}
