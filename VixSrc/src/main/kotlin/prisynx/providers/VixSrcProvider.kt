package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.absUrl
import prisynx.providers.core.bestHeight
import prisynx.providers.core.enc
import prisynx.providers.core.fetchDomains
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromHeight
import prisynx.providers.core.urlOf

/**
 * VixSrc: the site exposes a JSON payload with an embed page that contains the
 * signed HLS master playlist (multi-audio, Italian included).
 */
class VixSrcProvider : TmdbProvider("VixSrc", "https://vixsrc.to") {
    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val configured = try {
            fetchDomains()?.urlOf("vixsrc", mainUrl) ?: mainUrl
        } catch (e: Exception) {
            mainUrl
        }
        val bases = linkedSetOf(configured.trimEnd('/'), mainUrl.trimEnd('/'))
        val ids = linkedSetOf(ref.id.toString())
        meta.imdbId?.takeIf { it.startsWith("tt") }?.let { ids.add(it) }

        for (base in bases) {
            for (id in ids) {
                val apiPath = if (ref.isTv) {
                    "/api/tv/$id/${ref.season ?: 1}/${ref.episode ?: 1}"
                } else {
                    "/api/movie/$id"
                }
                val payload = try {
                    net.get(
                        "$base$apiPath?lang=it&_=${System.currentTimeMillis()}",
                        headers = mapOf(
                            "User-Agent" to UA_PC,
                            "Referer" to "$base/",
                            "Accept" to "application/json",
                            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8"
                        ),
                        timeout = 15
                    ).text
                } catch (e: Exception) {
                    null
                } ?: continue
                val embedPath = parseSrc(payload) ?: continue
                val embedUrl = absUrl(embedPath, "$base/")
                val html = try {
                    net.get(
                        embedUrl,
                        headers = mapOf("User-Agent" to UA_PC, "Referer" to "$base/"),
                        timeout = 15
                    ).text
                } catch (e: Exception) {
                    continue
                }
                val master = extractPlaylist(html, base) ?: continue

                val playHeaders = mapOf(
                    "User-Agent" to UA_PC,
                    "Referer" to "$base/",
                    "Origin" to base
                )
                // Do not reject the stream only because the preflight request
                // fails: some devices time out on the master while the player
                // can still open it. Use it as a quality hint when available.
                val masterText = try {
                    net.get(master, headers = playHeaders, timeout = 12).text
                } catch (e: Exception) {
                    ""
                }
                val fhd = Regex("canPlayFHD\\s*=\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(html)
                val height = bestHeight(masterText)
                val quality = qualityFromHeight(height).takeIf { it > 0 }
                    ?: if (fhd) 1080 else 720
                emit(
                    callback = callback,
                    url = master,
                    label = "VixSrc ${quality}p \uD83C\uDDEE\uD83C\uDDF9",
                    quality = quality,
                    type = ExtractorLinkType.M3U8,
                    referer = "$base/",
                    headers = playHeaders
                )
                return true
            }
        }
        return false
    }

    private fun parseSrc(payload: String): String? {
        val node: JsonNode? = runCatching { json.readTree(payload) }.getOrNull()
        val fromJson = node?.path("src")?.asText("")?.takeIf { it.isNotBlank() }
        val raw = fromJson ?: Regex("\"src\"\\s*:\\s*\"([^\"]+)\"")
            .find(payload)?.groupValues?.get(1)
        return raw?.replace("\\u0026", "&")?.replace("\\/", "/")?.takeIf { it.isNotBlank() }
    }

    private fun extractPlaylist(html: String, base: String): String? {
        val assignment = Regex("\\bwindow\\.masterPlaylist\\s*=\\s*\\{").find(html)
        val objectStart = assignment?.let { html.indexOf('{', it.range.first) } ?: -1
        val master = if (objectStart >= 0) objectAt(html, objectStart).orEmpty() else ""
        val source = master.ifBlank { html }

        val url = Regex("\\burl[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
            .find(source)?.groupValues?.get(1) ?: return null
        val token = Regex("\\btoken[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
            .find(source)?.groupValues?.get(1) ?: return null
        val expires = Regex("\\bexpires[\"']?\\s*:\\s*[\"']?(\\d+)")
            .find(source)?.groupValues?.get(1) ?: return null
        val asn = Regex("\\basn[\"']?\\s*:\\s*[\"']([^\"']*)[\"']")
            .find(source)?.groupValues?.get(1).orEmpty()

        var playlist = absUrl(url.replace("\\/", "/"), base).substringBefore("#")
        val queryStart = playlist.indexOf('?')
        var path = if (queryStart >= 0) playlist.substring(0, queryStart) else playlist
        val query = if (queryStart >= 0) playlist.substring(queryStart + 1) else ""
        if (!path.endsWith(".m3u8", true)) path = path.trimEnd('/') + ".m3u8"

        val known = setOf("token", "expires", "h", "asn")
        val retained = query.split('&').filter { part ->
            part.isNotBlank() && part.substringBefore('=').lowercase() !in known
        }
        val signed = mutableListOf("token=${enc(token)}", "expires=${enc(expires)}")
        if (asn.isNotBlank()) signed.add("asn=${enc(asn)}")
        if (Regex("canPlayFHD\\s*=\\s*true", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)
        ) {
            signed.add("h=1")
        }
        return path + "?" + (retained + signed).joinToString("&")
    }

    private fun objectAt(source: String, start: Int): String? {
        var depth = 0
        var quote = ' '
        var i = start
        while (i < source.length) {
            val c = source[i]
            if (quote != ' ') {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) quote = ' '
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                val end = source.indexOf('\n', i + 2)
                if (end < 0) return null
                i = end
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                val end = source.indexOf("*/", i + 2)
                if (end < 0) return null
                i = end + 2
                continue
            } else if (c == '{') {
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0) return source.substring(start, i + 1)
            }
            i++
        }
        return null
    }
}
