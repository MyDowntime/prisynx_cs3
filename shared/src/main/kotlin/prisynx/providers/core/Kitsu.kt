package prisynx.providers.core

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.DubStatus

/**
 * A Kitsu anime (optionally one episode). Kitsu has one entry per season/cour
 * with its own episode numbering, which maps 1:1 to the Italian anime sites.
 */
data class KitsuRef(val id: String, val episode: Int? = null, val isMovie: Boolean = false) {
    fun asData(): String = if (isMovie) "kitsu:m:$id" else "kitsu:$id:${episode ?: 1}"

    /** Real web URL: survives CloudStream's fixUrl (see MediaRef.asWebUrl). */
    fun asWebUrl(): String = "https://kitsu.app/anime/$id"

    companion object {
        private val MOVIE = Regex("^kitsu:m:(\\d+)$")
        private val DATA = Regex("^kitsu:(\\d+)(?::(\\d+))?$")
        private val WEB = Regex("kitsu\\.(?:app|io)/anime/(\\d+)")

        fun parse(value: String): KitsuRef? {
            val v = value.trim()
            MOVIE.find(v)?.let { return KitsuRef(it.groupValues[1], 1, isMovie = true) }
            DATA.find(v)?.let { return KitsuRef(it.groupValues[1], it.groupValues[2].toIntOrNull()) }
            WEB.find(v)?.let { return KitsuRef(it.groupValues[1]) }
            return null
        }
    }
}

class KitsuRow(val id: String, val name: String, val catalog: String, val paged: Boolean = true)

/** Catalogue/meta client backed by the public "Anime Kitsu" Stremio addon. */
object Kitsu {
    private const val BASE = "https://anime-kitsu.strem.fun"
    const val PAGE_SIZE = 20

    val rows: List<KitsuRow> = listOf(
        KitsuRow("trending", "Anime di tendenza", "kitsu-anime-trending", paged = false),
        KitsuRow("airing", "Anime in corso", "kitsu-anime-airing"),
        KitsuRow("popular", "Anime più popolari", "kitsu-anime-popular"),
        KitsuRow("rating", "Anime più votati", "kitsu-anime-rating")
    )

    private suspend fun get(path: String): JsonNode? = try {
        json.readTree(
            net.get(
                "$BASE/$path",
                headers = mapOf("User-Agent" to UA_PC, "Accept" to "application/json"),
                timeout = 25
            ).text
        )
    } catch (e: Exception) {
        null
    }

    suspend fun catalog(row: KitsuRow, page: Int): List<JsonNode> {
        if (page > 1 && !row.paged) return emptyList()
        val skip = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val path = if (skip > 0) "catalog/anime/${row.catalog}/skip=$skip.json" else "catalog/anime/${row.catalog}.json"
        return get(path)?.path("metas")?.toList() ?: emptyList()
    }

    suspend fun search(query: String): List<JsonNode> {
        val q = enc(query.trim()).replace("+", "%20")
        if (q.isBlank()) return emptyList()
        return get("catalog/anime/kitsu-anime-list/search=$q.json")?.path("metas")?.toList() ?: emptyList()
    }

    suspend fun meta(id: String): JsonNode? =
        get("meta/anime/kitsu:$id.json")?.path("meta")?.takeIf { it.isObject }

    fun idOf(node: JsonNode): String? =
        node.path("kitsu_id").asText("").ifBlank { node.path("id").asText("").removePrefix("kitsu:") }
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }

    fun isMovie(node: JsonNode): Boolean =
        node.path("type").asText("") == "movie" || node.path("animeType").asText("").equals("movie", true)

    fun posterOf(id: String) = "https://media.kitsu.app/anime/poster_images/$id/medium.jpg"
}

/** TMDB entries that are Japanese animation (handled by Kitsu when enabled). */
fun isTmdbAnime(node: JsonNode): Boolean {
    val animation = node.path("genre_ids").any { it.asInt() == 16 } ||
        node.path("genres").any { it.path("id").asInt() == 16 }
    return animation && node.path("original_language").asText("") == "ja"
}

fun TmdbProvider.kitsuCard(node: JsonNode): SearchResponse? {
    val id = Kitsu.idOf(node) ?: return null
    val title = node.path("name").asText("").ifBlank { return null }
    val poster = node.path("poster").asText("").ifBlank { Kitsu.posterOf(id) }
    val year = node.path("releaseInfo").asText("").take(4).toIntOrNull()
    val movie = Kitsu.isMovie(node)
    val url = KitsuRef(id, isMovie = movie).asWebUrl()
    val rating = node.path("imdbRating").asText("").toDoubleOrNull()?.takeIf { it > 0 }?.let { Score.from10(it) }
    return if (movie) {
        newMovieSearchResponse(title, url, TvType.AnimeMovie, fix = false) {
            posterUrl = poster
            this.year = year
            score = rating
        }
    } else {
        newAnimeSearchResponse(title, url, TvType.Anime, fix = false) {
            posterUrl = poster
            this.year = year
            score = rating
        }
    }
}

/** Italian overview from TMDB through the IMDb id (Kitsu only has English). */
private suspend fun TmdbProvider.italianPlot(imdb: String?, movie: Boolean): String? {
    if (imdb.isNullOrBlank()) return null
    val root = fetchJson(tmdbUrl("find/$imdb", "external_source=imdb_id")) ?: return null
    val hit = root.path(if (movie) "movie_results" else "tv_results").firstOrNull() ?: return null
    return hit.path("overview").asText("").ifBlank { null }
}

suspend fun TmdbProvider.loadKitsu(ref: KitsuRef, url: String): LoadResponse {
    val m = Kitsu.meta(ref.id)
        ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Anime non trovato su Kitsu")
    val title = m.path("name").asText("").ifBlank { "Anime" }
    val movie = ref.isMovie || Kitsu.isMovie(m)
    val imdb = m.path("imdb_id").asText("").ifBlank { null }
    val poster = m.path("poster").asText("").ifBlank { Kitsu.posterOf(ref.id) }
    val background = m.path("background").asText("").ifBlank { null }
    val logo = m.path("logo").asText("").ifBlank { null }
    val plot = italianPlot(imdb, movie) ?: m.path("description").asText("").ifBlank { null }
    val year = m.path("releaseInfo").asText("").ifBlank { m.path("year").asText("") }.take(4).toIntOrNull()
    val genres = m.path("genres").mapNotNull { it.asText("").ifBlank { null } }
    val runtime = Regex("(\\d+)").find(m.path("runtime").asText(""))?.value?.toIntOrNull()
    val rating = m.path("imdbRating").asText("").toDoubleOrNull()?.let { Score.from10(it) }
    val trailers = m.path("trailers").mapNotNull { t ->
        t.path("source").asText("").ifBlank { null }?.let { "https://www.youtube.com/watch?v=$it" }
    }
    val related = m.path("links")
        .filter { it.path("category").asText("") == "Franchise" }
        .mapNotNull { link ->
            val id = Regex("kitsu:(\\d+)").find(link.path("url").asText(""))?.groupValues?.get(1)
                ?: return@mapNotNull null
            val name = link.path("name").asText("").ifBlank { return@mapNotNull null }
            newAnimeSearchResponse(name, KitsuRef(id).asWebUrl(), TvType.Anime, fix = false) {
                posterUrl = Kitsu.posterOf(id)
            }
        }

    if (movie) {
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, KitsuRef(ref.id, 1, isMovie = true).asData()) {
            posterUrl = poster
            backgroundPosterUrl = background
            logoUrl = logo
            this.plot = plot
            this.year = year
            tags = genres
            duration = runtime
            score = rating
            recommendations = related
            addTrailer(trailers)
            addImdbId(imdb)
        }
    }

    val episodes: List<Episode> = m.path("videos").mapNotNull { v ->
        val number = v.path("episode").asInt(0).takeIf { it > 0 } ?: return@mapNotNull null
        newEpisode(KitsuRef(ref.id, number).asData(), {
            name = v.path("title").asText("").ifBlank { "Episodio $number" }
            episode = number
            posterUrl = v.path("thumbnail").asText("").ifBlank { null }
            description = v.path("overview").asText("").ifBlank { null }
            runTime = Regex("(\\d+)").find(v.path("runtime").asText(""))?.value?.toIntOrNull()
            v.path("released").asText("").take(10).takeIf { it.length == 10 }?.let { addDate(it) }
        }, fix = false)
    }.sortedBy { it.episode }

    return newAnimeLoadResponse(title, url, TvType.Anime) {
        addEpisodes(DubStatus.Subbed, episodes)
        posterUrl = poster
        backgroundPosterUrl = background
        logoUrl = logo
        this.plot = plot
        this.year = year
        tags = genres
        duration = runtime
        score = rating
        recommendations = related
        showStatus = when (m.path("status").asText("")) {
            "current", "upcoming" -> ShowStatus.Ongoing
            "finished" -> ShowStatus.Completed
            else -> null
        }
        addTrailer(trailers)
        addImdbId(imdb)
    }
}
