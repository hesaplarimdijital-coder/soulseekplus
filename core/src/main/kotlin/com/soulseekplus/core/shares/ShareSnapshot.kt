package com.soulseekplus.core.shares

import com.soulseekplus.core.api.UploadSource
import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.ShareLevel

/**
 * Paylaşılan bir dosya. [ref] uygulama katmanına özgü opak başvurudur (Android'de SAF belge URI'si);
 * çekirdek yalnızca [UploadOpener]'a geri verir.
 */
data class ShareFile(
    val name: String,
    val size: Long,
    val attributes: FileAttributes = FileAttributes.EMPTY,
    val ref: String = "",
)

/** Bir kökün altındaki klasör ve doğrudan içindeki dosyalar. [path] köke göre, `\` ayırıcılı; kökün kendisi için boş. */
data class ShareFolder(
    val path: String,
    val files: List<ShareFile>,
)

/**
 * Paylaşım kökü: kullanıcıya görünen sanal ad ([alias]), erişim kademesi ve (GROUP için) grup adı.
 * Sanal yol = `alias\klasör\alt\dosya`.
 */
data class ShareRoot(
    val id: String,
    val alias: String,
    val level: ShareLevel = ShareLevel.PUBLIC,
    val group: String? = null,
    val folders: List<ShareFolder> = emptyList(),
)

/** Sanal yola karşılık gelen dosyanın yükleme kaynağını açar (dosya artık yoksa null). */
fun interface UploadOpener {
    fun open(virtualPath: String, ref: String): UploadSource?
}

/** Tüm paylaşımların değişmez anlık görüntüsü; [ShareIndex.update] ile atomik olarak değiştirilir. */
class ShareSnapshot(
    val roots: List<ShareRoot>,
    val opener: UploadOpener,
) {
    companion object {
        val EMPTY = ShareSnapshot(emptyList()) { _, _ -> null }

        /** `alias\rel\path` biçiminde sanal yol birleştirir. */
        fun virtualFolderPath(alias: String, relativeFolder: String): String =
            if (relativeFolder.isEmpty()) alias else "$alias\\$relativeFolder"

        /** Takma adı yol ayırıcılarından arındırır (Nicotine+ get_normalized_virtual_name). */
        fun sanitizeAlias(alias: String): String {
            var name = alias.replace('/', '_').replace('\\', '_').trim().trim('"').trim()
            if (name.isEmpty()) name = "Shared"
            if (name == "." || name == "..") name = name.replace('.', '_')
            return name
        }
    }
}
