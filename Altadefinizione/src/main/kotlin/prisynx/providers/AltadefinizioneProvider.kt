package prisynx.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_MOBILE
import prisynx.providers.core.fetchDomains
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromText
import prisynx.providers.core.urlOf

/**
 * Altadefinizione: /api/player-sources returns MP4 CDN edges tied to the
 * requesting IP. Cookies are shared through the realbestia domains config.
 */
class AltadefinizioneProvider : TmdbProvider("Altadefinizione", "https://altastreaming.duckdns.org") {
    private val ownCookie =
        "sid=63eb86ff548cc0d81c89eb328993d91a3e776a7f2ea9d0d65d2d975462bd01d3"

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val config = try {
            fetchDomains()
        } catch (e: Exception) {
            null
        }
        val base = config?.urlOf("ADS", mainUrl) ?: mainUrl
        val shared = config?.path("ADS_COOKIE")?.asText("")?.trim().orEmpty()
        val cookies = listOf(ownCookie, shared).filter { it.isNotBlank() }.distinct()

        val api = if (ref.isTv) {
            "$base/api/player-sources/tv/${ref.id}/${ref.season ?: 1}/${ref.episode ?: 1}"
        } else {
            "$base/api/player-sources/movie/${ref.id}"
        }
        val headers = mapOf(
            "User-Agent" to UA_MOBILE,
            "Referer" to "$base/",
            "Accept" to "application/json"
        )

        var data: com.fasterxml.jackson.databind.JsonNode? = null
        for (cookie in cookies) {
            data = try {
                val body = net.get(api, headers = headers + mapOf("Cookie" to cookie), timeout = 25).text
                prisynx.providers.core.json.readTree(body)
            } catch (e: Exception) {
                null
            }
            if (data != null && data!!.path("sources").size() > 0) break
        }
        val root = data ?: return false
        if (root.path("unavailable").asBoolean(false) || root.path("gate").asText("").isNotBlank()) {
            return false
        }

        val sources = root.path("sources")
            .filter { it.path("provider").asText("") == "cdn" }
            .filter { it.path("url").asText("").startsWith("https://") }
            .sortedBy { it.path("priority").asInt(99) }

        val seenHosts = HashSet<String>()
        var emitted = 0
        for (source in sources) {
            if (emitted >= 2) break
            val url = source.path("url").asText("")
            val host = Regex("^https://([^/]+)").find(url)?.groupValues?.get(1) ?: ""
            if (host.isBlank() || !seenHosts.add(host)) continue
            val quality = qualityFromText(source.path("quality").asText("720p"))
            val region = source.path("region").asText("")
            emit(
                callback = callback,
                url = url,
                label = "Altadefinizione ${if (quality > 0) "${quality}p" else "HD"} " +
                    "${if (emitted > 0) "\u00B7 alt" else ""} " +
                    (if (region.isNotBlank()) "\u00B7 ${region.uppercase()}" else "") +
                    " \uD83C\uDDEE\uD83C\uDDF9",
                quality = quality,
                type = ExtractorLinkType.VIDEO,
                referer = "$base/",
                headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$base/")
            )
            emitted++
        }
        return emitted > 0
    }
}
