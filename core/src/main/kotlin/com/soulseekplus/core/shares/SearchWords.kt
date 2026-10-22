package com.soulseekplus.core.shares

/**
 * Nicotine+ kelime bölme kuralı: noktalama (ASCII + Unicode noktalama) boşluk sayılır, küçük harfe çevrilir.
 * Paylaşım dizini ve gelen arama aynı kuralı kullanır.
 */
object SearchWords {

    fun isPunctuation(codePoint: Int): Boolean {
        if (codePoint < 128) {
            return codePoint in 33..47 || codePoint in 58..64 || codePoint in 91..96 || codePoint in 123..126
        }
        return when (Character.getType(codePoint).toByte()) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }
    }

    /** `text` içindeki kelimeler (zaten küçük harfli olması beklenir), sıralı ve tekrarlı. */
    fun split(text: String): List<String> {
        val words = ArrayList<String>()
        var start = -1
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val separator = Character.isWhitespace(cp) || isPunctuation(cp)
            if (separator) {
                if (start >= 0) {
                    words.add(text.substring(start, i))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
            i += width
        }
        if (start >= 0) words.add(text.substring(start))
        return words
    }

    /** Dosya yolu için dizin kelimeleri: klasör yolu + dosya adı, küçük harf, dosya başına tekil. */
    fun indexWords(virtualPath: String): Set<String> {
        val lower = virtualPath.lowercase()
        return LinkedHashSet(split(lower))
    }
}
