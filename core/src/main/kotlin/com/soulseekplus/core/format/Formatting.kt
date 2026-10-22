package com.soulseekplus.core.format

import java.util.Locale

/**
 * Arayüzün ortak, yerelleştirmeden bağımsız biçimlendiricileri (sayılar ve birimler her dilde aynı yazılır).
 * Ondalık ayırıcı için [Locale.ROOT] kullanılır; tablo sütunlarında hizalama tutarlı kalsın diye.
 */
object Formatting {

    /** 1536 → "1.5 KiB". */
    fun size(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100) String.format(Locale.ROOT, "%.0f %s", value, units[unit])
        else if (value >= 10) String.format(Locale.ROOT, "%.1f %s", value, units[unit])
        else String.format(Locale.ROOT, "%.2f %s", value, units[unit])
    }

    /** Bayt/saniye → "1.2 MiB/s". 0 ise "-". */
    fun speed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "-" else size(bytesPerSecond) + "/s"

    /** Süre: 75 → "1:15", 3725 → "1:02:05". */
    fun duration(seconds: Int?): String {
        if (seconds == null || seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Kalan süre (ETA): saniye → "1:15" ya da "1:02:05"; bilinmiyorsa "-". */
    fun eta(seconds: Long?): String = if (seconds == null || seconds < 0) "-" else duration(seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    /** 44100 → "44.1 kHz". */
    fun sampleRate(hz: Int?): String {
        if (hz == null || hz <= 0) return ""
        val khz = hz / 1000.0
        return if (hz % 1000 == 0) String.format(Locale.ROOT, "%.0f kHz", khz) else String.format(Locale.ROOT, "%.1f kHz", khz)
    }

    /** "ISO ülke kodu" → bayrak emojisi; geçersizse boş dize. */
    fun flag(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return ""
        val upper = countryCode.uppercase(Locale.ROOT)
        if (upper.any { it !in 'A'..'Z' }) return ""
        val base = 0x1F1E6
        val sb = StringBuilder(4)
        for (c in upper) sb.appendCodePoint(base + (c - 'A'))
        return sb.toString()
    }

    /** Yüzde: 0.4237f → "42%". */
    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100f).toInt()}%"

    /** Sanal yoldaki (`\` ayırıcılı) son bileşen. */
    fun leafName(virtualPath: String): String = virtualPath.substringAfterLast('\\')

    /** Ses kalitesi özeti: "FLAC 44.1 kHz / 16 bit", "MP3 320 kbps VBR". */
    fun quality(extension: String, bitrate: Int?, sampleRateHz: Int?, bitDepth: Int?, vbr: Boolean?): String {
        val parts = ArrayList<String>(3)
        if (bitDepth != null && bitDepth > 0) parts += "$bitDepth bit"
        if (sampleRateHz != null && sampleRateHz > 0) parts += sampleRate(sampleRateHz)
        if (bitrate != null && bitrate > 0) parts += "$bitrate kbps" + if (vbr == true) " VBR" else ""
        return parts.joinToString(" / ").ifEmpty { extension.uppercase(Locale.ROOT) }
    }
}
