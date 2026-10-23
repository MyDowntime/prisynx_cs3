package prisynx.providers

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Keeps an HLS session alive when every playlist/segment URL carries a short
 * lived token. Each URL seen is mapped to its structural position inside the
 * playlist tree (e.g. [variant 1080p, segment 42]); when the CDN answers 401/403
 * the master URL is renewed and the same position is looked up again in the
 * fresh playlists, so the player keeps requesting its old URLs transparently.
 */
internal class HlsTokenRenewer(
    initialUrl: String,
    private val renew: () -> String?,
    private val fetch: (HttpUrl) -> Response
) : Interceptor {
    private val lock = Any()
    private val positionOf = HashMap<HttpUrl, List<String>>()
    private val current = HashMap<List<String>, HttpUrl>()
    private val playlists = HashSet<List<String>>()
    private var generation = 0
    private var lastRenewal = 0L

    init {
        val root = initialUrl.toHttpUrl()
        positionOf[root] = emptyList()
        current[emptyList()] = root
        playlists.add(emptyList())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val (position, target, gen) = synchronized(lock) {
            val pos = positionOf[request.url] ?: return chain.proceed(request)
            Triple(pos, current[pos] ?: request.url, generation)
        }
        var response = chain.proceed(request.newBuilder().url(target).build())
        if (response.code == 401 || response.code == 403) {
            val fresh = synchronized(lock) {
                try {
                    locateAfterRenewal(position, gen)
                } catch (e: Exception) {
                    null
                }
            }
            if (fresh != null) {
                response.close()
                response = chain.proceed(request.newBuilder().url(fresh).header("Cache-Control", "no-cache").build())
            }
        }
        if (response.isSuccessful && position in playlists) {
            val text = try {
                response.peekBody(MAX_PLAYLIST).string()
            } catch (e: IOException) {
                null
            }
            if (text != null && text.trimStart().startsWith("#EXTM3U")) {
                synchronized(lock) { learn(position, response.request.url, text) }
            }
        }
        return response
    }

    private fun locateAfterRenewal(position: List<String>, seenGeneration: Int): HttpUrl? {
        if (seenGeneration == generation) {
            val now = System.currentTimeMillis()
            if (now - lastRenewal < 5_000L) return null
            lastRenewal = now
            val root = renew()?.toHttpUrl() ?: return null
            generation++
            current.clear()
            current[emptyList()] = root
            positionOf[root] = emptyList()
        }
        return locate(position, 0)
    }

    private fun locate(position: List<String>, depth: Int): HttpUrl {
        current[position]?.let { return it }
        if (position.isEmpty() || depth > 8) throw IOException("HLS position not found")
        val parent = position.dropLast(1)
        val parentUrl = locate(parent, depth + 1)
        fetch(parentUrl).use { response ->
            if (!response.isSuccessful) throw IOException("HLS playlist HTTP ${response.code}")
            learn(parent, response.request.url, response.body?.string().orEmpty())
        }
        return current[position] ?: throw IOException("HLS position missing after renewal")
    }

    private fun learn(parent: List<String>, url: HttpUrl, text: String) {
        current[parent] = url
        positionOf[url] = parent
        for ((key, childUrl, isPlaylist) in references(url, text)) {
            val child = parent + key
            current[child] = childUrl
            positionOf[childUrl] = child
            if (isPlaylist) playlists.add(child)
        }
    }

    private fun references(base: HttpUrl, text: String): List<Triple<String, HttpUrl, Boolean>> {
        val out = mutableListOf<Triple<String, HttpUrl, Boolean>>()
        val attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        fun attrs(line: String) = attr.findAll(line.substringAfter(':'))
            .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }
        fun identity(tag: String, a: Map<String, String>) =
            tag + a.filterKeys { it != "URI" }.toSortedMap().entries.joinToString(",")
        fun add(key: String, uri: String?, playlist: Boolean) {
            val resolved = uri?.let { base.resolve(it) } ?: return
            out.add(Triple(key, resolved, playlist))
        }
        var sequence = 0L
        var variant: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-STREAM-INF:") -> variant = identity("variant:", attrs(line))
                line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> {
                    val a = attrs(line)
                    add(identity(line.substringBefore(':'), a), a["URI"], true)
                }
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-MAP:") -> {
                    add("${line.substringBefore(':')}:$sequence", attrs(line)["URI"], false)
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    add(variant ?: "segment:$sequence", line, variant != null)
                    if (variant == null) sequence++
                    variant = null
                }
            }
        }
        return out
    }

    private companion object {
        const val MAX_PLAYLIST = 4L * 1024 * 1024
    }
}
