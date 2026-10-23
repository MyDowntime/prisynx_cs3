package prisynx.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.FormBody
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.decodeHtmlEntities
import prisynx.providers.core.enc
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.normalizeTitle
import prisynx.providers.core.qualityLabel
import prisynx.providers.core.resolveUprot

/**
 * CB01: live-domain redirect, card search, then MaxStream (uprot) and
 * MixDrop/StayOnline sources. Folder pages are not followed.
 */
class Cb01Provider : TmdbProvider("CB01", "https://cb01official.uno") {

    private data class Section(val url: String, val quality: Int)

    private data class Match(val url: String, val score: Int)

    @Volatile
    private var cachedDomain: String? = null

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val domain = activeDomain() ?: return false
        val isMovie = !ref.isTv
        if (meta.title.isBlank() && meta.originalTitle.isBlank()) return false

        val page = search(domain, meta, isMovie) ?: return false
        val html = get(page) ?: return false
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1

        val target = if (isMovie) {
            html
        } else {
            findEpisodeBlock(html, season, episode) ?: return false
        }

        val sections = sections(target)
        if (sections.isEmpty()) return false

        var emitted = 0
        val seen = HashSet<String>()
        for (section in sections.sortedByDescending { it.quality }) {
            if (emitted >= 3) break
            if (!seen.add(section.url)) continue
            if (section.url.contains("uprot.net/", ignoreCase = true)) {
                val stream = resolveUprot(section.url) ?: continue
                val quality = stream.quality.takeIf { it > 0 } ?: section.quality
                emit(
                    callback = callback,
                    url = stream.url,
                    label = "CB01 ${qualityLabel(quality)} MaxStream",
                    quality = quality,
                    type = ExtractorLinkType.M3U8,
                    referer = "https://maxstream.video/",
                    headers = stream.headers
                )
                emitted++
            } else if (section.url.contains("stayonline.pro", ignoreCase = true)) {
                val actual = unwrapStayonline(section.url) ?: continue
                if (actual.contains("uprot.net/", ignoreCase = true)) {
                    val stream = resolveUprot(actual) ?: continue
                    val quality = stream.quality.takeIf { it > 0 } ?: section.quality
                    emit(
                        callback = callback,
                        url = stream.url,
                        label = "CB01 ${qualityLabel(quality)} MaxStream",
                        quality = quality,
                        type = ExtractorLinkType.M3U8,
                        referer = "https://maxstream.video/",
                        headers = stream.headers
                    )
                    emitted++
                } else if (actual.contains("mixdrop", ignoreCase = true) ||
                    Regex("m[i1]xdrop", RegexOption.IGNORE_CASE).containsMatchIn(actual)
                ) {
                    val host = prisynx.providers.core.originOf(actual)
                    if (emitPlayer(actual, "$host/", subtitleCallback, callback)) emitted++
                }
            } else if (section.url.contains("mixdrop", ignoreCase = true)) {
                val host = prisynx.providers.core.originOf(section.url)
                if (emitPlayer(section.url, "$host/", subtitleCallback, callback)) emitted++
            }
        }
        return emitted > 0
    }

    // ------------------------------------------------------------ domain

    private suspend fun activeDomain(): String? {
        cachedDomain?.let { return it }
        for (candidate in listOf("https://cb01official.uno/", "https://cb01uno.wiki/")) {
            try {
                val response = net.get(
                    candidate,
                    headers = mapOf("User-Agent" to UA_PC, "Accept" to "text/html"),
                    timeout = 20
                )
                val finalUrl = response.url.ifBlank { candidate }
                val origin = prisynx.providers.core.originOf(finalUrl)
                if (origin.isNotBlank() && response.code in 200..399) {
                    cachedDomain = origin
                    return origin
                }
            } catch (e: Exception) {
                // try next candidate
            }
        }
        return null
    }

    // ------------------------------------------------------------ search

    private suspend fun get(url: String): String? = try {
        val referer = prisynx.providers.core.originOf(url) + "/"
        net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to referer
            ),
            timeout = 25
        ).text
    } catch (e: Exception) {
        null
    }

    private suspend fun search(domain: String, meta: MediaMeta, isMovie: Boolean): String? {
        val path = if (isMovie) "" else "serietv/"
        val titles = listOf(meta.title, meta.originalTitle)
            .filter { it.isNotBlank() }
            .distinct()
        val queries = mutableListOf<String>()
        for (title in titles) {
            if (title !in queries) queries.add(title)
            Regex("^([^:\\-(]+)").find(title)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotBlank() && it !in queries) queries.add(it)
            }
        }
        val year = meta.year
        for (query in queries) {
            val html = get("$domain/$path?s=${enc(query)}") ?: continue
            val found = findBest(html, titles, year)
            if (found != null) return found
        }
        return null
    }

    private fun findBest(html: String, refs: List<String>, year: Int?): String? {
        val results = mutableListOf<Pair<String, String>>()
        Regex(
            "<div[^>]+class=\"[^\"]*card-content[^\"]*\"[\\s\\S]*?<h3[^>]+class=\"[^\"]*card-title[^\"]*\"[\\s\\S]*?<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { results.add(it.groupValues[1] to it.groupValues[2]) }
        if (results.isEmpty()) {
            Regex(
                "<article[^>]*>[\\s\\S]*?<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</article>",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { results.add(it.groupValues[1] to it.groupValues[2]) }
        }
        var best: Match? = null
        for ((url, text) in results) {
            val score = titleScore(refs, text, year)
            if (score > 0 && (best == null || score > best!!.score)) best = Match(url, score)
        }
        return best?.url
    }

    private fun titleWords(text: String): List<String> {
        val cleaned = decodeHtmlEntities(text)
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\((?:19|20)\\d\\d\\)"), " ")
            .replace(Regex("(?:\\u2013|-)\\s*\\d+\\s*(?:\\u00d7|x)\\s*\\d+[\\s\\S]*$", RegexOption.IGNORE_CASE), " ")
        return normalizeTitle(cleaned)
            .split(" ")
            .filter { it.isNotBlank() && it !in setOf("hd", "sd", "3d", "4k", "uhd", "ita", "sub", "streaming", "serie", "tv") }
    }

    private fun titleScore(refs: List<String>, text: String, year: Int?): Int {
        val found = Regex("\\(((?:19|20)\\d\\d)\\)").find(decodeHtmlEntities(text))
        val foundYear = found?.groupValues?.get(1)?.toIntOrNull()
        if (year != null && foundYear != null && kotlin.math.abs(foundYear - year) > 1) return 0
        val sameYear = year != null && foundYear == year
        val words = titleWords(text)
        var best = 0
        for (ref in refs) {
            val want = titleWords(ref)
            if (want.isEmpty()) continue
            val missing = want.count { it !in words }
            val extra = words.size - (want.size - missing)
            if (missing > if (sameYear && want.size >= 3) 1 else 0) continue
            if (want.size == 1 && extra > 0) continue
            val score = 100 - 20 * missing - 5 * extra + if (sameYear) 10 else 0
            if (score > best) best = score
        }
        return best
    }

    // ------------------------------------------------------------ page extraction

    private fun clean(html: String): String = html
        .replace(Regex("<script\\b[^<]*(?:(?!</script>)<[^<]*)*</script>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<style\\b[^<]*(?:(?!</style>)<[^<]*)*</style>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<!--[\\s\\S]*?-->"), "")

    private fun findEpisodeBlock(html: String, season: Int, episode: Int): String? {
        val body = clean(html)
        val ep2 = if (episode < 10) "0$episode" else episode.toString()
        val labels = listOf(
            "(?<!\\d)(?:$season|0$season)\\s*(?:x|\\u00d7|&#215;)\\s*0?$episode(?!\\d)",
            "(?<!\\d)S0?${season}E$ep2(?!\\d)",
            "STAGIONE\\s+$season\\s*-\\s*EPISODIO\\s+$episode\\b"
        )
        for (pattern in labels) {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(body) ?: continue
            val start = match.range.first
            val next = Regex(
                "S\\d+E\\d+|\\d+\\s*(?:x|\\u00d7|&#215;)\\s*\\d+",
                RegexOption.IGNORE_CASE
            ).find(body, start + match.value.length)
            val end = next?.range?.first?.takeIf { it > start } ?: body.length
            val block = body.substring(start, end)
            if (block.contains("stayonline.pro", ignoreCase = true) ||
                block.contains("uprot.net/", ignoreCase = true)
            ) return block
        }
        return null
    }

    private fun sections(target: String): List<Section> {
        val out = mutableListOf<Section>()
        var quality = 720
        val tables = Regex("<table[^>]*>[\\s\\S]*?</table>", RegexOption.IGNORE_CASE)
            .findAll(target).map { it.value }.toList()
        for (table in tables) {
            val text = Regex("<[^>]+>").replace(table, " ").lowercase().trim()
            if (text.contains("streaming")) {
                quality = if (text.contains("hd")) 1080 else 720
                continue
            }
            if (table.contains("tableinside", ignoreCase = true)) {
                Regex(
                    "<a[^>]+href=[\"']([^\"']*(?:stayonline\\.pro|uprot\\.net/msf/)[^\"']*)",
                    RegexOption.IGNORE_CASE
                ).find(table)?.groupValues?.get(1)?.let { out.add(Section(it, quality)) }
            }
        }
        if (out.isEmpty()) {
            val upper = target.uppercase()
            val defaultQuality = if (upper.contains("STREAMING HD:") ||
                upper.contains("- ITA - HD") ||
                upper.contains("- SUB - HD")
            ) 1080 else 720
            Regex(
                "href=[\"'](https?://[^\"']*(?:stayonline\\.pro|uprot\\.net/msf)[^\"']*)",
                RegexOption.IGNORE_CASE
            ).findAll(target).forEach { m ->
                val url = m.groupValues[1]
                if (out.none { it.url == url }) out.add(Section(url, defaultQuality))
            }
        }
        return out
    }

    private suspend fun unwrapStayonline(url: String): String? {
        val id = Regex("/(?:[el])/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: return null
        val data = try {
            json.readTree(
                net.post(
                    "https://stayonline.pro/ajax/linkEmbedView.php",
                    headers = mapOf(
                        "User-Agent" to UA_PC,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Origin" to "https://stayonline.pro",
                        "Referer" to "https://stayonline.pro/",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    requestBody = FormBody.Builder()
                        .add("id", id)
                        .add("ref", "")
                        .build(),
                    timeout = 20
                ).text
            )
        } catch (e: Exception) {
            return null
        }
        return data.path("data").path("value").asText("").ifBlank {
            data.path("value").asText("")
        }.trim().ifBlank { null }
    }
}
