package com.soulseekplus.core.model

/**
 * Soulseek dosya öznitelikleri (protokol öznitelik kodları: 0 bitrate, 1 süre, 2 VBR, 4 örnekleme hızı, 5 bit derinliği).
 * Bilinmeyen değerler null'dır.
 */
data class FileAttributes(
    val bitrate: Int? = null,
    val durationSeconds: Int? = null,
    val vbr: Boolean? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
) {
    companion object {
        val EMPTY = FileAttributes()
    }
}

/**
 * Ağda görülen ya da paylaşılan bir dosya.
 *
 * [path] her zaman Soulseek sanal yoludur: ayırıcı ters eğik çizgidir (`\`), ör. `Music\Artist\Album\01 Song.flac`.
 * [extension] küçük harfli, noktasız uzantıdır (protokoldeki alan boş gelebilir; o zaman yoldan türetilir).
 */
data class SharedFile(
    val path: String,
    val size: Long,
    val extension: String = extensionOf(path),
    val attributes: FileAttributes = FileAttributes.EMPTY,
) {
    /** Yalnızca dosya adı. */
    val name: String get() = path.substringAfterLast('\\')

    /** Üst klasörün tam sanal yolu (dosya kökteyse boş). */
    val folder: String get() = if ('\\' in path) path.substringBeforeLast('\\') else ""

    /** Kayıpsız ses biçimi mi (flac, alac, ape, wav, aiff, wv, dsf, dff). */
    val isLossless: Boolean get() = extension in LOSSLESS_EXTENSIONS

    companion object {
        val LOSSLESS_EXTENSIONS = setOf("flac", "alac", "ape", "wav", "aif", "aiff", "wv", "dsf", "dff", "tta")
        val AUDIO_EXTENSIONS = LOSSLESS_EXTENSIONS + setOf("mp3", "ogg", "opus", "m4a", "aac", "wma", "mpc")

        fun extensionOf(path: String): String {
            val name = path.substringAfterLast('\\').substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
        }
    }
}

/** Bir klasör ve doğrudan içindeki dosyalar (protokoldeki dizin girdisi; alt klasörler ayrı girdidir). */
data class SharedFolder(
    val path: String,
    val files: List<SharedFile>,
) {
    val name: String get() = path.substringAfterLast('\\')
    val totalSize: Long get() = files.sumOf { it.size }
}

/** Paylaşım istatistiği (sunucuya SharedFoldersFiles ile bildirilir). */
data class ShareStats(val folders: Int, val files: Int) {
    companion object {
        val EMPTY = ShareStats(0, 0)
    }
}

/**
 * Bir kullanıcının paylaşım ağacı (SharedFileListResponse).
 * [folders] herkese açık klasörler; [lockedFolders] isteyenin erişimi olmayan, kilitli gösterilen klasörler.
 */
data class BrowseResult(
    val username: String,
    val folders: List<SharedFolder>,
    val lockedFolders: List<SharedFolder> = emptyList(),
) {
    val fileCount: Int get() = folders.sumOf { it.files.size } + lockedFolders.sumOf { it.files.size }
}

/** Paylaşım erişim kademesi (Nicotine+ 3 kademeli paylaşım). Sıra önemlidir: daha kısıtlı olan daha büyüktür. */
enum class ShareLevel {
    /** Herkese açık. */
    PUBLIC,

    /** Yalnızca arkadaş listesindekiler. */
    BUDDIES,

    /** Yalnızca seçili kullanıcı grubu üyeleri. */
    GROUP,
}
