package prisynx.hub

import com.fasterxml.jackson.databind.JsonNode
import prisynx.providers.core.UA_PC
import prisynx.providers.core.json
import prisynx.providers.core.net

data class TopxCatalog(val type: String, val id: String, val name: String) {
    val key: String get() = "$type/$id"
    val isTv: Boolean get() = type == "series"
    val label: String get() = "$name · ${if (isTv) "Serie" else "Film"}"
}

data class TopxItem(
    val tmdbId: Int,
    val isTv: Boolean,
    val title: String,
    val poster: String?,
    val year: Int?
)

/** Minimal Stremio catalog client for the TopX addon (or any compatible addon). */
object TopX {
    private const val TTL_MS = 30 * 60 * 1000L
    private val headers = mapOf("User-Agent" to UA_PC, "Accept" to "application/json")
    private var manifestCache: Triple<String, List<TopxCatalog>, Long>? = null
    private val catalogCache = HashMap<String, Pair<List<TopxItem>, Long>>()

    fun baseOf(manifestUrl: String): String =
        manifestUrl.trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")

    private suspend fun get(url: String): JsonNode? = try {
        json.readTree(net.get(url, headers = headers, timeout = 20).text)
    } catch (e: Exception) {
        null
    }

    suspend fun catalogs(manifestUrl: String, force: Boolean = false): List<TopxCatalog> {
        val now = System.currentTimeMillis()
        manifestCache?.let { (url, list, at) ->
            if (!force && url == manifestUrl && now - at < TTL_MS && list.isNotEmpty()) return list
        }
        val root = get("${baseOf(manifestUrl)}/manifest.json") ?: return manifestCache?.second.orEmpty()
        val list = root.path("catalogs").mapNotNull { c ->
            val type = c.path("type").asText("")
            val id = c.path("id").asText("")
            if (type !in listOf("movie", "series") || id.isBlank()) return@mapNotNull null
            TopxCatalog(type, id, c.path("name").asText(id))
        }
        if (list.isNotEmpty()) manifestCache = Triple(manifestUrl, list, now)
        return list
    }

    suspend fun items(manifestUrl: String, catalog: TopxCatalog): List<TopxItem> {
        val url = "${baseOf(manifestUrl)}/catalog/${catalog.type}/${catalog.id}.json"
        val now = System.currentTimeMillis()
        synchronized(catalogCache) {
            catalogCache[url]?.let { (list, at) -> if (now - at < TTL_MS) return list }
        }
        val root = get(url) ?: return emptyList()
        val list = root.path("metas").mapNotNull { m ->
            val tmdb = m.path("tmdbId").asText("").toIntOrNull()
                ?: m.path("id").asText("").removePrefix("tmdb:").toIntOrNull()
                ?: return@mapNotNull null
            val tmdbType = m.path("tmdb_type").asText("")
            val isTv = if (tmdbType.isNotBlank()) tmdbType == "tv" else m.path("type").asText("") == "series"
            TopxItem(
                tmdbId = tmdb,
                isTv = isTv,
                title = m.path("name").asText("").ifBlank { return@mapNotNull null },
                poster = m.path("poster").asText("").ifBlank { null },
                year = m.path("year").asInt(0).takeIf { it > 0 }
                    ?: m.path("releaseInfo").asText("").take(4).toIntOrNull()
            )
        }
        if (list.isNotEmpty()) synchronized(catalogCache) { catalogCache[url] = list to now }
        return list
    }

    fun clearCache() {
        manifestCache = null
        synchronized(catalogCache) { catalogCache.clear() }
    }
}
