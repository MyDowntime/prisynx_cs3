package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.TmdbRow
import prisynx.providers.core.UA_MOBILE
import prisynx.providers.core.absUrl
import prisynx.providers.core.bestHeight
import prisynx.providers.core.decodeB64
import prisynx.providers.core.enc
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromHeight
import prisynx.providers.core.unpackJs

/**
 * Toonitalia: the site is behind Cloudflare, but a small Stremio-Italia API
 * exposes the Toonitalia-indexed catalogue and the player URLs. VOE/Uqload are
 * then resolved on-device (links are IP-bound).
 */
class ToonitaliaProvider : TmdbProvider("Toonitalia", "https://toonitalia.xyz") {
    private val api = "https://ti.stremio-italia.eu"

    private data class TiItem(
        val title: String,
        val year: Int?,
        val movie: Boolean
    )

    private data class TiCandidate(
        val node: JsonNode,
        val isTv: Boolean,
        val title: String,
        val year: Int?,
        val normalized: String
    )

    /**
     * Toonitalia's own catalogue is exposed by the Stremio-Italia service only
     * through /search and /streams; there is no public home/catalog endpoint.
     * The home page therefore uses animation-focused TMDB rows, while search
     * goes through Toonitalia's native index and maps each hit to its TMDB
     * entry for posters/episodes.
     */
    override fun homeRows(): List<TmdbRow> = listOf(
        TmdbRow(
            "Toonitalia \u2022 Serie animate",
            "discover/tv",
            extra = "with_genres=16&sort_by=popularity.desc&vote_count.gte=5",
            forcedTv = true
        ),
        TmdbRow(
            "Toonitalia \u2022 Film d'animazione",
            "discover/movie",
            extra = "with_genres=16&sort_by=popularity.desc&vote_count.gte=5",
            forcedTv = false
        ),
        TmdbRow(
            "Toonitalia \u2022 Animazione italiana",
            "discover/tv",
            extra = "with_genres=16&with_origin_country=IT&sort_by=popularity.desc",
            forcedTv = true
        ),
        TmdbRow(
            "Toonitalia \u2022 Classici",
            "discover/movie",
            extra = "with_genres=16&sort_by=vote_count.desc",
            forcedTv = false
        )
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = super.getMainPage(page, request)
        // Keep the provider usable if the TMDB discovery rows are unavailable.
        if (page == 1 && response.items.none { it.list.isNotEmpty() }) {
            return newHomePageResponse(
                listOfNotNull(row("Tendenze", "trending/all/week")),
                hasNext = false
            )
        }
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        // Native Toonitalia search. The API returns its internal id/title/year
        // but no poster or TMDB id, so find the matching TMDB node for metadata.
        val tiItems = fetchJson(
            "$api/search?q=${enc(query)}",
            headers = mapOf("Accept" to "application/json"),
            timeout = 15
        )?.path("results")?.mapNotNull { node ->
            val title = node.path("title").asText("").trim()
            if (title.isBlank()) null else TiItem(
                title = title,
                year = node.path("year").asInt(0).takeIf { it > 0 },
                movie = node.path("movie").asBoolean(false)
            )
        }.orEmpty()

        if (tiItems.isEmpty()) return super.search(query)

        val globalCandidates = tmdbCandidates(query)
        val cards = coroutineScope {
            tiItems.map { item ->
                async {
                    val wanted = normalizeTitle(item.title)
                    fun bestIn(list: List<TiCandidate>): TiCandidate? =
                        list.mapNotNull { candidate ->
                            val score = matchScore(item, wanted, candidate)
                            if (score >= 60) candidate to score else null
                        }.maxByOrNull { it.second }?.first

                    var match = bestIn(globalCandidates)
                    if (match == null) {
                        // Series usually match the generic query, but films with
                        // long Italian titles often need an exact lookup.
                        match = bestIn(tmdbCandidates(item.title))
                    }
                    match?.let { cardOf(it.node, it.isTv) }
                }
            }.awaitAll().filterNotNull()
        }.distinctBy { it.url }

        return cards.ifEmpty { super.search(query) }
    }

    private suspend fun tmdbCandidates(query: String): List<TiCandidate> {
        val root = fetchJson(
            tmdbUrl("search/multi", "query=${enc(query)}&include_adult=false&page=1")
        ) ?: return emptyList()
        return root.path("results").mapNotNull { node ->
            val type = node.path("media_type").asText("")
            val isTv = when {
                type == "tv" && supportsTv -> true
                type == "movie" && supportsMovies -> false
                else -> return@mapNotNull null
            }
            val title = if (isTv) node.path("name").asText("") else node.path("title").asText("")
            val date =
                if (isTv) node.path("first_air_date").asText("")
                else node.path("release_date").asText("")
            if (title.isBlank()) return@mapNotNull null
            TiCandidate(
                node = node,
                isTv = isTv,
                title = title,
                year = date.take(4).toIntOrNull(),
                normalized = normalizeTitle(title)
            )
        }
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            // Toonitalia prefixes films with "Movie 01", "Film 02", etc. and
            // marks the TV entry as "Serie TV"; those labels are not in TMDB.
            .replace(Regex("\\b(movie|film)\\s*\\d+\\b"), " ")
            .replace(Regex("\\bserie\\s+tv\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun titleScore(wanted: String, candidate: String): Int {
        if (wanted.isBlank() || candidate.isBlank()) return 0
        if (wanted == candidate) return 100
        if (!wanted.contains(candidate) && !candidate.contains(wanted)) return 0
        val shorter = minOf(wanted.length, candidate.length).toDouble()
        val longer = maxOf(wanted.length, candidate.length).coerceAtLeast(1).toDouble()
        return if (shorter / longer >= 0.75) 45 else 25
    }

    private fun matchScore(item: TiItem, wanted: String, candidate: TiCandidate): Int {
        var score = titleScore(wanted, candidate.normalized)
        if (score == 0) return 0

        // Toonitalia's "movie" flag is not always reliable (some films are
        // marked as series), so also look at the title before trusting it.
        val wantsMovie = item.movie ||
            Regex("\\b(film|movie)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title)

        if (item.year != null && candidate.year != null) {
            val diff = kotlin.math.abs(item.year - candidate.year)
            score += when {
                diff == 0 -> 35
                diff <= 1 -> 18
                diff <= 3 -> 5
                else -> -25
            }
        }
        score += if (candidate.isTv == !wantsMovie) 10 else -60
        return score
    }

    /**
     * The /search endpoint exposes the exact Toonitalia title, including labels
     * such as "Movie 01" that are absent from TMDB. /streams only matches those
     * original titles, so playback uses this lookup as a fallback.
     */
    private suspend fun findTiItem(title: String, year: Int?): TiItem? {
        if (title.isBlank()) return null
        val wanted = normalizeTitle(title)
        val root = fetchJson(
            "$api/search?q=${enc(title)}",
            headers = mapOf("Accept" to "application/json"),
            timeout = 15
        ) ?: return null
        return root.path("results").mapNotNull { node ->
            val found = node.path("title").asText("").trim()
            var score = titleScore(wanted, normalizeTitle(found))
            if (score == 0) return@mapNotNull null
            val foundYear = node.path("year").asInt(0).takeIf { it > 0 }
            if (year != null && foundYear != null) {
                val diff = kotlin.math.abs(year - foundYear)
                score += when {
                    diff == 0 -> 35
                    diff <= 1 -> 18
                    diff <= 3 -> 5
                    else -> -25
                }
            }
            if (score < 60) null else TiItem(
                title = found,
                year = foundYear,
                movie = node.path("movie").asBoolean(false)
            ) to score
        }.maxByOrNull { it.second }?.first
    }

    private suspend fun lookupStreams(query: String): JsonNode? = try {
        json.readTree(
            net.get(
                "$api/streams?$query",
                headers = mapOf("Accept" to "application/json"),
                timeout = 20
            ).text
        )
    } catch (e: Exception) {
        null
    }

    private fun streamQuery(
        type: String,
        title: String,
        original: String,
        year: Int,
        season: Int,
        episode: Int,
        absolute: Int
    ): String {
        var query = "type=$type&title=${enc(title)}" +
            "&orig=${enc(original.ifBlank { title })}&year=$year"
        if (type == "series") query += "&s=$season&e=$episode&abs=$absolute"
        return query
    }

    private fun hasMatch(data: JsonNode?): Boolean {
        val match = data?.path("match") ?: return false
        return !match.isMissingNode && !match.isNull
    }

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (meta.title.isBlank()) return false
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        var absolute = episode
        if (ref.isTv) {
            val details = fetchDetails(ref)
            details?.path("seasons")?.forEach { node ->
                val number = node.path("season_number").asInt(0)
                if (number in 1 until season) {
                    absolute += node.path("episode_count").asInt(0)
                }
            }
        }

        var data = lookupStreams(
            streamQuery(
                type = if (ref.isTv) "series" else "movie",
                title = meta.title,
                original = meta.originalTitle,
                year = meta.year ?: 0,
                season = season,
                episode = episode,
                absolute = absolute
            )
        )

        if (!hasMatch(data)) {
            val exact = findTiItem(meta.title, meta.year)
                ?: meta.originalTitle.takeIf { it.isNotBlank() }
                    ?.let { findTiItem(it, meta.year) }
            if (exact != null) {
                data = lookupStreams(
                    streamQuery(
                        type = if (ref.isTv) "series" else "movie",
                        title = exact.title,
                        original = exact.title,
                        year = exact.year ?: meta.year ?: 0,
                        season = season,
                        episode = episode,
                        absolute = absolute
                    )
                )
                // Some Toonitalia films are tagged as series in its index and
                // are only reachable through the series lookup.
                if (!hasMatch(data) && !ref.isTv) {
                    data = lookupStreams(
                        streamQuery(
                            type = "series",
                            title = exact.title,
                            original = exact.title,
                            year = exact.year ?: meta.year ?: 0,
                            season = 1,
                            episode = 1,
                            absolute = 1
                        )
                    )
                }
            }
        }

        val root = data?.takeIf { hasMatch(it) } ?: return false
        val players = root.path("players")
        var emitted = false
        val seen = HashSet<String>()
        for (player in players) {
            val url = player.path("url").asText("")
            if (url.isBlank()) continue
            if (Regex("uqload", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                emitted = extractUqload(url, "$mainUrl/", callback) || emitted
            } else if (Regex("/e/[A-Za-z0-9]+").containsMatchIn(url)) {
                emitted = extractVoe(url, callback) || emitted
            }
        }
        return emitted
    }

    private suspend fun extractUqload(
        playerUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = Regex("^(?:https?:)?//([^/]+)").find(playerUrl)?.groupValues?.get(1) ?: return false
        val code = Regex("/(?:e|embed-)/?([A-Za-z0-9]+)").find(playerUrl)?.groupValues?.get(1) ?: return false
        val origin = "https://$host"
        val text = try {
            net.post(
                "$origin/dl",
                headers = mapOf(
                    "User-Agent" to UA_MOBILE,
                    "Referer" to "$origin/e/$code",
                    "Origin" to origin,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                requestBody = FormBody.Builder()
                    .add("op", "embed")
                    .add("file_code", code)
                    .add("auto", "1")
                    .add("referer", pageUrl)
                    .build(),
                timeout = 20
            ).text
        } catch (e: Exception) {
            return false
        }

        var src: String? = null
        val packed = Regex("\\}\\(\\s*'([\\s\\S]*?)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([\\s\\S]*?)'\\.split\\('\\|'\\)")
            .find(text)
        if (packed != null) {
            val unpacked = unpackJs(
                packed.groupValues[1],
                packed.groupValues[2].toIntOrNull() ?: 62,
                packed.groupValues[3].toIntOrNull() ?: 0,
                packed.groupValues[4].split('|')
            )
            src = Regex("file\\s*:\\s*[\"']([^\"']+)").find(unpacked)?.groupValues?.get(1)
        }
        if (src.isNullOrBlank()) {
            src = Regex("(?:sources|file|src)\\s*[:=]\\s*\\[?\\s*\\{?\\s*(?:file\\s*:\\s*)?[\"']([^\"']+(?:m3u8|mp4)[^\"']*)")
                .find(text)?.groupValues?.get(1)
        }
        if (src.isNullOrBlank()) return false
        if (src.startsWith("//")) src = "https:$src"
        if (src.contains("/static/")) return false
        val headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$origin/", "Origin" to origin)
        return checkAndEmit(src, headers, "Uqload", callback)
    }

    private suspend fun extractVoe(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var pageUrl = playerUrl
        var html = try {
            net.get(playerUrl, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$mainUrl/"), timeout = 20).text
        } catch (e: Exception) {
            return false
        }
        val redirect = Regex("window\\.location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]").find(html)
            ?.groupValues?.get(1)
        if (redirect != null && !html.contains("application/json")) {
            pageUrl = absUrl(redirect, pageUrl)
            html = try {
                net.get(pageUrl, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to playerUrl), timeout = 20).text
            } catch (e: Exception) {
                return false
            }
        }

        val scriptRegex = Regex(
            "<script[^>]*type=[\"']application/json[\"'][^>]*>([\\s\\S]*?)</script>",
            RegexOption.IGNORE_CASE
        )
        var decoded: JsonNode? = null
        for (script in scriptRegex.findAll(html)) {
            val raw = script.groupValues[1].trim()
            val array = runCatching { json.readTree(raw) }.getOrNull() ?: continue
            if (array.isArray && array.size() > 0 && array[0].isTextual) {
                decoded = voeDecode(array[0].asText(""))
                if (decoded != null) break
            }
        }
        val data = decoded ?: return false
        var src = data.path("source").asText("")
            .ifBlank { data.path("direct_access_url").asText("") }
            .ifBlank { data.path("file").asText("") }
        if (src.isBlank()) return false
        src = absUrl(src, pageUrl)
        val origin = Regex("^(https?://[^/]+)").find(pageUrl)?.groupValues?.get(1) ?: mainUrl
        val headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$origin/", "Origin" to origin)
        return checkAndEmit(src, headers, "VOE", callback)
    }

    private suspend fun checkAndEmit(
        url: String,
        headers: Map<String, String>,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = try {
            net.get(url, headers = headers, timeout = 15).text
        } catch (e: Exception) {
            return false
        }
        if (!text.contains("#EXTM3U")) return false
        val quality = qualityFromHeight(bestHeight(text))
        emit(
            callback = callback,
            url = url,
            label = "Toonitalia $label ${if (quality > 0) "${quality}p" else "HLS"} \uD83C\uDDEE\uD83C\uDDF9",
            quality = quality,
            type = ExtractorLinkType.M3U8,
            referer = headers["Referer"],
            headers = headers
        )
        return true
    }

    private fun rot13(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                in 'a'..'z' -> append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                else -> append(c)
            }
        }
    }

    private fun voeDecode(obfuscated: String): JsonNode? {
        var value = rot13(obfuscated)
        for (marker in listOf("@\$", "^^", "~@", "%?", "*~", "!!", "#&")) {
            value = value.replace(marker, "")
        }
        val first = decodeB64(value) ?: return null
        val inner = String(first, Charsets.ISO_8859_1)
        val shifted = buildString(inner.length) {
            for (i in inner.length - 1 downTo 0) {
                append((inner[i].code - 3).toChar())
            }
        }
        val out = decodeB64(shifted) ?: return null
        return runCatching { json.readTree(String(out, Charsets.UTF_8)) }.getOrNull()
    }
}
