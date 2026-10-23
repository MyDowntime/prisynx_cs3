package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import prisynx.providers.core.AudioSync
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.VixMedia
import prisynx.providers.core.absUrl
import prisynx.providers.core.publishCombinedMaster
import prisynx.providers.core.fetchVixMaster
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.qualityLabel
import prisynx.providers.core.vixAudio
import prisynx.providers.core.vixDelayedTracks
import prisynx.providers.core.vixEnglishAudio
import prisynx.providers.core.vixMedia
import prisynx.providers.core.vixSubtitles

/**
 * MovyITA: Movy video (up to 4K) combined with the Italian VixSrc audio track.
 * The generated master is served from a loopback HTTP URL so CloudStream's
 * ExoPlayer can open it (its HLS stack does not support data: URIs).
 */
class MovyitaProvider : TmdbProvider("MovyITA", "https://www.movy.sx") {

    private val servers = listOf("miami", "boise", "atlanta", "orlando", "phoenix")
    private val qualities = listOf(2160, 1080, 720)

    private val movyUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private data class Source(val url: String, val quality: Int, val server: String)

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (meta.title.isBlank()) return false
        val site = mainUrl
        val headers = mapOf(
            "User-Agent" to movyUa,
            "Accept" to "*/*",
            "Origin" to site,
            "Referer" to "$site/"
        )
        val seed = getSeed(movyUa, site, ref.id) ?: return false
        val sources = mutableListOf<Source>()
        val subtitles = mutableListOf<VixMedia>()
        val have = HashSet<Int>()

        for ((index, server) in servers.withIndex()) {
            if (have.contains(2160) && have.contains(1080)) break
            val payload = fetchSources(ref, meta, seed, server, headers, index) ?: continue
            val data = decrypt(payload, seed, ref.id) ?: continue

            data.path("sources").forEach { node ->
                val rawUrl = node.path("url").asText("")
                if (rawUrl.isBlank() || !Regex("\\.m3u8(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)) {
                    return@forEach
                }
                val quality = qualityOf(node.path("quality").asText("")) ?: return@forEach
                if (quality !in qualities || quality in have) return@forEach
                val resolved = resolveVideo(rawUrl, quality, headers) ?: return@forEach
                if (resolved.second !in qualities || resolved.second in have) return@forEach
                have.add(resolved.second)
                sources.add(Source(resolved.first, resolved.second, server))
            }

            data.path("subtitles").forEach { node ->
                val url = node.path("url").asText("")
                if (url.isBlank() || subtitles.any { it.uri == url }) return@forEach
                val language = node.path("language").asText("")
                    .ifBlank { node.path("lang").asText("en") }
                val name = node.path("label").asText("")
                    .ifBlank { node.path("name").asText("") }
                    .ifBlank { language }
                subtitles.add(
                    VixMedia(
                        uri = url,
                        type = "SUBTITLES",
                        language = language,
                        name = name,
                        forced = false
                    )
                )
            }

            // One working 4K plus one 1080p source is enough.
            if (have.contains(2160) && have.contains(1080)) break
        }

        if (sources.isEmpty()) return false

        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        val ita = coroutineScope {
            val tracks = async { tracksFor(ref) }
            val info = async { AudioSync.lookup(meta.imdbId, ref.isTv, season, episode) }
            val status = async { AudioSync.status(meta.imdbId, ref.isTv, season, episode, "movy") }
            val lengths = sources.map { source -> async { AudioSync.playlistLength(source.url, headers) } }
            Ita(tracks.await(), info.await(), status.await(), lengths.awaitAll())
        }
        val tracks = ita.tracks
        val audio = tracks?.let { vixAudio(it.media) }
        val vixSubs = tracks?.let { vixSubtitles(it.media) }.orEmpty()
        val syncs = sources.mapIndexed { i, source ->
            // ToastFlix's per-provider verdict was measured on the miami/boise files only.
            val fallback = if (source.server in measuredServers) ita.status else null
            AudioSync.verdict(ita.info, ita.lengths[i], "movy", fallback)
        }

        if (audio != null && tracks != null && ita.info.jobs.status == null) {
            val unmeasured = sources.indices.filter { !syncs[it].measured && sources[it].quality >= 1080 }
            val server = unmeasured.firstOrNull()?.let { sources[it].server }
            if (server != null) {
                val picked = unmeasured.filter { sources[it].server == server }
                val length = picked.maxOf { ita.lengths[it] }.takeIf { it >= 30 } ?: ((meta.runtime ?: 90) * 60.0)
                AudioSync.report(
                    ita.info.mediaKey,
                    audio.uri,
                    vixEnglishAudio(tracks.media)?.uri,
                    tracks.headers,
                    listOf(
                        mapOf(
                            "provider" to "movy",
                            "server" to server,
                            "video_duration" to length,
                            "headers" to headers,
                            "renditions" to picked.map { mapOf("resolution" to sources[it].quality, "url" to sources[it].url) }
                        )
                    )
                )
            }
        }

        sources.indices.sortedByDescending { sources[it].quality }.forEach { i ->
            val source = sources[i]
            val sync = syncs[i]
            if (audio != null && tracks != null) {
                val delay = sync.delayMs
                val (itaAudio, itaSubs) = if (delay != null && delay != 0) {
                    vixDelayedTracks(audio, vixSubs, tracks.headers, delay) ?: (audio to vixSubs)
                } else {
                    audio to vixSubs
                }
                emit(
                    callback = callback,
                    url = publishCombinedMaster(source.url, source.quality, itaAudio, itaSubs + subtitles),
                    label = "MovyITA ${qualityLabel(source.quality)} ITA ${sync.badge}",
                    quality = source.quality,
                    type = ExtractorLinkType.M3U8,
                    referer = "$site/",
                    headers = headers
                )
            }
            emit(
                callback = callback,
                url = source.url,
                label = "MovyITA ${qualityLabel(source.quality)} solo video",
                quality = source.quality,
                type = ExtractorLinkType.M3U8,
                referer = "$site/",
                headers = headers
            )
        }
        return sources.isNotEmpty()
    }

    private class VixTracks(val media: List<VixMedia>, val headers: Map<String, String>)

    private class Ita(
        val tracks: VixTracks?,
        val info: AudioSync.Info,
        val status: AudioSync.Measure?,
        val lengths: List<Double>
    )

    private val measuredServers = setOf("miami", "boise")

    private suspend fun tracksFor(ref: MediaRef): VixTracks? {
        val master = fetchVixMaster(
            ref.id,
            ref.isTv,
            ref.season ?: 1,
            ref.episode ?: 1
        ) ?: return null
        return VixTracks(vixMedia(master.text, master.url), master.headers)
    }

    // ------------------------------------------------------------ Movy API

    @Volatile
    private var apiBase = "https://api.wecollege.net"

    private suspend fun getSeed(ua: String, site: String, mediaId: Int): String? {
        val headers = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Origin" to site,
            "Referer" to "$site/"
        )
        seedFrom(apiBase, mediaId, headers)?.let { return it }
        // The configured API host stopped answering: look for the new one on the site.
        val discovered = discoverApi(site)
        if (discovered.isBlank()) return null
        val seed = seedFrom(discovered, mediaId, headers) ?: return null
        apiBase = discovered
        return seed
    }

    private suspend fun seedFrom(
        api: String,
        mediaId: Int,
        headers: Map<String, String>
    ): String? {
        val text = try {
            net.get("$api/seed?mediaId=$mediaId", headers = headers, timeout = 15).text
        } catch (e: Exception) {
            return null
        }
        return runCatching {
            json.readTree(text).path("seed").asText("")
        }.getOrDefault("").takeIf { it.isNotBlank() }
    }

    private suspend fun discoverApi(site: String): String {
        val html = try {
            net.get("$site/", headers = mapOf("User-Agent" to movyUa, "Accept" to "text/html"), timeout = 15).text
        } catch (e: Exception) {
            return ""
        }
        return Regex("https://api\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .firstOrNull { !Regex("tmdb|themoviedb|google|cloudflare", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .orEmpty()
    }

    private suspend fun fetchSources(
        ref: MediaRef,
        meta: MediaMeta,
        seed: String,
        server: String,
        headers: Map<String, String>,
        index: Int
    ): String? {
        val params = linkedMapOf(
            "title" to meta.title,
            "mediaType" to if (ref.isTv) "tv" else "movie",
            "year" to (meta.year?.toString() ?: ""),
            "tmdbId" to ref.id.toString(),
            "imdbId" to (meta.imdbId ?: ""),
            "enc" to "2",
            "seed" to seed
        )
        if (ref.isTv) {
            params["seasonId"] = (ref.season ?: 1).toString()
            params["episodeId"] = (ref.episode ?: 1).toString()
        }
        val query = params.entries.joinToString("&") {
            "${prisynx.providers.core.enc(it.key)}=${prisynx.providers.core.enc(it.value)}"
        }
        return try {
            net.get(
                "$apiBase/$server/sources?$query",
                headers = headers,
                timeout = if (index < 2) 18 else 8
            ).text.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ playlist

    private suspend fun resolveVideo(url: String, quality: Int, headers: Map<String, String>): Pair<String, Int>? {
        val body = try {
            net.get(
                url,
                headers = headers + mapOf("Range" to "bytes=0-65535"),
                timeout = 15
            ).text
        } catch (e: Exception) {
            null
        } ?: return null
        if (body.contains("#EXT-X-STREAM-INF")) {
            val variants = mutableListOf<Pair<String, Int>>()
            val lines = body.split('\n')
            for (i in 0 until lines.size - 1) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
                val width = Regex("RESOLUTION=(\\d+)x\\d+").find(line)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val uri = lines[i + 1].trim()
                if (uri.isBlank() || uri.startsWith("#")) continue
                val q = when {
                    width >= 3200 -> 2160
                    width >= 2400 -> 1440
                    width >= 1800 -> 1080
                    width >= 1200 -> 720
                    width >= 800 -> 480
                    width > 0 -> 360
                    else -> quality
                }
                variants.add(absUrl(uri, url) to q)
            }
            if (variants.isEmpty()) return null
            val best = variants.filter { it.second <= quality }.maxByOrNull { it.second }
                ?: variants.minByOrNull { it.second } ?: return null
            return best
        }
        if (body.contains("#EXTINF")) return url to quality
        return null
    }

    private fun qualityOf(label: String): Int? {
        val value = label.lowercase()
        if (value.contains("4k")) return 2160
        val height = Regex("(\\d{3,4})p").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (height in qualities) height else null
    }

    // ------------------------------------------------------------ cipher

    private class State(val s: IntArray, var acc: Int, val assigned: BooleanArray)

    private fun uHash(value: Int): Int {
        var e = value
        e = e xor (e ushr 16)
        e *= 0x85ebca6b.toInt()
        e = e xor (e ushr 13)
        e *= 0xc2b2ae35.toInt()
        e = e xor (e ushr 16)
        return e
    }

    private fun rotl(value: Int, shift: Int): Int {
        val t = shift and 31
        return if (t == 0) value else (value shl t) or (value ushr (32 - t))
    }

    private fun initKey(seed: String, mediaId: Int): State {
        var h = 0x811c9dc5.toInt()
        for (ch in seed) {
            h = (h xor ch.code) * 0x1000193
        }
        var i = uHash(uHash(h) xor uHash(mediaId xor 0x9e3779b9.toInt()))
        val s = IntArray(61)
        val assigned = BooleanArray(61)
        for (e in 0 until 8) {
            val t = Integer.remainderUnsigned(i, 61)
            i = rotl(i + 0x9e3779b9.toInt(), 7 + (7 and e))
            s[t] = i xor uHash(i)
            assigned[t] = true
            i = uHash(i + t)
        }
        return State(s, uHash(0xa5a5a5a5.toInt() xor i), assigned)
    }

    private fun nextWord(state: State, round: Int): Int {
        val idx = Integer.remainderUnsigned(state.acc, 61)
        val inBounds = if (state.assigned[idx]) -1 else 0
        val s = state.s[idx] xor (0x9e3779b9.toInt() * (round + 1))
        var c = (state.acc xor s) or (state.acc and s and inBounds)
        c = rotl(c + state.acc, idx) xor rotl(state.acc, idx * 7)
        val acc = uHash(c + 0x9e3779b9.toInt())
        state.acc = acc
        state.s[idx] = acc
        state.assigned[idx] = true
        return acc
    }

    private fun decrypt(payload: String, seed: String, mediaId: Int): JsonNode? {
        val raw = base64ToBytes(payload.trim())
        if (raw.size < 5) return null
        val state = initKey(seed, mediaId)
        val out = ByteArray(raw.size)
        var i = 0
        var round = 0
        while (i < raw.size) {
            val word = nextWord(state, round)
            var b = 0
            while (b < 4 && i < raw.size) {
                out[i] = (raw[i].toInt() xor ((word ushr (8 * b)) and 255)).toByte()
                i++
                b++
            }
            round++
        }
        if (out[0] != 'm'.code.toByte() || out[1] != 'v'.code.toByte() ||
            out[2] != 'm'.code.toByte() || out[3] != '1'.code.toByte()
        ) {
            return null
        }
        val text = out.copyOfRange(4, out.size).toString(Charsets.UTF_8)
        return runCatching { json.readTree(text) }.getOrNull()
    }

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
}
