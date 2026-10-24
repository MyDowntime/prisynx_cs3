package prisynx.providers

import java.util.Locale

internal object KissKHText {
    private val ITALIAN_LABELS = setOf("it", "ita", "italian", "italiano", "italy")
    private val ENGLISH_LABELS = setOf("en", "eng", "english")
    private val YEAR = Regex("(19|20)\\d{2}")

    fun cleanTitle(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\w\\s\\u3131-\\uD79D\\u4E00-\\u9FFF\\u3040-\\u30FF]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun titleSimilarity(first: String?, second: String?): Double {
        val left = cleanTitle(first)
        val right = cleanTitle(second)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) return 0.85

        val leftWords = left.split(' ').filter { it.length > 2 }.toSet()
        val rightWords = right.split(' ').filter { it.length > 2 }.toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0.0
        val common = leftWords.count { it in rightWords }
        return common.toDouble() / maxOf(leftWords.size, rightWords.size)
    }

    fun yearOf(value: String?): Int? =
        YEAR.find(value.orEmpty())?.value?.toIntOrNull()

    /**
     * KissKH lists every season as its own entry ("Squid Game Season 2"), so the
     * TMDB season number must be used to select the right entry.
     */
    fun seasonOfTitle(value: String?): Int {
        val match = Regex("\\bseason\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
            .find(value.orEmpty()) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    fun detectSubtitleLang(label: String?, url: String?): String? {
        val normalized = label.orEmpty().lowercase(Locale.ROOT).trim()
        if (normalized in ITALIAN_LABELS) return "it"
        if (normalized in ENGLISH_LABELS) return "en"
        if (normalized.contains("italian") || normalized.contains("italiano")) return "it"
        if (normalized.contains("english") || normalized.contains("inglés")) return "en"

        val value = url.orEmpty().lowercase(Locale.ROOT)
        if (Regex("\\.it\\.(srt|txt1|txt)(\\?.*)?$").containsMatchIn(value) ||
            value.contains("/it/") || value.contains("italian")
        ) {
            return "it"
        }
        if (Regex("\\.en\\.(srt|txt1|txt)(\\?.*)?$").containsMatchIn(value) ||
            value.contains("/en/") || value.contains("english")
        ) {
            return "en"
        }
        return null
    }

    /** Only the target languages accepted by the DeepL API are allowed. */
    private val DEEPL_BASES = setOf(
        "ar", "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hu",
        "id", "it", "ja", "ko", "lt", "lv", "nb", "nl", "pl", "pt", "ro", "ru",
        "sk", "sl", "sv", "tr", "uk", "zh"
    )

    private val DEEPL_NAMES = mapOf(
        "italiano" to "it", "italian" to "it",
        "inglese" to "en", "english" to "en",
        "spagnolo" to "es", "spanish" to "es",
        "francese" to "fr", "french" to "fr",
        "tedesco" to "de", "german" to "de",
        "portoghese" to "pt", "portuguese" to "pt",
        "giapponese" to "ja", "japanese" to "ja",
        "coreano" to "ko", "korean" to "ko",
        "cinese" to "zh-hans", "chinese" to "zh-hans"
    )

    /**
     * Normalizes what the user types in the settings field: accepts ISO codes,
     * region variants ("it-IT", "pt_BR") and language names ("italiano").
     */
    fun sanitizeTargetLang(value: String?): String? {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-')
        if (normalized.isEmpty()) return null
        DEEPL_NAMES[normalized]?.let { return it }

        val match = Regex("^([a-z]{2,3})(?:-([a-z]{2}|[a-z]{4}))?$").find(normalized) ?: return null
        val base = match.groupValues[1]
        if (base !in DEEPL_BASES) return null
        val region = match.groupValues[2]

        return when (base) {
            // DeepL needs a script variant for Chinese.
            "zh" -> when (region) {
                "hant", "tw", "hk" -> "zh-hant"
                else -> "zh-hans"
            }
            // English only accepts the US/GB variants.
            "en" -> when (region) {
                "us" -> "en-us"
                "gb" -> "en-gb"
                else -> "en"
            }
            "pt" -> when (region) {
                "br" -> "pt-br"
                "pt" -> "pt-pt"
                else -> "pt"
            }
            else -> base
        }
    }

    /**
     * Descriptive subtitle label that CloudStream can still map to an IETF tag
     * (e.g. "it" -> "Italian (DeepL)").
     */
    fun deeplLabel(target: String): String {
        val code = sanitizeTargetLang(target) ?: "it"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return if (name.isBlank() || name.equals(code, ignoreCase = true)) {
            "$code (DeepL)"
        } else {
            "$name (DeepL)"
        }
    }
}
