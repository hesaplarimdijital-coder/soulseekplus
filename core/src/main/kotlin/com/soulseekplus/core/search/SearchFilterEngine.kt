package com.soulseekplus.core.search

import com.soulseekplus.core.model.SearchFilter
import com.soulseekplus.core.model.SearchHit
import com.soulseekplus.core.model.SharedFile
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Hangi filtre alanında sorun var. */
enum class FilterField { INCLUDE, EXCLUDE }

/**
 * Filtre derlenirken bulunan sorun (şimdilik yalnızca geçersiz düzenli ifade).
 * Sorunlu alan süzgeç olarak uygulanmaz (her şey geçer); arayüz alanı kırmızı gösterir.
 * [message] düzenli ifade çözümleyicisinin İngilizce açıklamasıdır (kullanıcıya birincil metin olarak değil, ayrıntı olarak gösterilir).
 */
data class FilterIssue(val field: FilterField, val message: String)

/** [SearchFilterEngine.apply] sonucu: geçen satırlar ve derleme sorunları. */
data class FilterOutcome(val hits: List<SearchHit>, val issues: List<FilterIssue>)

/**
 * Önceden derlenmiş bir [SearchFilter]. Her satır için ucuzdur; tek bir sorgu boyunca yeniden kullanılır.
 * İş parçacığı güvenlidir (değişmez).
 */
class CompiledFilter internal constructor(
    val source: SearchFilter,
    val issues: List<FilterIssue>,
    private val includeMatcher: TextMatcher?,
    private val excludeMatcher: TextMatcher?,
    private val extensionRule: ExtensionRule?,
    private val countryRule: CountryRule?,
) {
    /** Hiçbir koşul uygulanmıyorsa true (her satır geçer). */
    val isPassThrough: Boolean =
        includeMatcher == null && excludeMatcher == null && extensionRule == null && countryRule == null &&
            source.copy(include = "", exclude = "", extensions = "", countries = "", useRegex = false) ==
            SearchFilter(hideLocked = false)

    fun matches(hit: SearchHit): Boolean {
        val f = source
        val file = hit.file
        if (f.hideLocked && hit.locked) return false
        if (f.freeSlotsOnly && !hit.freeSlots) return false
        if (f.maxQueueLength != null && effectiveQueue(hit) > f.maxQueueLength) return false
        if (f.minSpeedBytes != null && hit.averageSpeed < f.minSpeedBytes) return false
        if (f.losslessOnly && !file.isLossless) return false
        if (f.minBitrate != null && !file.isLossless && (file.attributes.bitrate ?: -1) < f.minBitrate) return false
        if (f.minSampleRate != null && (file.attributes.sampleRate ?: -1) < f.minSampleRate) return false
        if (f.minBitDepth != null && (file.attributes.bitDepth ?: -1) < f.minBitDepth) return false
        if (f.minSizeBytes != null && file.size < f.minSizeBytes) return false
        if (f.maxSizeBytes != null && file.size > f.maxSizeBytes) return false
        if (extensionRule != null && !extensionRule.allows(file)) return false
        if (countryRule != null && !countryRule.allows(hit.countryCode)) return false
        if (includeMatcher != null && !includeMatcher.includes(hit)) return false
        if (excludeMatcher != null && excludeMatcher.excludes(hit)) return false
        return true
    }

    private fun effectiveQueue(hit: SearchHit): Long = if (hit.freeSlots) 0L else hit.queueLength

    internal interface TextMatcher {
        /** Satır dahil etme koşulunu sağlıyor mu. */
        fun includes(hit: SearchHit): Boolean

        /** Satır hariç tutma koşuluna takılıyor mu. */
        fun excludes(hit: SearchHit): Boolean
    }

    internal class ExtensionRule(private val included: Set<String>, private val excluded: Set<String>) {
        fun allows(file: SharedFile): Boolean {
            val ext = file.extension
            if (ext in excluded) return false
            return included.isEmpty() || ext in included
        }
    }

    /** Nicotine+ ülke süzgeci: "tr,de" yalnızca bunlar, "!us" bunun dışındakiler. */
    internal class CountryRule(private val codes: List<String>) {
        fun allows(country: String?): Boolean {
            val value = country?.uppercase().orEmpty()
            var allowed = false
            for (code in codes) {
                if (code.startsWith("!")) {
                    if (code.substring(1) == value) return false
                    allowed = true
                } else if (code == value) {
                    allowed = true
                }
            }
            return allowed
        }
    }
}

/**
 * Arama sonucu süzgeci (Nicotine+ süzgeçleri + Seeker çipleri). Saftır, iş parçacığı güvenlidir.
 *
 * Kelime kipi ([SearchFilter.useRegex] kapalı): metin boşluklarla terimlere bölünür, `"tırnaklı ifade"` tek terimdir,
 * `a|b` terim içinde alternatiftir. Her [SearchFilter.include] terimi kullanıcı adında ya da dosya yolunda geçmeli (büyük/küçük
 * harf duyarsız alt dize); [SearchFilter.exclude] terimlerinden biri geçerse satır elenir.
 * Düzenli ifade kipi: ifade yolda aranır (find) ya da kullanıcı adıyla tam eşleşir (Nicotine+ ile aynı).
 */
object SearchFilterEngine {

    /** Düzenli ifade eşleşmesi için üst sınır (karakter erişimi sayısı); aşılırsa eşleşme yok sayılır. */
    private const val REGEX_STEP_BUDGET = 400_000

    private val GENERIC_TYPES: Map<String, Set<String>> = mapOf(
        "audio" to SharedFile.AUDIO_EXTENSIONS,
        "lossless" to SharedFile.LOSSLESS_EXTENSIONS,
        "lossy" to (SharedFile.AUDIO_EXTENSIONS - SharedFile.LOSSLESS_EXTENSIONS),
        "video" to setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "mpg", "mpeg", "m4v", "ogv", "ts", "vob"),
        "image" to setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "heic", "svg"),
        "archive" to setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "cue"),
        "document" to setOf("pdf", "epub", "mobi", "doc", "docx", "txt", "rtf", "odt", "nfo", "m3u", "m3u8", "log"),
    )

    fun compile(filter: SearchFilter): CompiledFilter {
        val issues = ArrayList<FilterIssue>(2)
        val include = compileText(filter.include, filter.useRegex, FilterField.INCLUDE, issues)
        val exclude = compileText(filter.exclude, filter.useRegex, FilterField.EXCLUDE, issues)
        return CompiledFilter(
            source = filter,
            issues = issues,
            includeMatcher = include,
            excludeMatcher = exclude,
            extensionRule = compileExtensions(filter.extensions),
            countryRule = compileCountries(filter.countries),
        )
    }

    /** Yalnızca geçersiz ifade sorunlarını verir (arayüz doğrulaması için). */
    fun validate(filter: SearchFilter): List<FilterIssue> {
        if (!filter.useRegex) return emptyList()
        val issues = ArrayList<FilterIssue>(2)
        compileText(filter.include, true, FilterField.INCLUDE, issues)
        compileText(filter.exclude, true, FilterField.EXCLUDE, issues)
        return issues
    }

    fun matches(hit: SearchHit, filter: SearchFilter): Boolean = compile(filter).matches(hit)

    fun apply(hits: List<SearchHit>, filter: SearchFilter): FilterOutcome {
        val compiled = compile(filter)
        if (compiled.isPassThrough) return FilterOutcome(hits, compiled.issues)
        return FilterOutcome(hits.filter { compiled.matches(it) }, compiled.issues)
    }

    // ------------------------------------------------------------------ metin

    private fun compileText(text: String, regex: Boolean, field: FilterField, issues: MutableList<FilterIssue>): CompiledFilter.TextMatcher? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (regex) {
            try {
                RegexMatcher(Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE))
            } catch (e: PatternSyntaxException) {
                issues += FilterIssue(field, e.description ?: "Invalid regular expression")
                null
            } catch (e: StackOverflowError) {
                issues += FilterIssue(field, "Regular expression too complex")
                null
            }
        } else {
            val terms = splitTerms(trimmed)
            if (terms.isEmpty()) null else WordMatcher(terms)
        }
    }

    /** `foo "bar baz" a|b` → [[foo], [bar baz], [a, b]] (hepsi küçük harf). */
    fun splitTerms(text: String): List<List<String>> {
        val terms = ArrayList<List<String>>()
        val current = StringBuilder()
        var inQuote = false

        fun flush() {
            if (current.isNotEmpty()) {
                val alternatives = current.toString().split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                if (alternatives.isNotEmpty()) terms += alternatives
                current.setLength(0)
            }
        }
        for (ch in text) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> flush()
                else -> current.append(ch)
            }
        }
        flush()
        return terms
    }

    private class WordMatcher(private val terms: List<List<String>>) : CompiledFilter.TextMatcher {
        private fun haystack(hit: SearchHit): String = (hit.username + "\u0001" + hit.file.path).lowercase()

        override fun includes(hit: SearchHit): Boolean {
            val text = haystack(hit)
            return terms.all { alternatives -> alternatives.any { text.contains(it) } }
        }

        override fun excludes(hit: SearchHit): Boolean {
            val text = haystack(hit)
            return terms.any { alternatives -> alternatives.any { text.contains(it) } }
        }
    }

    private class RegexMatcher(private val pattern: Pattern) : CompiledFilter.TextMatcher {
        private fun test(hit: SearchHit): Boolean = try {
            pattern.matcher(BudgetedText(hit.file.path)).find() || pattern.matcher(BudgetedText(hit.username)).matches()
        } catch (e: RegexBudgetExceeded) {
            false
        } catch (e: StackOverflowError) {
            false
        }

        override fun includes(hit: SearchHit): Boolean = test(hit)
        override fun excludes(hit: SearchHit): Boolean = test(hit)
    }

    private class RegexBudgetExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Felaket geri izleme (catastrophic backtracking) durumunda eşleşmeyi keser. */
    private class BudgetedText(private val inner: String) : CharSequence {
        private var steps = 0
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if (++steps > REGEX_STEP_BUDGET) throw RegexBudgetExceeded()
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = inner.subSequence(startIndex, endIndex)
        override fun toString(): String = inner
    }

    // ------------------------------------------------------------------ uzantı / ülke

    private fun splitList(text: String): List<String> =
        text.split(',', ';', '|', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun compileExtensions(text: String): CompiledFilter.ExtensionRule? {
        val items = splitList(text.lowercase())
        if (items.isEmpty()) return null
        val included = HashSet<String>()
        val excluded = HashSet<String>()
        for (raw in items) {
            val negative = raw.startsWith("!")
            val name = raw.removePrefix("!").removePrefix(".").removePrefix("*.").removePrefix("*")
            if (name.isEmpty()) continue
            val expanded = GENERIC_TYPES[name] ?: setOf(name)
            if (negative) excluded += expanded else included += expanded
        }
        if (included.isEmpty() && excluded.isEmpty()) return null
        return CompiledFilter.ExtensionRule(included, excluded)
    }

    private fun compileCountries(text: String): CompiledFilter.CountryRule? {
        val items = splitList(text.uppercase())
        if (items.isEmpty()) return null
        return CompiledFilter.CountryRule(items)
    }
}
