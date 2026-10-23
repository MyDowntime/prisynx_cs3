package prisynx.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.bestHeight
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromHeight

/**
 * PaCC (partite.cc): the title page already contains signed /hls/p/... master
 * playlists, one per server/episode. No TMDB lookup on the site side (IMDb id).
 */
class PaCCProvider : TmdbProvider("PaCC", "https://www.partite.cc") {
    private val playlistRegex = Regex(
        "(?:https?://[^\\s\"'<>]+)?/hls/[^\\s\"'<>]+?/playlist\\.m3u8(?:\\?[^\\s\"'<>]*)?"
    )
    private val pathRegex = Regex(
        "/hls/(?:p/[^/]+/[^/]+/)?s(\\d+)/(movie|serial)/([^/]+)(?:/(\\d+)/(\\d+))?/playlist\\.m3u8",
        RegexOption.IGNORE_CASE
    )

    private data class Playlist(
        val url: String,
        val server: Int,
        val kind: String,
        val id: String,
        val season: Int?,
        val episode: Int?
    )

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val imdb = meta.imdbId ?: return false
        val page = if (ref.isTv) "$mainUrl/serie-tv/$imdb" else "$mainUrl/film/$imdb"
        val html = try {
            net.get(
                page,
                headers = mapOf("User-Agent" to UA_PC, "Referer" to "$mainUrl/"),
                timeout = 25
            ).text
        } catch (e: Exception) {
            return false
        }

        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        val candidates = playlistRegex.findAll(html)
            .mapNotNull { match -> parse(absolute(match.value.replace("&amp;", "&"))) }
            .filter { item ->
                if (ref.isTv) {
                    item.kind == "serial" && item.season == season && item.episode == episode
                } else {
                    item.kind == "movie" && item.id.equals(imdb, true)
                }
            }
            .distinctBy { it.url }
            .take(4)
            .toList()

        if (candidates.isEmpty()) return false

        var emitted = false
        for (item in candidates) {
            val text = try {
                net.get(
                    item.url,
                    headers = mapOf("User-Agent" to UA_PC, "Referer" to "$mainUrl/"),
                    timeout = 15
                ).text
            } catch (e: Exception) {
                continue
            }
            if (!text.contains("#EXTM3U")) continue
            val audioLines = Regex("^#EXT-X-MEDIA:.*TYPE=AUDIO.*$", RegexOption.MULTILINE)
                .findAll(text).map { it.value }.toList()
            val italian = audioLines.isEmpty() || audioLines.any { line ->
                    Regex("LANGUAGE=\"(it|ita|italiano)", RegexOption.IGNORE_CASE).containsMatchIn(line) ||
                    Regex("NAME=\"[^\"]*(italian|italiano)", RegexOption.IGNORE_CASE).containsMatchIn(line)
            }
            val quality = qualityFromHeight(bestHeight(text))
            emit(
                callback = callback,
                url = item.url,
                label = "PaCC ${if (quality > 0) "${quality}p" else "HLS"} \u00B7 Server ${item.server} ${if (italian) "\uD83C\uDDEE\uD83C\uDDF9" else "\uD83C\uDF10"}",
                quality = quality,
                type = ExtractorLinkType.M3U8,
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to UA_PC, "Referer" to "$mainUrl/")
            )
            emitted = true
        }
        return emitted
    }

    private fun absolute(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value else mainUrl + value

    private fun parse(value: String): Playlist? {
        val path = pathRegex.find(value) ?: return null
        return Playlist(
            url = value,
            server = path.groupValues[1].toIntOrNull() ?: 1,
            kind = path.groupValues[2].lowercase(),
            id = path.groupValues[3],
            season = path.groupValues[4].toIntOrNull(),
            episode = path.groupValues[5].toIntOrNull()
        )
    }
}
