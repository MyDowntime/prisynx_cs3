package prisynx.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.domainOf
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.decodeHtmlEntities
import prisynx.providers.core.enc
import prisynx.providers.core.net
import prisynx.providers.core.normalizeTitle
import prisynx.providers.core.qualityLabel
import prisynx.providers.core.resolveUprot

/**
 * Eurostreaming (ES Nuvio): TV series only. Resolves the Italian MaxStream
 * source through uprot's captcha. clicka.cc hosts need their OCR captcha and
 * are intentionally skipped; the MaxStream link covers most episodes.
 */
class EurostreamingProvider : TmdbProvider("Eurostreaming", "https://eurostreamings.live") {

    override val supportsMovies = false
    override val supportsTv = true

    @Volatile
    private var cachedDomain: String? = null

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!ref.isTv) return false
        val domain = activeDomain() ?: return false
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        val page = search(domain, meta) ?: return false
        val html = get(page) ?: return false
        val block = episodeBlock(html, season, episode) ?: return false

        val links = mutableListOf<String>()
        Regex("https?://uprot\\.net/ms(?:fi?|ei)/[A-Za-z0-9+/=_.-]+", RegexOption.IGNORE_CASE)
            .findAll(block)
            .forEach { if (it.value !in links) links.add(it.value) }
        if (links.isEmpty()) return false

        var emitted = 0
        for (link in links.take(2)) {
            val stream = resolveUprot(link) ?: continue
            val quality = stream.quality.takeIf { it > 0 } ?: 1080
            emit(
                callback = callback,
                url = stream.url,
                label = "Eurostreaming S${season}E$episode ${qualityLabel(quality)} ITA",
                quality = quality,
                type = ExtractorLinkType.M3U8,
                referer = "https://maxstream.video/",
                headers = stream.headers
            )
            emitted++
        }
        return emitted > 0
    }

    private suspend fun activeDomain(): String? {
        cachedDomain?.let { return it }
        val dynamic = try {
            prisynx.providers.core.fetchDomains(prisynx.providers.core.GUARDOSERIE_DOMAINS_URL)
                ?.let { node ->
                    val value = node.path("eurostreaming").asText("")
                    if (value.isBlank()) null else node.domainOf("eurostreaming", "")
                }
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        val candidates = listOfNotNull(dynamic?.let { "https://$it/" }) +
            listOf("https://eurostreamings.tattoo/", "https://eurostreamings.live/")
        for (candidate in candidates.distinct()) {
            try {
                val response = net.get(
                    candidate,
                    headers = mapOf("User-Agent" to UA_PC, "Accept" to "text/html"),
                    timeout = 20
                )
                val origin = prisynx.providers.core.originOf(response.url.ifBlank { candidate })
                if (origin.isNotBlank() && response.code in 200..399) {
                    cachedDomain = origin
                    return origin
                }
            } catch (e: Exception) {
                // try next
            }
        }
        return null
    }

    private suspend fun get(url: String): String? = try {
        net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to (prisynx.providers.core.originOf(url) + "/")
            ),
            timeout = 25
        ).text
    } catch (e: Exception) {
        null
    }

    private suspend fun search(domain: String, meta: MediaMeta): String? {
        val titles = listOf(meta.title, meta.originalTitle)
            .filter { it.isNotBlank() }
            .distinct()
        for (title in titles) {
            val html = get("$domain/?s=${enc(title)}") ?: continue
            val found = bestMatch(html, titles)
            if (found != null) return found
        }
        return null
    }

    private fun bestMatch(html: String, refs: List<String>): String? {
        val results = mutableListOf<Pair<String, String>>()
        Regex(
            "<h\\d[^>]*>\\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*(?:title=[\"']([^\"']*)[\"'])?[^>]*>([\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            if (href.contains("/tag/") || href.contains("/category/")) return@forEach
            val text = m.groupValues[2].ifBlank { m.groupValues[3] }
            results.add(href to decodeHtmlEntities(text))
        }
        var best: Pair<String, Int>? = null
        val wanted = refs.map { normalizeTitle(it) }.filter { it.isNotBlank() }
        for ((url, text) in results) {
            val words = normalizeTitle(text).split(" ").filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            var score = 0
            for (ref in wanted) {
                val target = ref.split(" ").filter { it.isNotBlank() }
                if (target.isEmpty()) continue
                if (target.all { it in words }) {
                    val value = 100 - kotlin.math.abs(words.size - target.size)
                    if (value > score) score = value
                }
            }
            if (score > 50 && (best == null || score > best!!.second)) best = url to score
        }
        return best?.first
    }

    private fun episodeBlock(html: String, season: Int, episode: Int): String? {
        val body = html
            .replace(Regex("<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
        val ep2 = if (episode < 10) "0$episode" else episode.toString()
        val patterns = listOf(
            "(?<!\\d)(?:$season|0$season)\\s*(?:&#215;|\\u00d7|x|X)\\s*0?$episode(?!\\d)[\\s\\S]{0,1800}",
            "(?<!\\d)S0?${season}E$ep2(?!\\d)[\\s\\S]{0,1800}"
        )
        for (pattern in patterns) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(body) ?: continue
            var block = match.value
            block.substringBefore("<br").let { if (it.length > 30) block = it }
            if (block.contains("uprot.net/", ignoreCase = true)) return block
        }
        return null
    }
}
