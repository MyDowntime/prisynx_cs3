package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.absUrl
import prisynx.providers.core.publishCombinedMaster
import prisynx.providers.core.enc
import prisynx.providers.core.fetchVixMaster
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.qualityLabel
import prisynx.providers.core.vixAudio
import prisynx.providers.core.vixMedia
import prisynx.providers.core.vixSubtitles

/**
 * DualSync: 4K/1080p video from Cinejoy (decoded through enc-dec.app) with the
 * Italian VixSrc audio track, delivered as one HLS master. The master is
 * served from loopback HTTP because CloudStream's HLS stack has no data: support.
 * A plain video-only rendition is emitted as a fallback.
 */
class DualSyncProvider : TmdbProvider("DualSync", "https://cinejoy.pk") {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private data class Encode(
        val server: String,
        val headers: Map<String, String>,
        val renditions: Map<Int, String>
    )

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (meta.title.isBlank() && meta.originalTitle.isBlank()) return false
        val headers = cinejoyHeaders()
        val servers = getServers(headers)
        if (servers.isEmpty()) return false

        val encodes = mutableListOf<Encode>()
        for (server in servers) {
            if (encodes.any { it.renditions.containsKey(2160) } &&
                encodes.any { it.renditions.containsKey(1080) }
            ) break
            val encode = openServer(server, ref, meta, headers) ?: continue
            if (encode.renditions.isEmpty()) continue
            encodes.add(encode)
        }
        if (encodes.isEmpty()) return false

        val master = fetchVixMaster(
            ref.id,
            ref.isTv,
            ref.season ?: 1,
            ref.episode ?: 1
        )
        val tracks = master?.let { vixMedia(it.text, it.url) }.orEmpty()
        val audio = vixAudio(tracks)
        val subs = vixSubtitles(tracks)

        var emitted = 0
        val seen = HashSet<Int>()
        for (encode in encodes) {
            for (quality in listOf(2160, 1080)) {
                val video = encode.renditions[quality] ?: continue
                if (!seen.add(quality)) continue
                if (audio != null) {
                    emit(
                        callback = callback,
                        url = publishCombinedMaster(video, quality, audio, subs),
                        label = "DualSync ${qualityLabel(quality)} ITA",
                        quality = quality,
                        type = ExtractorLinkType.M3U8,
                        referer = "https://cinejoy.pk/",
                        headers = encode.headers
                    )
                }
                emit(
                    callback = callback,
                    url = video,
                    label = "DualSync ${qualityLabel(quality)} solo video",
                    quality = quality,
                    type = ExtractorLinkType.M3U8,
                    referer = "https://cinejoy.pk/",
                    headers = encode.headers
                )
                emitted++
            }
        }
        return emitted > 0
    }

    // ------------------------------------------------------------ Cinejoy

    private fun pct(value: String): String = enc(value).replace("+", "%20")

    private fun cinejoyHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to "https://cinejoy.pk",
        "Referer" to "https://cinejoy.pk/"
    )

    private suspend fun getServers(headers: Map<String, String>): List<String> {
        val root = try {
            json.readTree(
                net.get("https://api.wing.st/servers", headers = headers, timeout = 15).text
            )
        } catch (e: Exception) {
            return emptyList()
        }
        return root.path("servers").mapNotNull { node ->
            if (node.path("status").asText("") != "ok") return@mapNotNull null
            val name = node.path("name").asText("")
            if (name.isBlank()) return@mapNotNull null
            val is4k = node.path("4k").asBoolean(false) == true ||
                Regex("4k", RegexOption.IGNORE_CASE).containsMatchIn(
                    "$name ${node.path("description").asText("")}"
                )
            name to is4k
        }.sortedByDescending { it.second }.map { it.first }
    }

    private suspend fun openServer(
        server: String,
        ref: MediaRef,
        meta: MediaMeta,
        headers: Map<String, String>
    ): Encode? {
        val params = linkedMapOf(
            "title" to (meta.originalTitle.ifBlank { meta.title }),
            "type" to if (ref.isTv) "series" else "movie",
            "year" to (meta.year?.toString() ?: ""),
            "imdb" to (meta.imdbId ?: ""),
            "tmdb" to ref.id.toString(),
            "server" to server
        )
        if (ref.isTv) {
            params["season"] = (ref.season ?: 1).toString()
            params["episode"] = (ref.episode ?: 1).toString()
        }
        val target = "https://api.wing.st/?" + params.entries.joinToString("&") {
            "${it.key}=${pct(it.value)}"
        }
        val sealed = encCinejoy(target) ?: return null
        val body = base64ToBytes(sealed.path("data").asText(""))
        if (body.isEmpty()) return null
        val answer = try {
            net.post(
                "https://api.wing.st/g",
                headers = headers + mapOf("Content-Type" to "text/plain;charset=UTF-8"),
                data = null,
                requestBody = body.toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull()),
                timeout = 20
            ).body?.bytes() ?: ByteArray(0)
        } catch (e: Exception) {
            return null
        }
        if (answer.isEmpty()) return null
        val opened = decCinejoy(
            base64Url(answer),
            sealed.path("state")
        ) ?: return null

        val renditions = linkedMapOf<Int, String>()
        opened.path("data").path("stream").forEach { stream ->
            val playlist = stream.path("playlist").asText("")
            if (playlist.isBlank()) return@forEach
            resolvePlaylist(playlist, headers)?.forEach { (quality, url) ->
                if (!renditions.containsKey(quality)) renditions[quality] = url
            }
        }
        if (renditions.isEmpty()) return null
        return Encode(server, headers, renditions)
    }

    private suspend fun encCinejoy(url: String): JsonNode? {
        val text = try {
            net.get(
                "https://enc-dec.app/api/enc-cinejoy?url=${enc(url)}",
                headers = mapOf("Accept" to "application/json"),
                timeout = 20
            ).text
        } catch (e: Exception) {
            return null
        }
        val root = runCatching { json.readTree(text) }.getOrNull() ?: return null
        if (root.path("status").asInt(0) != 200) return null
        return root.path("result").takeIf { it.has("data") && it.has("state") }
    }

    private suspend fun decCinejoy(text: String, state: JsonNode): JsonNode? {
        val body = json.writeValueAsString(mapOf("text" to text, "state" to state))
        val response = try {
            net.post(
                "https://enc-dec.app/api/dec-cinejoy",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                data = null,
                requestBody = body.toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 20
            ).text
        } catch (e: Exception) {
            return null
        }
        val root = runCatching { json.readTree(response) }.getOrNull() ?: return null
        if (root.path("status").asInt(0) != 200) return null
        return root.path("result")
    }

    private suspend fun resolvePlaylist(
        url: String,
        headers: Map<String, String>
    ): Map<Int, String>? {
        val text = try {
            net.get(url, headers = headers, timeout = 20).text
        } catch (e: Exception) {
            return null
        }
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        val found = linkedMapOf<Int, Pair<Int, String>>()
        val lines = text.split('\n')
        for (i in 0 until lines.size - 1) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val width = Regex("RESOLUTION=(\\d+)x\\d+").find(line)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val quality = when {
                width >= 3200 -> 2160
                width >= 1800 -> 1080
                else -> continue
            }
            val uri = lines[i + 1].trim()
            if (uri.isBlank() || uri.startsWith("#")) continue
            val previous = found[quality]
            if (previous == null || bandwidth > previous.first) {
                found[quality] = bandwidth to absUrl(uri, url)
            }
        }
        if (found.isEmpty()) return null
        return found.mapValues { it.value.second }
    }

    // ------------------------------------------------------------ base64

    private fun base64ToBytes(value: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = value.replace('-', '+').replace('_', '/').filter { it in alphabet }
        val out = ByteArray(clean.length * 3 / 4 + 3)
        var buf = 0
        var bits = 0
        var n = 0
        for (ch in clean) {
            buf = ((buf shl 6) or alphabet.indexOf(ch)) and 0xffffff
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = ((buf ushr bits) and 255).toByte()
            }
        }
        return out.copyOf(n)
    }

    private fun base64Url(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 255) shl 16) or
                ((bytes[i + 1].toInt() and 255) shl 8) or
                (bytes[i + 2].toInt() and 255)
            out.append(alphabet[(n ushr 18) and 63])
            out.append(alphabet[(n ushr 12) and 63])
            out.append(alphabet[(n ushr 6) and 63])
            out.append(alphabet[n and 63])
            i += 3
        }
        if (bytes.size - i == 1) {
            val n = (bytes[i].toInt() and 255) shl 16
            out.append(alphabet[(n ushr 18) and 63])
            out.append(alphabet[(n ushr 12) and 63])
            out.append("==")
        } else if (bytes.size - i == 2) {
            val n = ((bytes[i].toInt() and 255) shl 16) or
                ((bytes[i + 1].toInt() and 255) shl 8)
            out.append(alphabet[(n ushr 18) and 63])
            out.append(alphabet[(n ushr 12) and 63])
            out.append(alphabet[(n ushr 6) and 63])
            out.append("=")
        }
        return out.toString().replace('+', '-').replace('/', '_').trimEnd('=')
    }
}
