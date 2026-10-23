package prisynx.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.Request
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.bestHeight
import prisynx.providers.core.net
import prisynx.providers.core.qualityFromHeight
import java.util.concurrent.TimeUnit

/**
 * VidxGo (v.vidxgo.co): IMDb based. Movies expose an HTML player whose m3u8 is
 * XOR+base64 obfuscated; episodes answer JSON {"url": ...}. Playlist tokens
 * expire after a few minutes, so [interceptorFor] renews them during playback.
 */
class VidxGoProvider : TmdbProvider("VidxGo", VidxGo.BASE) {
    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val imdb = meta.imdbId?.removePrefix("tt")?.takeIf { it.isNotBlank() } ?: return false
        val target = if (ref.isTv) {
            "${VidxGo.BASE}/t/$imdb/${ref.season ?: 1}/${ref.episode ?: 1}"
        } else {
            "${VidxGo.BASE}/$imdb"
        }
        val m3u8 = VidxGo.resolvePlaylist(target) ?: return false
        val quality = try {
            qualityFromHeight(bestHeight(net.get(m3u8, headers = VidxGo.MEDIA_HEADERS, timeout = 15).text))
        } catch (e: Exception) {
            0
        }
        callback(
            newExtractorLink(
                source = name,
                name = "VidxGo ${if (quality > 0) "${quality}p" else "HLS"}",
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = quality
                this.referer = "${VidxGo.BASE}/"
                this.headers = VidxGo.MEDIA_HEADERS
                this.extractorData = VidxGo.DATA_PREFIX + target
            }
        )
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        VidxGo.interceptorFor(extractorLink)
}

object VidxGo {
    const val BASE = "https://v.vidxgo.co"
    const val DATA_PREFIX = "prisynx:vidxgo:"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"

    val MEDIA_HEADERS = mapOf(
        "User-Agent" to UA,
        "Accept" to "*/*",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to BASE,
        "Referer" to "$BASE/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "DNT" to "1"
    )

    private fun pageHeaders(episode: Boolean) = mapOf(
        "User-Agent" to UA,
        "Accept" to if (episode) "application/json, */*" else
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$BASE/",
        "Sec-Fetch-Dest" to if (episode) "empty" else "iframe",
        "Sec-Fetch-Mode" to if (episode) "cors" else "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "DNT" to "1"
    )

    suspend fun resolvePlaylist(target: String): String? {
        val response = try {
            net.get(target, headers = pageHeaders(target.contains("/t/")), timeout = 15, cacheTime = 0)
        } catch (e: Exception) {
            return null
        }
        if (response.code !in 200..299) return null
        val body = response.text
        return (fromJson(body) ?: fromHtml(body))?.takeIf { it.startsWith("http") }
    }

    private fun clean(url: String) = url.replace("\\/", "/").replace("\\", "")

    private fun fromJson(body: String): String? =
        Regex("""["']url["']\s*:\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
            ?.let(::clean)?.takeIf { it.contains(".m3u8", true) }

    private fun fromHtml(html: String): String? {
        val scripts = Regex("""<script\b[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }
        for (script in scripts) {
            val encoded = Regex("""atob\(\s*["']([^"']+)["']\s*\)""").find(script)?.groupValues?.get(1)
                ?: continue
            val raw = try {
                android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                continue
            }
            for (key in Regex("""var\s+\w+\s*=\s*["']([^"']+)["']""").findAll(script).map { it.groupValues[1] }) {
                val decoded = String(
                    ByteArray(raw.size) { i -> (raw[i].toInt() xor key[i % key.length].code).toByte() },
                    Charsets.UTF_8
                )
                val url = Regex("""currentSrc\s*=\s*["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
                    ?: Regex("""(https?:[^\s"']+\.m3u8[^\s"']*)""").find(decoded)?.groupValues?.get(1)
                if (!url.isNullOrBlank()) return clean(url)
            }
        }
        return null
    }

    fun interceptorFor(link: ExtractorLink): Interceptor? {
        val target = link.extractorData?.takeIf { it.startsWith(DATA_PREFIX) }?.removePrefix(DATA_PREFIX)
            ?: return null
        val client = app.baseClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        return try {
            HlsTokenRenewer(
                initialUrl = link.url,
                renew = { runBlocking { withTimeout(15_000L) { resolvePlaylist(target) } } },
                fetch = { url ->
                    val request = Request.Builder().url(url)
                    link.headers.forEach { (k, v) -> request.header(k, v) }
                    client.newCall(request.header("Cache-Control", "no-cache").build()).execute()
                }
            )
        } catch (e: Exception) {
            null
        }
    }
}
