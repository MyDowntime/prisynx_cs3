package prisynx.providers.core

import com.fasterxml.jackson.databind.JsonNode

private val mappingBases = listOf(
    "https://animemapping.realbestia.com",
    "https://anime.stremio-italia.eu"
)

/** Fetch the shared anime mapping payload from the first base that answers. */
suspend fun fetchAnimeMapping(
    provider: String,
    id: String,
    season: Int,
    episode: Int
): JsonNode? {
    val query = "?ep=$episode" + (if (season > 0) "&s=$season" else "") + "&lang=it"
    for (base in mappingBases) {
        val url = "$base/$provider/${enc(id)}$query"
        val node = try {
            json.readTree(
                net.get(
                    url,
                    headers = mapOf("User-Agent" to UA_PC, "Accept" to "application/json"),
                    timeout = 15
                ).text
            )
        } catch (e: Exception) {
            null
        }
        if (node != null && node.path("ok").asBoolean(false) && !node.path("mappings").isMissingNode) {
            return node
        }
    }
    return null
}

/** Extract normalized paths for a given anime site from a mapping payload. */
fun mappingPaths(map: JsonNode?, key: String, prefix: String, limit: Int = 4): List<String> {
    val raw = map?.path("mappings")?.path(key) ?: return emptyList()
    val items = if (raw.isArray) raw.toList() else listOf(raw)
    val out = mutableListOf<String>()
    for (item in items) {
        val value = when {
            item.isTextual -> item.asText("")
            item.has("path") -> item.path("path").asText("")
            item.has("url") -> item.path("url").asText("")
            item.has("href") -> item.path("href").asText("")
            else -> ""
        }
        val path = Regex("^$prefix[^/?#]+").find(
            value.replace(Regex("^https?://[^/]+"), "")
        )?.value ?: continue
        if (path !in out) out.add(path)
    }
    return out.sortedByDescending { isItalianPath(it) }.take(limit)
}

fun isItalianPath(path: String): Boolean =
    Regex("(?:^|[-_/])ita(?:[-_/]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)

fun mappingAbsoluteEpisode(map: JsonNode?, fallback: Int): Int =
    map?.path("kitsu")?.path("episode")?.asInt(0)?.takeIf { it > 0 } ?: fallback

fun mappingTitle(map: JsonNode?): String {
    val kitsu = map?.path("kitsu") ?: return ""
    return kitsu.path("canonicalTitle").asText("").ifBlank {
        kitsu.path("titles").path("it").asText("").ifBlank {
            kitsu.path("titles").path("en").asText("")
        }
    }
}

fun mappingYear(map: JsonNode?): Int =
    map?.path("kitsu")?.path("startDate")?.asText("")?.take(4)?.toIntOrNull() ?: 0

fun decodeHtmlEntities(value: String): String {
    var out = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
    out = Regex("&#(\\d+);").replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
        code.toChar().toString()
    }
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
        code.toChar().toString()
    }
    return out
}
