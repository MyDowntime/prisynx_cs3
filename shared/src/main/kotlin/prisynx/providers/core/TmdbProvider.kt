package prisynx.providers.core

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

const val TMDB_API = "https://api.themoviedb.org/3"
const val TMDB_KEY = "68e094699525b18a70bab2f86b1fa706"
const val IMG_500 = "https://image.tmdb.org/t/p/w500"
const val IMG_780 = "https://image.tmdb.org/t/p/w780"
const val IMG_1280 = "https://image.tmdb.org/t/p/w1280"
const val IMG_185 = "https://image.tmdb.org/t/p/w185"

const val UA_PC =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
const val UA_MOBILE =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

val net: Requests by lazy {
    Requests(defaultHeaders = mapOf("User-Agent" to UA_PC))
}
val json: ObjectMapper by lazy { ObjectMapper() }

data class MediaRef(
    val id: Int,
    val isTv: Boolean,
    val season: Int? = null,
    val episode: Int? = null
) {
    fun asData(): String = when {
        isTv -> "tmdb:tv:$id:${season ?: 1}:${episode ?: 1}"
        else -> "tmdb:movie:$id"
    }

    /**
     * CloudStream applies [MainAPI.fixUrl] to every SearchResponse.url before
     * calling load(), even when the response was created with fix = false.
     * A custom "tmdb:movie:123" URL would therefore become
     * "https://<provider>/tmdb:movie:123" and fail to load. A real TMDB web
     * URL starts with https:// and survives fixUrl unchanged.
     */
    fun asWebUrl(): String = when {
        isTv -> "https://www.themoviedb.org/tv/$id"
        else -> "https://www.themoviedb.org/movie/$id"
    }

    companion object {
        private val REF = Regex("^tmdb:(movie|tv):(\\d+)(?::(\\d+):(\\d+))?$")
        private val WEB = Regex(
            "themoviedb\\.org/(movie|tv)/(\\d+)(?:/season/(\\d+)/episode/(\\d+))?"
        )

        fun parse(data: String): MediaRef? {
            val value = data.trim()
            REF.find(value)?.let { m ->
                val isTv = m.groupValues[1] == "tv"
                return MediaRef(
                    id = m.groupValues[2].toIntOrNull() ?: return null,
                    isTv = isTv,
                    season = m.groupValues[3].toIntOrNull(),
                    episode = m.groupValues[4].toIntOrNull()
                )
            }
            WEB.find(value)?.let { m ->
                val isTv = m.groupValues[1] == "tv"
                return MediaRef(
                    id = m.groupValues[2].toIntOrNull() ?: return null,
                    isTv = isTv,
                    season = m.groupValues[3].toIntOrNull(),
                    episode = m.groupValues[4].toIntOrNull()
                )
            }
            // Tolerate a bare TMDB id (movie by default).
            value.replace("tmdb:", "").toIntOrNull()?.let {
                return MediaRef(it, false)
            }
            return null
        }
    }
}

data class MediaMeta(
    val title: String = "",
    val originalTitle: String = "",
    val year: Int? = null,
    val imdbId: String? = null,
    val runtime: Int? = null,
    val posterUrl: String? = null,
    val backgroundUrl: String? = null,
    val plot: String? = null,
    val genres: List<String> = emptyList()
)

/** A single home-page row backed by a TMDB endpoint. */
data class TmdbRow(
    val name: String,
    val path: String,
    val extra: String = "",
    val forcedTv: Boolean? = null,
    val limit: Int = 24
)

/** Result of loading one [TmdbRow] for a given page. */
data class TmdbRowResult(
    val name: String,
    val list: List<SearchResponse>,
    val hasNext: Boolean
)

/** Shared TMDB catalogue/load layer used by all PriSynx stream providers. */
abstract class TmdbProvider(
    override var name: String,
    override var mainUrl: String
) : MainAPI() {
    override var lang = "it"
    override val hasMainPage = true

    open val supportsMovies = true
    open val supportsTv = true

    override val supportedTypes: Set<TvType>
        get() = buildSet {
            if (supportsMovies) add(TvType.Movie)
            if (supportsTv) add(TvType.TvSeries)
        }

    // ---------------------------------------------------------------- TMDB

    suspend fun fetchJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long = 25
    ): JsonNode? = try {
        json.readTree(net.get(url, headers = headers, timeout = timeout).text)
    } catch (e: Exception) {
        null
    }

    fun tmdbUrl(path: String, extra: String = ""): String {
        val sep = if (extra.isEmpty()) "" else "&$extra"
        return "$TMDB_API/$path?api_key=$TMDB_KEY&language=it-IT$sep"
    }

    suspend fun fetchDetails(ref: MediaRef): JsonNode? = fetchJson(
        tmdbUrl(
            if (ref.isTv) "tv/${ref.id}" else "movie/${ref.id}",
            "append_to_response=external_ids"
        )
    )

    /** Details plus cast, trailers, recommendations, logos and age ratings for the info page. */
    suspend fun fetchFullDetails(ref: MediaRef): JsonNode? = fetchJson(
        tmdbUrl(
            if (ref.isTv) "tv/${ref.id}" else "movie/${ref.id}",
            "append_to_response=external_ids,videos,recommendations,images," +
                (if (ref.isTv) "aggregate_credits,content_ratings" else "credits,release_dates") +
                "&include_video_language=it,en&include_image_language=it,en,null"
        )
    )

    private fun actorsOf(d: JsonNode, isTv: Boolean): List<ActorData> {
        val cast = if (isTv) d.path("aggregate_credits").path("cast") else d.path("credits").path("cast")
        return cast.take(25).mapNotNull { node ->
            val name = node.path("name").asText("").ifBlank { return@mapNotNull null }
            val profile = node.path("profile_path").asText("")
            val role = if (isTv) {
                node.path("roles").firstOrNull()?.path("character")?.asText("")
            } else {
                node.path("character").asText("")
            }
            ActorData(
                actor = Actor(name, profile.takeIf { it.isNotBlank() }?.let { IMG_185 + it }),
                roleString = role?.ifBlank { null }
            )
        }
    }

    private fun trailersOf(d: JsonNode): List<String> =
        d.path("videos").path("results")
            .filter { it.path("site").asText("") == "YouTube" && it.path("key").asText("").isNotBlank() }
            .filter { it.path("type").asText("") in setOf("Trailer", "Teaser") }
            .sortedWith(
                compareBy(
                    { if (it.path("iso_639_1").asText("") == "it") 0 else 1 },
                    { if (it.path("type").asText("") == "Trailer") 0 else 1 },
                    { if (it.path("official").asBoolean(false)) 0 else 1 }
                )
            )
            .map { "https://www.youtube.com/watch?v=" + it.path("key").asText("") }
            .distinct()
            .take(4)

    private fun logoOf(d: JsonNode): String? {
        val logos = d.path("images").path("logos").toList()
        val pick = logos.firstOrNull { it.path("iso_639_1").asText("") == "it" }
            ?: logos.firstOrNull { it.path("iso_639_1").asText("") == "en" }
            ?: logos.firstOrNull()
        return pick?.path("file_path")?.asText("")?.takeIf { it.isNotBlank() }?.let { IMG_500 + it }
    }

    private fun contentRatingOf(d: JsonNode, isTv: Boolean): String? {
        fun pick(country: String): String? = if (isTv) {
            d.path("content_ratings").path("results")
                .firstOrNull { it.path("iso_3166_1").asText("") == country }
                ?.path("rating")?.asText("")?.ifBlank { null }
        } else {
            d.path("release_dates").path("results")
                .firstOrNull { it.path("iso_3166_1").asText("") == country }
                ?.path("release_dates")
                ?.map { it.path("certification").asText("") }
                ?.firstOrNull { it.isNotBlank() }
        }
        return pick("IT") ?: pick("US")
    }

    private fun recommendationsOf(d: JsonNode, isTv: Boolean): List<SearchResponse> =
        d.path("recommendations").path("results").take(20).mapNotNull { cardOf(it, isTv) }

    private fun scoreOf(node: JsonNode): Score? =
        node.path("vote_average").asDouble(0.0).takeIf { it > 0 }?.let { Score.from10(it) }

    /** Italian overview is often missing: fall back to the English one. */
    private suspend fun plotOf(ref: MediaRef, meta: MediaMeta): String? = meta.plot ?: try {
        fetchJson(
            "$TMDB_API/${if (ref.isTv) "tv" else "movie"}/${ref.id}?api_key=$TMDB_KEY&language=en-US"
        )?.path("overview")?.asText("")?.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    suspend fun toMeta(ref: MediaRef, details: JsonNode? = null): MediaMeta {
        val d = details ?: fetchDetails(ref) ?: return MediaMeta()
        val title = if (ref.isTv) d.path("name").asText("") else d.path("title").asText("")
        val original =
            if (ref.isTv) d.path("original_name").asText("")
            else d.path("original_title").asText("")
        val date =
            if (ref.isTv) d.path("first_air_date").asText("")
            else d.path("release_date").asText("")
        val poster = d.path("poster_path").asText("")
        val backdrop = d.path("backdrop_path").asText("")
        val imdb = d.path("imdb_id").asText("")
            .ifBlank { d.path("external_ids").path("imdb_id").asText("") }
            .ifBlank { null }
        var runtime = if (ref.isTv) 0 else d.path("runtime").asInt(0)
        if (ref.isTv && runtime == 0) {
            runtime = d.path("episode_run_time").firstOrNull()?.asInt(0) ?: 0
        }
        val genres = d.path("genres").mapNotNull { it.path("name").asText("").ifBlank { null } }
        return MediaMeta(
            title = title,
            originalTitle = original,
            year = date.take(4).toIntOrNull(),
            imdbId = imdb,
            runtime = runtime.takeIf { it > 0 },
            posterUrl = poster.takeIf { it.isNotBlank() }?.let { IMG_500 + it },
            backgroundUrl = backdrop.takeIf { it.isNotBlank() }?.let { IMG_780 + it },
            plot = d.path("overview").asText("").ifBlank { null },
            genres = genres
        )
    }

    // ---------------------------------------------------------------- catalogue

    protected fun cardOf(node: JsonNode, isTv: Boolean): SearchResponse? {
        if (node.path("adult").asBoolean(false) == true) return null
        val id = node.path("id").asInt(0)
        if (id <= 0) return null
        val ref = MediaRef(id, isTv)
        val title =
            if (isTv) node.path("name").asText("")
            else node.path("title").asText("")
        if (title.isBlank()) return null
        val date =
            if (isTv) node.path("first_air_date").asText("")
            else node.path("release_date").asText("")
        val poster = node.path("poster_path").asText("")
        return if (isTv) {
            newTvSeriesSearchResponse(title, ref.asWebUrl(), TvType.TvSeries, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }?.let { IMG_500 + it }
                year = date.take(4).toIntOrNull()
            }
        } else {
            newMovieSearchResponse(title, ref.asWebUrl(), TvType.Movie, fix = false) {
                posterUrl = poster.takeIf { it.isNotBlank() }?.let { IMG_500 + it }
                year = date.take(4).toIntOrNull()
            }
        }
    }

    /** Home page rows; subclasses can override to expose a custom catalogue. */
    protected open fun homeRows(): List<TmdbRow> = buildList {
        add(TmdbRow("Tendenze della settimana", "trending/all/week"))
        if (supportsMovies) add(TmdbRow("Film popolari", "movie/popular", forcedTv = false))
        if (supportsTv) add(TmdbRow("Serie TV popolari", "tv/popular", forcedTv = true))
    }

    /**
     * Load one catalogue row for a given [page]. TMDB paginates its own
     * endpoints, so pages 2, 3, ... must be requested explicitly; otherwise the
     * home page would only ever show the first batch of titles.
     */
    protected suspend fun fetchRow(spec: TmdbRow, page: Int): TmdbRowResult {
        val currentPage = page.coerceAtLeast(1)
        val extra = listOfNotNull(
            spec.extra.takeIf { it.isNotBlank() },
            "page=$currentPage"
        ).joinToString("&")
        val root = fetchJson(tmdbUrl(spec.path, extra))
            ?: return TmdbRowResult(spec.name, emptyList(), false)
        val list = root.path("results").mapNotNull { node ->
            val isTv = spec.forcedTv ?: (node.path("media_type").asText("") == "tv")
            if (spec.forcedTv == null && node.path("media_type").asText("") !in listOf("movie", "tv")) {
                return@mapNotNull null
            }
            if (isTv && !supportsTv) return@mapNotNull null
            if (!isTv && !supportsMovies) return@mapNotNull null
            cardOf(node, isTv)
        }.take(spec.limit)
        val totalPages = root.path("total_pages").asInt(1).coerceAtLeast(1)
        return TmdbRowResult(spec.name, list, list.isNotEmpty() && currentPage < totalPages)
    }

    /** Backwards-compatible single-row helper (page 1 by default). */
    protected suspend fun row(
        name: String,
        path: String,
        forcedTv: Boolean? = null,
        limit: Int = 24,
        extra: String = "",
        page: Int = 1
    ): HomePageList? {
        val result = fetchRow(TmdbRow(name, path, extra, forcedTv, limit), page)
        return if (result.list.isEmpty()) null else HomePageList(result.name, result.list)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The rows are independent: fetch them at the same time so a slow TMDB
        // response does not delay the whole home page. `page` is forwarded so a
        // "load more" request returns the next batch instead of repeating page 1.
        val results = coroutineScope {
            homeRows().map { spec -> async { fetchRow(spec, page) } }.awaitAll()
        }
        val rows = results
            .filter { it.list.isNotEmpty() }
            .map { HomePageList(it.name, it.list) }
        return newHomePageResponse(rows, results.any { it.hasNext })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val root = fetchJson(
            tmdbUrl("search/multi", "query=$encoded&include_adult=false&page=1")
        ) ?: return emptyList()
        return root.path("results").mapNotNull { node ->
            val type = node.path("media_type").asText("")
            when {
                type == "movie" && supportsMovies -> cardOf(node, false)
                type == "tv" && supportsTv -> cardOf(node, true)
                else -> null
            }
        }
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        val ref = MediaRef.parse(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Riferimento non valido")
        val details = fetchFullDetails(ref) ?: fetchDetails(ref)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Titolo non trovato")
        val meta = toMeta(ref, details)
        val title = meta.title.ifBlank { meta.originalTitle }
        val fullPlot = plotOf(ref, meta)
        val background = details.path("backdrop_path").asText("")
            .takeIf { it.isNotBlank() }?.let { IMG_1280 + it } ?: meta.backgroundUrl
        val tagline = details.path("tagline").asText("").ifBlank { null }
        val plotText = listOfNotNull(tagline?.let { "\u201C$it\u201D" }, fullPlot)
            .joinToString("\n\n").ifBlank { null }

        if (!ref.isTv) {
            return newMovieLoadResponse(title, url, TvType.Movie, ref.asData()) {
                posterUrl = meta.posterUrl
                backgroundPosterUrl = background
                logoUrl = logoOf(details)
                plot = plotText
                year = meta.year
                tags = meta.genres
                duration = meta.runtime
                score = scoreOf(details)
                contentRating = contentRatingOf(details, false)
                actors = actorsOf(details, false)
                recommendations = recommendationsOf(details, false)
                addTrailer(trailersOf(details))
                addImdbId(meta.imdbId)
                addTMDbId(ref.id.toString())
            }
        }

        // Load every season in parallel (bounded to avoid hammering TMDB).
        // Long shows used to make N sequential requests before showing anything.
        val seasonNumbers = details.path("seasons").mapNotNull { seasonNode ->
            seasonNode.path("season_number").asInt(-1).takeIf { it in 1..60 }
        }
        val semaphore = Semaphore(4)
        val seasonRoots = coroutineScope {
            seasonNumbers.map { sn ->
                async {
                    sn to semaphore.withPermit {
                        fetchJson(tmdbUrl("tv/${ref.id}/season/$sn"))
                    }
                }
            }.awaitAll()
        }

        val episodes = mutableListOf<Episode>()
        for ((sn, loadedSeason) in seasonRoots) {
            val seasonRoot = loadedSeason ?: continue
            for (ep in seasonRoot.path("episodes")) {
                val en = ep.path("episode_number").asInt(0)
                if (en <= 0) continue
                val still = ep.path("still_path").asText("")
                episodes.add(
                    newEpisode(
                        url = MediaRef(ref.id, true, sn, en).asData(),
                        initializer = {
                            name = ep.path("name").asText("Episodio $en")
                            this.season = sn
                            this.episode = en
                            description = ep.path("overview").asText("").ifBlank { null }
                            posterUrl = still.takeIf { it.isNotBlank() }?.let { IMG_500 + it }
                            runTime = ep.path("runtime").asInt(0).takeIf { it > 0 }
                            score = scoreOf(ep)
                            ep.path("air_date").asText("").takeIf { it.isNotBlank() }?.let { addDate(it) }
                        },
                        fix = false
                    )
                )
            }
        }

        val next = details.path("next_episode_to_air")
        val nextAiringData = run {
            val date = next.path("air_date").asText("")
            val ep = next.path("episode_number").asInt(0)
            if (date.isBlank() || ep <= 0) return@run null
            val millis = try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date)?.time
            } catch (e: Exception) {
                null
            } ?: return@run null
            NextAiring(ep, millis / 1000, next.path("season_number").asInt(0).takeIf { it > 0 })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = meta.posterUrl
            backgroundPosterUrl = background
            logoUrl = logoOf(details)
            plot = plotText
            year = meta.year
            tags = meta.genres
            duration = meta.runtime
            score = scoreOf(details)
            contentRating = contentRatingOf(details, true)
            actors = actorsOf(details, true)
            recommendations = recommendationsOf(details, true)
            showStatus = when (details.path("status").asText("")) {
                "Ended", "Canceled" -> ShowStatus.Completed
                "Returning Series", "In Production", "Planned", "Pilot" -> ShowStatus.Ongoing
                else -> null
            }
            nextAiring = nextAiringData
            addTrailer(trailersOf(details))
            addImdbId(meta.imdbId)
            addTMDbId(ref.id.toString())
        }
    }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = MediaRef.parse(data) ?: return false
        if (ref.isTv && !supportsTv) return false
        if (!ref.isTv && !supportsMovies) return false
        val meta = try {
            toMeta(ref)
        } catch (e: Exception) {
            MediaMeta()
        }
        return resolve(ref, meta, subtitleCallback, callback)
    }

    /** Implement the provider-specific resolution for a movie or a TV episode. */
    open suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false

    protected suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        url: String,
        label: String,
        quality: Int,
        type: ExtractorLinkType = ExtractorLinkType.M3U8,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = type
            ) {
                this.quality = quality
                this.referer = referer ?: ""
                this.headers = headers
            }
        )
    }

    /** CloudStream extractors name links after the host only ("MixDrop"): prefix the provider. */
    private suspend fun withProviderName(link: ExtractorLink): ExtractorLink {
        if (link.javaClass != ExtractorLink::class.java || link.name.startsWith(name)) return link
        return newExtractorLink(
            source = link.source,
            name = "$name \u00B7 ${link.name}",
            url = link.url,
            type = link.type
        ) {
            this.quality = link.quality
            this.referer = link.referer
            this.headers = link.headers
            this.extractorData = link.extractorData
        }
    }

    protected suspend fun emitPlayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        try {
            val found = mutableListOf<ExtractorLink>()
            loadExtractorWrapper(url, referer, subtitleCallback) { found.add(it) }
            found.forEach {
                emitted = true
                callback(withProviderName(it))
            }
        } catch (e: Exception) {
            // ignore, caller can retry with a custom extractor
        }
        return emitted
    }
}

// ---------------------------------------------------------------- helpers

suspend fun loadExtractorWrapper(
    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    com.lagradost.cloudstream3.utils.loadExtractor(url, referer, subtitleCallback, callback)
}

fun JsonNode.text(field: String): String = path(field).asText("").trim()

fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

fun absUrl(uri: String, base: String): String {
    val value = uri.trim().replace("\\/", "/")
    if (value.isEmpty()) return ""
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    if (value.startsWith("//")) return "https:$value"
    val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: ""
    if (value.startsWith("/")) return origin + value
    val dir = base.substringBefore("?").substringBeforeLast("/")
    return "$dir/$value"
}

fun qualityFromHeight(height: Int): Int = when {
    height >= 2160 -> 2160
    height >= 1440 -> 1440
    height >= 1080 -> 1080
    height >= 720 -> 720
    height >= 480 -> 480
    height >= 360 -> 360
    else -> 0
}

fun qualityFromText(text: String): Int {
    val t = text.lowercase()
    return when {
        t.contains("2160") || t.contains("4k") || t.contains("uhd") -> 2160
        t.contains("1080") || t.contains("fhd") -> 1080
        t.contains("720") -> 720
        t.contains("576") || t.contains("480") || t.contains("sd") -> 480
        else -> 0
    }
}

fun qualityLabel(quality: Int): String = when {
    quality >= 2160 -> "4K"
    quality >= 1440 -> "1440p"
    quality >= 1080 -> "1080p"
    quality >= 720 -> "720p"
    quality >= 480 -> "480p"
    quality > 0 -> "${quality}p"
    else -> "HD"
}

fun bestHeight(master: String): Int =
    Regex("RESOLUTION=\\d+x(\\d+)").findAll(master)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .maxOrNull() ?: 0

fun decodeB64(value: String): ByteArray? = try {
    Base64.decode(value, Base64.DEFAULT)
} catch (e: Exception) {
    null
}

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/** Dean Edwards packer used by Uqload and several Italian hosts. */
fun unpackJs(p: String, a: Int, c0: Int, k: List<String>): String {
    fun e(n: Int): String {
        if (n < a) return ""
        val digit = n % a
        val ch = if (digit > 35) (digit + 29).toChar() else digit.toString(36).first()
        return e(n / a) + ch
    }
    val dict = HashMap<String, String>()
    for (i in (c0 - 1) downTo 0) {
        val key = e(i)
        dict[key] = k.getOrNull(i) ?: key
    }
    return Regex("\\b\\w+\\b").replace(p) { match -> dict[match.value] ?: match.value }
}
