package prisynx.providers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.FormBody
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_MOBILE
import prisynx.providers.core.absUrl
import prisynx.providers.core.bestHeight
import prisynx.providers.core.decodeB64
import prisynx.providers.core.domainOf
import prisynx.providers.core.enc
import prisynx.providers.core.fetchDomains
import prisynx.providers.core.hexToBytes
import prisynx.providers.core.net
import prisynx.providers.core.normalizeTitle
import prisynx.providers.core.qualityFromHeight
import prisynx.providers.core.slugify
import prisynx.providers.core.unpackJs
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Guardoserie: WordPress-like catalogue, Uqload players, Abyss (mloadm) players
 * and legacy Loadm links (AES-128-CBC hex payloads).
 */
class GuardoserieProvider : TmdbProvider("Guardoserie", "https://guardoseries.cyou") {
    private val loadmKey = "kiemtienmua911ca"
    private val loadmIv = "1234567890oiuytr"

    private data class Candidate(val url: String, val title: String, val poster: String, val year: Int)

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (meta.title.isBlank()) return false
        val domain = try {
            fetchDomains("https://raw.githubusercontent.com/qwertyuiop8899/streamvix/main/config/domains.json")
                ?.domainOf("guardoserie", "guardoseries.cyou") ?: "guardoseries.cyou"
        } catch (e: Exception) {
            "guardoseries.cyou"
        }
        val base = "https://$domain"
        val isTv = ref.isTv
        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        val year = meta.year ?: 0
        val poster = posterFile(meta.posterUrl.orEmpty())
        val info = Info(meta.title, meta.originalTitle, normalizeTitle(meta.title), normalizeTitle(meta.originalTitle), year, poster)

        val titlePage = findTitlePage(base, info, isTv) ?: return false
        val page = if (isTv) {
            findEpisodeHtml(base, titlePage, season, episode)
        } else {
            val html = try {
                net.get(titlePage, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$base/"), timeout = 25).text
            } catch (e: Exception) {
                null
            }
            if (html != null && hasPlayers(html)) titlePage to html else null
        }

        if (page == null && isTv) {
            val slugs = listOf(slugify(meta.title), slugify(meta.originalTitle))
                .filter { it.isNotBlank() }.distinct()
            for (slug in slugs) {
                val direct = "$base/episodio/$slug-stagione-$season-episodio-$episode/"
                val html = try {
                    net.get(direct, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$base/"), timeout = 20).text
                } catch (e: Exception) {
                    null
                }
                if (html != null && hasPlayers(html)) {
                    return emitPlayers(html, direct, base, domain, isTv, subtitleCallback, callback)
                }
            }
            return false
        }
        if (page == null) return false
        return emitPlayers(page.second, page.first, base, domain, isTv, subtitleCallback, callback)
    }

    private data class Info(
        val title: String,
        val orig: String,
        val nTitle: String,
        val nOrig: String,
        val year: Int,
        val poster: String
    )

    private suspend fun emitPlayers(
        html: String,
        pageUrl: String,
        base: String,
        domain: String,
        isTv: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val players = extractPlayers(html)
        var emitted = false
        for (player in players) {
            val url = player.first
            if (Regex("uqload", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                emitted = extractUqload(url, pageUrl, callback) || emitted
            } else if (Regex("loadm\\.cam/?#", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                emitted = extractLoadm(url, domain, callback) || emitted
            } else if (Abyss.HOST_REGEX.containsMatchIn(url)) {
                emitted = extractAbyss(url, pageUrl, callback) || emitted
            }
        }
        return emitted
    }

    private fun posterFile(value: String): String =
        Regex("([A-Za-z0-9]+)\\.(?:jpe?g|png|webp)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1) ?: ""

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace("&#x27;", "'", true)
        .replace("&rsquo;", "'", true)
        .replace("&lsquo;", "'", true)
        .replace("&hellip;", "...", true)
        .replace("&nbsp;", " ", true)

    private fun parseSearch(html: String, base: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val blocks = html.split(Regex("<div[^>]*class=\"ml-item\"[^>]*>")).drop(1)
        for (block in blocks) {
            val chunk = block.take(4000)
            val href = Regex("<a[^>]*href=\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
            if (href.isNullOrBlank() || !href.startsWith(base)) continue
            val title = Regex("oldtitle=\"([^\"]*)\"").find(chunk)?.groupValues?.get(1)
                ?: Regex("<h2>([^<]*)</h2>").find(chunk)?.groupValues?.get(1)
                ?: ""
            val poster = posterFile(
                Regex("(?:data-original|data-src)=\"([^\"]*image\\.tmdb\\.org[^\"]*)\"").find(chunk)
                    ?.groupValues?.get(1).orEmpty()
            )
            val year = Regex("release-year/(\\d{4})").find(chunk)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            out.add(Candidate(href, decodeEntities(title), poster, year))
        }
        if (out.isEmpty() && (html.contains("id=\"player2\"") || html.contains("id=\"seasons\""))) {
            val canonical = Regex("<link[^>]*rel=\"canonical\"[^>]*href=\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1)
                ?: Regex("property=\"og:url\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            val ogTitle = Regex("property=\"og:title\" content=\"([^\"]+)\"").find(html)
                ?.groupValues?.get(1).orEmpty()
            if (canonical != null && !canonical.contains("?s=")) {
                out.add(
                    Candidate(
                        canonical,
                        ogTitle.replace(Regex("\\s*-\\s*Guardaserie\\s*$", RegexOption.IGNORE_CASE), ""),
                        "",
                        Regex("release-year/(\\d{4})").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return out
    }

    private fun scoreCandidate(c: Candidate, info: Info): Int {
        val t = normalizeTitle(c.title)
        var score = 0
        val parts = decodeEntities(c.title)
            .split(Regex("\\s+[-\\u2013\\u2014:|]\\s+|:\\s+"))
            .map { normalizeTitle(it) }
            .filter { it.isNotBlank() }
        val hits = parts.count { it == info.nTitle || (info.nOrig.isNotBlank() && it == info.nOrig) }
        if (t.isNotBlank() && (t == info.nTitle || (info.nOrig.isNotBlank() && t == info.nOrig))) {
            score += 5
        } else if (parts.size > 1 && hits == parts.size) {
            score += 5
        } else if (hits > 0) {
            score += 3
        } else {
            for (x in listOf(info.nTitle, info.nOrig)) {
                if (x.isBlank() || t.isBlank() || score >= 2) continue
                if (t.contains(x) || x.contains(t)) {
                    val ratio = minOf(t.length, x.length).toDouble() / maxOf(t.length, x.length)
                    if (ratio >= 0.6) score += 2
                }
            }
        }
        if (c.poster.isNotBlank() && c.poster == info.poster) score += 6
        if (c.year > 0 && info.year > 0) {
            when (kotlin.math.abs(c.year - info.year)) {
                0 -> score += 2
                1 -> score += 1
                else -> score -= 3
            }
        }
        return score
    }

    private suspend fun findTitlePage(base: String, info: Info, isTv: Boolean): String? {
        val queries = mutableListOf(normalizeTitle(info.title).split(' ').take(3).joinToString(" "))
        val second = normalizeTitle(info.orig).split(' ').take(3).joinToString(" ")
        if (second.isNotBlank() && second !in queries) queries.add(second)
        for (query in queries) {
            if (query.isBlank()) continue
            val url = "$base/?s=" + enc(query).replace("%20", "+")
            val html = try {
                net.get(url, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$base/"), timeout = 25).text
            } catch (e: Exception) {
                continue
            }
            val candidates = parseSearch(html, base).filter {
                if (isTv) it.url.contains("/serie/")
                else !Regex("/(serie|episodio)/").containsMatchIn(it.url) &&
                    it.url.replace(base, "").split('/').filter { p -> p.isNotBlank() }.size == 1
            }
            var best: Candidate? = null
            var bestScore = 0
            for (candidate in candidates) {
                val score = scoreCandidate(candidate, info)
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }
            if (best != null && bestScore >= 5) return best.url
        }
        return null
    }

    private suspend fun findEpisodeHtml(
        base: String,
        seriesUrl: String,
        season: Int,
        episode: Int
    ): Pair<String, String>? {
        val slug = Regex("/serie/([^/]+)").find(seriesUrl)?.groupValues?.get(1)
        val suffix = "-stagione-$season-episodio-$episode"
        if (!slug.isNullOrBlank()) {
            val direct = "$base/episodio/$slug$suffix/"
            val html = try {
                net.get(direct, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to seriesUrl), timeout = 20).text
            } catch (e: Exception) {
                null
            }
            if (html != null && hasPlayers(html)) return direct to html
        }
        val seriesHtml = try {
            net.get(seriesUrl, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$base/"), timeout = 20).text
        } catch (e: Exception) {
            return null
        }
        val href = Regex("href=\"([^\"]+$suffix)/?\"", RegexOption.IGNORE_CASE)
            .find(seriesHtml)?.groupValues?.get(1) ?: return null
        return try {
            val html = net.get(href, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to seriesUrl), timeout = 20).text
            if (hasPlayers(html)) href to html else null
        } catch (e: Exception) {
            null
        }
    }

    private fun hasPlayers(html: String): Boolean =
        html.contains("id=\"player2\"") || html.contains("<iframe", true)

    private fun iframeUrl(tag: String): String? {
        val match = Regex("\\s(?:data-src|src)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(tag) ?: return null
        val url = decodeEntities(match.groupValues[1])
        return if (url.startsWith("data:")) null else url
    }

    private fun extractPlayers(html: String): List<Pair<String, String>> {
        val labels = HashMap<String, String>()
        Regex("href=\"#tab(\\d+)\"[^>]*>([^<]*)<").findAll(html).forEach {
            labels[it.groupValues[1]] = decodeEntities(it.groupValues[2]).trim()
        }
        val players = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        val tabRegex = Regex(
            "<div id=\"tab(\\d+)\"[^>]*>([\\s\\S]*?)(?=<div id=\"tab\\d+\"|<div id=\"bar-player\"|<div class=\"player_nav\"|$)",
            RegexOption.IGNORE_CASE
        )
        for (tab in tabRegex.findAll(html)) {
            val iframe = Regex("<iframe[^>]*>", RegexOption.IGNORE_CASE).find(tab.groupValues[2])?.value
            val url = iframe?.let { iframeUrl(it) }
            if (!url.isNullOrBlank() && seen.add(url)) {
                players.add(url to (labels[tab.groupValues[1]] ?: ""))
            }
        }
        if (players.isEmpty()) {
            Regex("<iframe[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
                val url = iframeUrl(match.value)
                if (!url.isNullOrBlank() && seen.add(url) &&
                    Regex("uqload|loadm|mload|guardabest|vixsrc", RegexOption.IGNORE_CASE).containsMatchIn(url)
                ) {
                    players.add(url to "")
                }
            }
        }
        return players
    }

    private suspend fun extractUqload(
        playerUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = Regex("^(?:https?:)?//([^/]+)").find(playerUrl)?.groupValues?.get(1) ?: return false
        val code = Regex("/(?:e|embed-)/?([A-Za-z0-9]+)").find(playerUrl)?.groupValues?.get(1) ?: return false
        val origin = "https://$host"
        val text = try {
            net.post(
                "$origin/dl",
                headers = mapOf(
                    "User-Agent" to UA_MOBILE,
                    "Referer" to "$origin/e/$code",
                    "Origin" to origin,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                requestBody = FormBody.Builder()
                    .add("op", "embed")
                    .add("file_code", code)
                    .add("auto", "1")
                    .add("referer", pageUrl)
                    .build(),
                timeout = 20
            ).text
        } catch (e: Exception) {
            return false
        }

        var src: String? = null
        val packed = Regex("\\}\\(\\s*'([\\s\\S]*?)'\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*'([\\s\\S]*?)'\\.split\\('\\|'\\)")
            .find(text)
        if (packed != null) {
            val unpacked = unpackJs(
                packed.groupValues[1],
                packed.groupValues[2].toIntOrNull() ?: 62,
                packed.groupValues[3].toIntOrNull() ?: 0,
                packed.groupValues[4].split('|')
            )
            src = Regex("file\\s*:\\s*[\"']([^\"']+)").find(unpacked)?.groupValues?.get(1)
        }
        if (src.isNullOrBlank()) {
            src = Regex("(?:sources|file|src)\\s*[:=]\\s*\\[?\\s*\\{?\\s*(?:file\\s*:\\s*)?[\"']([^\"']+(?:m3u8|mp4)[^\"']*)")
                .find(text)?.groupValues?.get(1)
        }
        if (src.isNullOrBlank()) return false
        if (src.startsWith("//")) src = "https:$src"
        if (src.contains("/static/")) return false

        val headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$origin/", "Origin" to origin)
        return emitStream(src, headers, "Uqload", callback)
    }

    private suspend fun extractLoadm(
        playerUrl: String,
        domain: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = playerUrl.split('#')
        if (parts.size < 2 || parts[1].isBlank()) return false
        val base = parts[0].trimEnd('/') + "/"
        val id = parts[1].trim()
        val api = "${base}api/v1/video?id=${enc(id)}&w=2560&h=1440&r=${enc(domain)}"
        val cipherText = try {
            net.get(
                api,
                headers = mapOf(
                    "User-Agent" to UA_MOBILE,
                    "Referer" to base,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                timeout = 20
            ).text.trim()
        } catch (e: Exception) {
            return false
        }
        val decrypted = try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(loadmKey.toByteArray(), "AES"),
                IvParameterSpec(loadmIv.toByteArray())
            )
            val raw = cipher.doFinal(hexToBytes(cipherText))
            val pad = raw.last().toInt() and 0xff
            val end = if (pad in 1..16) raw.size - pad else raw.size
            String(raw, 0, end.coerceAtLeast(0), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val jsonStart = decrypted.indexOf('{')
        val jsonEnd = decrypted.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return false
        val node = try {
            prisynx.providers.core.json.readTree(decrypted.substring(jsonStart, jsonEnd + 1))
        } catch (e: Exception) {
            return false
        }
        val headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to base)
        for (key in listOf("source", "cfNative")) {
            val url = node.path(key).asText("").trim()
            if (!url.startsWith("http")) continue
            if (emitStream(url, headers, "Loadm", callback)) return true
        }
        return false
    }

    private suspend fun extractAbyss(
        playerUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = if (playerUrl.startsWith("//")) "https:$playerUrl" else playerUrl
        val response = try {
            net.get(url, headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to pageUrl), timeout = 20)
        } catch (e: Exception) {
            return false
        }
        val host = Regex("^(https?://[^/]+)").find(response.url)?.groupValues?.get(1)
            ?: Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1)
            ?: return false
        val sources = try {
            Abyss.parse(response.text)
        } catch (e: Exception) {
            emptyList()
        }
        val headers = mapOf("User-Agent" to UA_MOBILE, "Referer" to "$host/", "Origin" to host)
        for (source in sources) {
            emit(
                callback = callback,
                url = source.url,
                label = "Guardoserie Abyss ${if (source.quality > 0) "${source.quality}p" else "MP4"} \uD83C\uDDEE\uD83C\uDDF9",
                quality = source.quality,
                type = ExtractorLinkType.VIDEO,
                referer = "$host/",
                headers = headers
            )
        }
        return sources.isNotEmpty()
    }

    private suspend fun emitStream(
        url: String,
        headers: Map<String, String>,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = try {
            net.get(url, headers = headers, timeout = 15).text
        } catch (e: Exception) {
            return false
        }
        if (!text.contains("#EXTM3U")) return false
        val quality = qualityFromHeight(bestHeight(text))
        emit(
            callback = callback,
            url = url,
            label = "Guardoserie $label ${if (quality > 0) "${quality}p" else "HLS"} \uD83C\uDDEE\uD83C\uDDF9",
            quality = quality,
            type = ExtractorLinkType.M3U8,
            referer = headers["Referer"],
            headers = headers
        )
        return true
    }
}
