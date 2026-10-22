package com.soulseekplus.core.protocol

import com.soulseekplus.core.model.FileAttributes
import com.soulseekplus.core.model.SharedFile
import com.soulseekplus.core.model.SharedFolder

/**
 * Dosya girdisi, dosya listesi, paylaşım listesi ve arama sonucu gövdelerinin kodlanması (bkz. docs/slsk-protocol.md §5).
 * Buradaki işlevler sıkıştırılmamış gövdeyi üretir/okur; zlib [PeerMessages] katmanındadır.
 */
object SharesCodec {

    private const val ATTR_BITRATE = 0
    private const val ATTR_LENGTH = 1
    private const val ATTR_VBR = 2
    private const val ATTR_SAMPLE_RATE = 4
    private const val ATTR_BIT_DEPTH = 5

    /** Bir dosya girdisinin en küçük boyutu: kod(1) + ad(4) + boyut(8) + uzantı(4) + öznitelik sayısı(4). */
    private const val MIN_FILE_ENTRY = 21

    // ------------------------------------------------------------------------------------------------- dosya girdisi

    /** [name]: paylaşım/klasör listelerinde dosya adı, arama sonuçlarında tam sanal yol. */
    fun writeFileEntry(w: MessageWriter, name: String, file: SharedFile) {
        w.uint8(1).string(name).uint64(file.size.coerceAtLeast(0)).string("")
        val attrs = ArrayList<Pair<Int, Int>>(4)
        val a = file.attributes
        val bitrate = a.bitrate?.takeIf { it > 0 }
        if (a.bitDepth != null) {
            // Kayıpsız: [length][sampleRate][bitDepth]
            a.durationSeconds?.let { attrs.add(ATTR_LENGTH to it) }
            a.sampleRate?.let { attrs.add(ATTR_SAMPLE_RATE to it) }
            attrs.add(ATTR_BIT_DEPTH to a.bitDepth)
        } else {
            // Kayıplı: [bitrate][length][vbr]
            bitrate?.let { attrs.add(ATTR_BITRATE to it) }
            a.durationSeconds?.let { attrs.add(ATTR_LENGTH to it) }
            if (bitrate != null && a.vbr != null) attrs.add(ATTR_VBR to if (a.vbr) 1 else 0)
        }
        w.uint32(attrs.size.toLong())
        for ((code, value) in attrs) w.uint32(code.toLong()).uint32(value.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Bir dosya girdisi okur. [toPath] ağdaki adı ([name]) tam sanal yola çevirir (klasör listelerinde klasör eklenir).
     */
    fun readFileEntry(r: MessageReader, toPath: (String) -> String): SharedFile {
        r.uint8() // kod (her zaman 1)
        val name = r.string().replace('/', '\\')
        val rawSize = r.uint64()
        // Soulseek NS hatası: en anlamlı bayt 0xFF ise dosya >2 GiB'tır ve yalnızca alt 32 bit anlamlıdır.
        val size = when {
            (rawSize ushr 56) == 0xFFL -> rawSize and 0xFFFFFFFFL
            rawSize < 0 -> 0L
            else -> rawSize
        }
        val extLen = r.uint32()
        if (extLen > r.remaining) throw ProtocolException("Geçersiz uzantı uzunluğu")
        r.skip(extLen.toInt())
        val n = r.count(8)
        var bitrate: Long? = null
        var length: Long? = null
        var vbr: Boolean? = null
        var sampleRate: Long? = null
        var bitDepth: Long? = null
        repeat(n) {
            val code = r.uint32().toInt()
            val value = r.uint32()
            when (code) {
                ATTR_BITRATE -> bitrate = value
                ATTR_LENGTH -> length = value
                ATTR_VBR -> vbr = value != 0L
                ATTR_SAMPLE_RATE -> sampleRate = value
                ATTR_BIT_DEPTH -> bitDepth = value
            }
        }
        val path = toPath(name)
        return SharedFile(path = path, size = size, attributes = deriveAttributes(size, bitrate, length, vbr, sampleRate, bitDepth))
    }

    /** Nicotine+ `parse_audio_quality_length` kuralları. */
    private fun deriveAttributes(size: Long, bitrate: Long?, length: Long?, vbr: Boolean?, sampleRate: Long?, bitDepth: Long?): FileAttributes {
        var br = bitrate
        if (br == null && sampleRate != null && bitDepth != null) br = sampleRate * bitDepth * 2 / 1000
        if (br != null && (br <= 0 || br > Int.MAX_VALUE)) br = null
        var len = length
        if (len == null && br != null && br > 0) len = size / (br * 125)
        return FileAttributes(
            bitrate = br?.toInt(),
            durationSeconds = len?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
            vbr = vbr,
            sampleRate = sampleRate?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            bitDepth = bitDepth?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
        )
    }

    // ------------------------------------------------------------------------------------------------- listeler

    /** Klasör listesi girdisi içindeki dosya listesi (ad = yalnızca dosya adı). */
    fun writeFolderFileList(w: MessageWriter, files: List<SharedFile>) {
        w.uint32(files.size.toLong())
        for (f in files) writeFileEntry(w, f.name, f)
    }

    fun readFolderFileList(r: MessageReader, folderPath: String): List<SharedFile> =
        r.readList(MIN_FILE_ENTRY) { readFileEntry(this) { name -> if (folderPath.isEmpty()) name else "$folderPath\\$name" } }

    fun writeFolder(w: MessageWriter, folder: SharedFolder) {
        w.string(folder.path)
        writeFolderFileList(w, folder.files)
    }

    fun readFolder(r: MessageReader): SharedFolder {
        val path = r.string().replace('/', '\\')
        return SharedFolder(path, readFolderFileList(r, path))
    }

    fun writeFolders(w: MessageWriter, folders: List<SharedFolder>) {
        w.uint32(folders.size.toLong())
        for (f in folders) writeFolder(w, f)
    }

    fun readFolders(r: MessageReader): List<SharedFolder> = r.readList(8) { readFolder(this) }

    /** Tam yollu dosya listesi (arama sonucu). */
    fun writeResultFiles(w: MessageWriter, files: List<SharedFile>) {
        w.uint32(files.size.toLong())
        for (f in files) writeFileEntry(w, f.path, f)
    }

    fun readResultFiles(r: MessageReader): List<SharedFile> = r.readList(MIN_FILE_ENTRY) { readFileEntry(this) { it } }

    // ------------------------------------------------------------------------------------------------- paylaşım listesi

    /** SharedFileListResponse gövdesi (sıkıştırılmamış). */
    fun encodeSharedList(folders: List<SharedFolder>, locked: List<SharedFolder>): ByteArray {
        val w = MessageWriter(1024)
        writeFolders(w, folders)
        w.uint32(0) // bilinmeyen
        if (locked.isNotEmpty()) writeFolders(w, locked)
        return w.toByteArray()
    }

    fun decodeSharedList(body: ByteArray): Pair<List<SharedFolder>, List<SharedFolder>> {
        val r = MessageReader(body)
        val folders = readFolders(r)
        var locked: List<SharedFolder> = emptyList()
        if (r.remaining >= 4) {
            r.uint32() // bilinmeyen
            if (r.remaining >= 4) locked = readFolders(r)
        }
        return folders to locked
    }
}
