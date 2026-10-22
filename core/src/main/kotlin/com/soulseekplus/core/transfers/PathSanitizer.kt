package com.soulseekplus.core.transfers

/** Uzaktan gelen dosya/klasör adlarını yerel dosya sistemi için güvenli hâle getirir (yol geçişi, kontrol karakterleri). */
object PathSanitizer {

    private const val MAX_SEGMENT_BYTES = 200
    private val ILLEGAL = charArrayOf('<', '>', ':', '"', '|', '?', '*', '/', '\\')

    /** Tek bir ad parçası (dosya ya da klasör adı). Boş sonuç "_" olur. */
    fun segment(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c.isISOControl() -> sb.append('_')
                c in ILLEGAL -> sb.append('_')
                else -> sb.append(c)
            }
        }
        var s = sb.toString().trim().trimEnd('.', ' ')
        s = s.trimStart('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") return "_"
        return truncateBytes(s, MAX_SEGMENT_BYTES)
    }

    /** `/` ya da `\` ile ayrılmış göreli klasör yolunu temizler; boşsa null. `..` ve boş parçalar atılır. */
    fun subfolder(raw: String?): String? {
        if (raw == null) return null
        val parts = raw.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        if (parts.isEmpty()) return null
        return parts.joinToString("/") { segment(it) }
    }

    /** Dosya adını korurken uzantıyı kesmeden uzun adı kısaltır. */
    private fun truncateBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
        var base = s.substring(0, s.length - ext.length)
        while (base.isNotEmpty() && (base + ext).toByteArray(Charsets.UTF_8).size > maxBytes) base = base.dropLast(1)
        return (base.ifEmpty { "_" }) + ext
    }
}
