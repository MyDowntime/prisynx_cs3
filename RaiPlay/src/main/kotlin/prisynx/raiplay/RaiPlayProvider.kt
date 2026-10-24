package prisynx.raiplay

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.nicehttp.Requests

private const val BASE = "https://www.raiplay.it"
private const val SEARCH_URL =
    "$BASE/atomatic/raiplay-search-service/api/v1/msearch"
private const val SEARCH_TEMPLATE_IN = "6470a982e4e0301afe1f81f1"
private const val SEARCH_TEMPLATE_OUT = "6516ac5d40da6c377b151642"
private const val WEB_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

// The relinker only returns the JSON manifest + all quality variants with a non-browser UA.
private const val PLAY_UA = "okhttp/4.12.0"

private data class SetRef(val path: String, val name: String)

class RaiPlayProvider : MainAPI() {
    override var mainUrl = BASE
    override var name = "RaiPlay"
    override var lang = "it"
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val mapper = ObjectMapper()
    private val app = Requests(defaultHeaders = mapOf("User-Agent" to WEB_UA))

    private val apiHeaders = mapOf(
        "User-Agent" to WEB_UA,
        "Referer" to "$BASE/",
        "Accept" to "application/json, text/plain, */*"
    )

    private val seasonRegex =
        Regex("(?:stagione|st\\.?)\\s*(\\d{1,3})", RegexOption.IGNORE_CASE)
    private val resolutionRegex = Regex("RESOLUTION=\\d+x(\\d+)")

    // ---------------------------------------------------------------- helpers

    private fun absUrl(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return BASE + if (value.startsWith("/")) value else "/$value"
    }

    private suspend fun getJson(path: String): JsonNode? {
        val url = absUrl(path) ?: return null
        return try {
            val body: String = app.get(url, headers = apiHeaders, timeout = 30).text
            mapper.readTree(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun imageOf(node: JsonNode): String? {
        val images = node.path("images")
        for (key in listOf(
            "portrait", "landscape", "landscape43", "portrait43", "square",
            "imgLarge", "imgMedium", "imgSmall"
        )) {
            val value = images.path(key).asText("").trim()
            if (value.isNotEmpty()) return absUrl(value)
        }
        for (key in listOf("immagine", "still_frame", "header_bg")) {
            val value = node.path(key).asText("").trim()
            if (value.isNotEmpty()) return absUrl(value)
        }
        return null
    }

    private fun contentUrlOf(node: JsonNode): String? {
        val fromVideo = node.path("video").path("content_url").asText("").trim()
        if (fromVideo.isNotEmpty()) return absUrl(fromVideo)
        val direct = node.path("video_url").asText("").trim()
        if (direct.isNotEmpty()) return absUrl(direct)
        return null
    }

    private fun tagsOf(info: JsonNode): List<String>? {
        val tags = mutableListOf<String>()
        for (field in listOf("genres", "subgenres")) {
            val array = info.path(field)
            if (!array.isArray) continue
            for (entry in array) {
                val genre = if (entry.isTextual) {
                    entry.asText("").trim()
                } else {
                    entry.path("name").asText("").trim()
                        .ifBlank { entry.path("nome").asText("").trim() }
                }
                if (genre.isNotEmpty() && !tags.contains(genre)) tags.add(genre)
            }
        }
        return tags.takeIf { it.isNotEmpty() }
    }

    private fun runtimeOf(node: JsonNode): Int? {
        val duration = node.path("duration").asText("").trim()
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size == 3) {
            val minutes = parts[0] * 60 + parts[1]
            if (minutes > 0) return minutes
        }
        return Regex("\\d+")
            .find(node.path("duration_in_minutes").asText(""))
            ?.value
            ?.toIntOrNull()
    }

    private fun yearOf(node: JsonNode): Int? {
        node.path("year").asText("").trim().toIntOrNull()?.let { return it }
        val published = node.path("date_published").asText("").trim()
        Regex("(?:19|20)\\d{2}").find(published)?.value?.toIntOrNull()?.let { return it }
        return Regex("(?:19|20)\\d{2}")
            .find(node.path("date").asText(""))
            ?.value
            ?.toIntOrNull()
    }

    private fun seasonFromSetName(name: String): Int? =
        seasonRegex.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun qualityFromHeight(height: Int): Int = when {
        height >= 2000 -> 2160
        height >= 1000 -> 1080
        height >= 700 -> 720
        height >= 400 -> 480
        else -> 360
    }

    private fun collectSets(node: JsonNode): List<SetRef> {
        val all = mutableListOf<Pair<String, SetRef>>()
        for (block in node.path("blocks")) {
            val blockName = block.path("name").asText("").trim()
            val sets = block.path("sets")
            if (!sets.isArray) continue
            for (set in sets) {
                val path = set.path("path_id").asText("").trim()
                if (path.isEmpty()) continue
                val setName = set.path("name").asText("").trim()
                all.add(blockName to SetRef(path, "$blockName $setName".trim()))
            }
        }
        val episodeSets = all.filter {
            val label = "${it.first} ${it.second.name}"
            label.contains("episod", true) || label.contains("puntat", true)
        }
        val chosen = if (episodeSets.isNotEmpty()) episodeSets else all
        val seen = mutableSetOf<String>()
        return chosen.map { it.second }.filter { seen.add(it.path) }
    }

    private fun episodeFromItem(
        item: JsonNode,
        setSeason: Int?,
        fallbackPoster: String?
    ): Episode? {
        val path = item.path("path_id").asText("").trim()
        if (path.isEmpty()) return null
        val number = item.path("episode").asText("").trim().toIntOrNull()
        // RaiPlay sometimes stores a year ("2024") or a range ("2023/24") in `season`.
        // Only accept plausible season numbers, otherwise fall back to the set name.
        val itemSeason = item.path("season").asText("").trim().toIntOrNull()
            ?.takeIf { it in 1..100 }
        val season = itemSeason ?: setSeason
        val title = item.path("episode_title").asText("").trim()
            .ifBlank { item.path("name").asText("").trim() }
            .ifBlank { if (number != null) "Episodio $number" else "" }
        return newEpisode(path) {
            name = title
            this.season = season
            this.episode = number
            posterUrl = imageOf(item) ?: fallbackPoster
            description = item.path("description").asText("").trim().ifBlank { null }
            runTime = runtimeOf(item)
        }
    }

    // ------------------------------------------------------------- homepage

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val rows = homeRows()
        if (rows.isEmpty()) return null
        return newHomePageResponse(rows)
    }

    /** RaiPlay home rows (also used by the PriSynx hub). */
    suspend fun homeRows(): List<HomePageList> {
        val root = getJson("/index.json") ?: return emptyList()
        val rows = mutableListOf<HomePageList>()

        for (section in root.path("contents")) {
            if (rows.size >= 15) break
            val rawTitle = section.path("name").asText("").trim()
            if (rawTitle.isEmpty()) continue
            val title = if (rawTitle.equals("Hero", true)) "In evidenza" else rawTitle
            val cards = section.path("contents")
            if (!cards.isArray) continue
            val items = cards.mapNotNull { cardToSearchResponse(it, title) }
            if (items.isNotEmpty()) {
                rows.add(HomePageList(title, items, isHorizontalImages = true))
            }
        }

        return rows
    }

    private fun cardToSearchResponse(card: JsonNode, section: String): SearchResponse? {
        val path = card.path("path_id").asText("").trim().ifBlank { null } ?: return null
        val title = card.path("name").asText("").trim()
            .ifBlank { card.path("title").asText("").trim() }
            .ifBlank { card.path("titolo").asText("").trim() }
            .ifBlank { null } ?: return null
        val type = card.path("type").asText("")
        val poster = imageOf(card)

        return when {
            type.contains("Diretta", true) ->
                newLiveSearchResponse(title, path) { posterUrl = poster }

            type.contains("Video Item", true) ->
                newMovieSearchResponse(title, path, TvType.Movie) { posterUrl = poster }

            section.contains("Film", true) ->
                newMovieSearchResponse(title, path, TvType.Movie) { posterUrl = poster }

            else ->
                newTvSeriesSearchResponse(title, path, TvType.TvSeries) { posterUrl = poster }
        }
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val payload = mapOf(
            "templateIn" to SEARCH_TEMPLATE_IN,
            "templateOut" to SEARCH_TEMPLATE_OUT,
            "params" to mapOf(
                "param" to query,
                "from" to null,
                "sort" to "relevance",
                "onlyVideoQuery" to false
            )
        )

        val rawBody: String = try {
            app.post(
                SEARCH_URL,
                headers = apiHeaders + mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                json = JsonAsString(mapper.writeValueAsString(payload)),
                timeout = 30
            ).text
        } catch (e: Exception) {
            return emptyList()
        }

        val root = try {
            mapper.readTree(rawBody)
        } catch (e: Exception) {
            return emptyList()
        }

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        for (card in root.path("agg").path("titoli").path("cards")) {
            val path = card.path("path_id").asText("").trim()
            if (path.isEmpty() || !seen.add(path)) continue
            val title = card.path("titolo").asText("").trim()
                .ifBlank { card.path("name").asText("").trim() }
                .ifBlank { null } ?: continue
            val poster = imageOf(card)
            results.add(
                newTvSeriesSearchResponse(title, path, TvType.TvSeries) {
                    posterUrl = poster
                }
            )
        }

        for (card in root.path("agg").path("video").path("cards")) {
            val path = card.path("path_id").asText("").trim()
            if (path.isEmpty() || !seen.add(path)) continue
            val title = card.path("titolo").asText("").trim()
                .ifBlank { card.path("name").asText("").trim() }
                .ifBlank { null } ?: continue
            val poster = imageOf(card)
            results.add(
                newMovieSearchResponse(title, path, TvType.Movie) {
                    posterUrl = poster
                }
            )
        }

        return results
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val node = getJson(url) ?: return null
        val contentType = contentUrlOf(node)
        val isLive = node.path("is_live").asBoolean(false) ||
            node.path("video").path("is_live").asBoolean(false)

        if (isLive && contentType != null) {
            return newLiveStreamLoadResponse(
                name = node.path("name").asText("RaiPlay").trim(),
                url = url,
                dataUrl = contentType
            ) {
                posterUrl = imageOf(node)
                plot = node.path("description").asText("").trim().ifBlank { null }
            }
        }

        if (node.has("program_info")) return loadProgram(url, node)

        if (contentType != null) return loadVideo(url, node, contentType)

        if (node.has("items")) return loadSet(url, node)

        return null
    }

    private suspend fun loadVideo(
        url: String,
        node: JsonNode,
        contentUrl: String
    ): LoadResponse {
        val title = node.path("name").asText("").trim()
            .ifBlank { node.path("episode_title").asText("").trim() }
            .ifBlank { "RaiPlay" }
        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = contentUrl) {
            posterUrl = imageOf(node)
            plot = node.path("description").asText("").trim().ifBlank { null }
            year = yearOf(node)
            tags = listOfNotNull(
                node.path("program_name").asText("").trim().ifBlank { null }
            ).takeIf { it.isNotEmpty() }
            duration = runtimeOf(node)
        }
    }

    private suspend fun loadProgram(url: String, node: JsonNode): LoadResponse? {
        val info = node.path("program_info")
        val name = node.path("name").asText("").trim()
            .ifBlank { info.path("name").asText("").trim() }
            .ifBlank { return null }
        val poster = imageOf(info)
        val plot = info.path("description").asText("").trim().ifBlank { null }
        val year = yearOf(info)
        val tags = tagsOf(info)
        val typology = info.path("typology").asText("")
        val firstItem = node.path("first_item_path").asText("").trim().ifBlank { null }
        val sets = collectSets(node)

        val isMovie = typology.contains("film", true) ||
            (sets.isEmpty() && firstItem != null)

        if (isMovie && firstItem != null) {
            return newMovieLoadResponse(name, url, TvType.Movie, dataUrl = firstItem) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (set in sets.take(60)) {
            val setNode = getJson(set.path) ?: continue
            val setSeason = seasonFromSetName(set.name)
            for (item in setNode.path("items")) {
                val path = item.path("path_id").asText("").trim()
                if (path.isEmpty() || !seen.add(path)) continue
                episodes.add(episodeFromItem(item, setSeason, poster) ?: continue)
            }
        }

        if (episodes.isEmpty()) {
            if (firstItem == null) return null
            return newMovieLoadResponse(name, url, TvType.Movie, dataUrl = firstItem) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun loadSet(url: String, node: JsonNode): LoadResponse? {
        val name = node.path("name").asText("RaiPlay").trim()
        val poster = imageOf(node)
        val setSeason = seasonFromSetName(name)
        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        for (item in node.path("items")) {
            val path = item.path("path_id").asText("").trim()
            if (path.isEmpty() || !seen.add(path)) continue
            episodes.add(episodeFromItem(item, setSeason, poster) ?: continue)
        }

        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
            posterUrl = poster
        }
    }

    // ------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var target = data
        if (target.contains(".json", true)) {
            val node = getJson(target) ?: return false
            target = contentUrlOf(node) ?: return false
        }

        val (streamUrl, contentType) = relinker(target) ?: return false
        val isHls = streamUrl.contains(".m3u8", true) ||
            contentType.contains("m3u8", true) ||
            contentType.contains("hls", true)

        var quality = 0
        if (isHls) {
            val master = try {
                app.get(
                    streamUrl,
                    headers = mapOf("User-Agent" to PLAY_UA),
                    timeout = 20
                ).text
            } catch (e: Exception) {
                null
            }
            if (master != null && master.contains("#EXTM3U")) {
                quality = resolutionRegex.findAll(master)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .maxOrNull()
                    ?.let { qualityFromHeight(it) }
                    ?: 0
            }
        }

        val label = if (quality > 0) "${quality}p" else "Auto"
        callback(
            newExtractorLink(
                source = "RaiPlay",
                name = "RaiPlay $label",
                url = streamUrl,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = "$BASE/"
                headers = mapOf(
                    "User-Agent" to PLAY_UA,
                    "Referer" to "$BASE/"
                )
                this.quality = quality
            }
        )
        return true
    }

    private suspend fun relinker(contentUrl: String): Pair<String, String>? {
        val url = absUrl(contentUrl) ?: return null
        val separator = if (url.contains("?")) "&" else "?"
        val body: String = try {
            app.get(
                "$url${separator}output=62",
                headers = mapOf("User-Agent" to PLAY_UA),
                timeout = 30
            ).text
        } catch (e: Exception) {
            return null
        }

        val node = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            return null
        }

        val video = node.path("video")
        val streamUrl = when {
            video.isArray && video.size() > 0 -> video.path(0).asText("").trim()
            video.isTextual -> video.asText("").trim()
            else -> node.path("url").asText("").trim()
        }
        if (streamUrl.isEmpty()) return null

        return streamUrl to node.path("ct").asText("")
    }
}
