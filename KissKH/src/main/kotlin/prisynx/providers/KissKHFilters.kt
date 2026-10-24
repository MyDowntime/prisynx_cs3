package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

/**
 * Adult-content filter ported from the KissKH reference.
 *
 * KissKH's list API does not expose an 18+ flag. "Uncensored" labels and a
 * title keyword blocklist catch most entries; Japanese MOVIES carrying the
 * RAW label are adult pink films (the same label is only a subtitle-status
 * marker on series, so it is intentionally scoped to type=2/country=3).
 */
internal object KissKHFilters {
    private val ADULT_LABELS = listOf("uncensored")

    private val ADULT_KEYWORDS = listOf(
        "uncensored", "kamasutra", "creampie", "squirt", "porn", "porno", "pornograph",
        "ntr", "nude", "naked", "rape", "slave", "gangbang", "milf", "jav", "bdsm",
        "kink", "orgy", "orgasm", "voyeur", "peeping", "hentai", "vivamax", "vmx",
        "viva hot babes", "play angels", "strip mahjong", "sexy breasts", "sexual desire",
        "schoolmistress", "female loan shark", "be my slave", "sex hub", "sex and zen",
        "sex in public", "sex plate", "sex games", "sex on phone", "sex trip", "sex drifter",
        "sex doll", "sex slave", "sex drive", "high school on sex", "on sex", "sex diet",
        "sex stories", "sex diary", "erotic", "erotica", "eros", "seduction", "seduce",
        "seducing", "affair", "affairs", "affaire", "unaffair", "immoral", "adult world",
        "adult movie", "adult film", "adult video", "adult streamer", "erotic tutoring",
        "erotic sister"
    )

    fun isAdult(item: JsonNode?, type: Int, country: Int): Boolean {
        if (!KissKHSettings.adultFilter || item == null) return false

        val title = item.path("title").asText("").lowercase(Locale.ROOT)
        for (keyword in ADULT_KEYWORDS) {
            var at = title.indexOf(keyword)
            while (at >= 0) {
                val previous = if (at > 0) title[at - 1] else ' '
                if (previous !in 'a'..'z' && previous !in '0'..'9') return true
                at = title.indexOf(keyword, at + 1)
            }
        }

        val label = item.path("label").asText("").lowercase(Locale.ROOT)
        if (ADULT_LABELS.any { label.contains(it) }) return true

        val itemType = item.path("type").asInt(type)
        val itemCountry = item.path("country").asInt(country)
        return itemType == 2 && itemCountry == 3 && label.contains("raw")
    }
}
