package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.UA_PC
import prisynx.providers.core.decodeHtmlEntities
import prisynx.providers.core.enc
import prisynx.providers.core.json
import prisynx.providers.core.net
import prisynx.providers.core.qualityLabel
import prisynx.providers.core.resolveUprot
import prisynx.providers.core.slugify

/**
 * Shared WordPress + uprot pipeline used by Filmez and OnlineSerieTV.
 * Posts expose a uprot.net/msf/ link behind an image captcha.
 */
abstract class WpUprotProvider(
    name: String,
    private val siteBase: String,
    private val filmType: String?,
    private val seriesType: String?
) : TmdbProvider(name, siteBase) {

    override val supportsMovies: Boolean get() = filmType != null
    override val supportsTv: Boolean get() = seriesType != null

    private data class Post(
        val link: String,
        val key: String,
        val tail: String,
        val year: Int,
        val isSub: Boolean,
        val weak: Boolean
    )

    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isMovie = !ref.isTv
        val type = if (isMovie) filmType else seriesType
        if (type == null) return false
        val posts = findPosts(meta, type, isMovie)
        if (posts.isEmpty()) return false

        val season = ref.season ?: 1
        val episode = ref.episode ?: 1
        var emitted = 0

        for (candidate in posts.take(3)) {
            val html = get(candidate.link) ?: continue
            if (isMovie) {
                val links = filmLinks(html)
                if (links.isEmpty()) continue
                for (link in links.take(2)) {
                    val stream = resolveUprot(link) ?: continue
                    val quality = stream.quality.takeIf { it > 0 } ?: 1080
                    emit(
                        callback = callback,
                        url = stream.url,
                        label = "$name ${qualityLabel(quality)}",
                        quality = quality,
                        type = ExtractorLinkType.M3U8,
                        referer = "https://maxstream.video/",
                        headers = stream.headers
                    )
                    emitted++
                    if (emitted >= 2) break
                }
            } else {
                val year = pageYear(html)
                if (meta.year != null && year > 0 && kotlin.math.abs(year - meta.year) > 1) continue
                val links = episodeLinks(html, season, episode)
                if (links.isEmpty()) continue
                for (link in links.take(2)) {
                    val stream = resolveUprot(link) ?: continue
                    val quality = stream.quality.takeIf { it > 0 } ?: 1080
                    emit(
                        callback = callback,
                        url = stream.url,
                        label = "$name S${season}E$episode ${qualityLabel(quality)}",
                        quality = quality,
                        type = ExtractorLinkType.M3U8,
                        referer = "https://maxstream.video/",
                        headers = stream.headers
                    )
                    emitted++
                    if (emitted >= 2) break
                }
            }
            if (emitted > 0) break
        }
        return emitted > 0
    }

    // ------------------------------------------------------------ WordPress

    private suspend fun get(url: String): String? = try {
        net.get(
            url,
            headers = mapOf(
                "User-Agent" to UA_PC,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
            ),
            timeout = 25
        ).text
    } catch (e: Exception) {
        null
    }

    private suspend fun api(type: String, query: String): List<JsonNode> = try {
        val root = json.readTree(
            net.get(
                "$siteBase/wp-json/wp/v2/$type?$query&per_page=20&_fields=slug,link,title",
                headers = mapOf("User-Agent" to UA_PC, "Accept" to "application/json"),
                timeout = 20
            ).text
        )
        if (root.isArray) root.toList() else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun parsePost(post: JsonNode): Post? {
        val link = post.path("link").asText("")
        if (link.isBlank()) return null
        val raw = decodeHtmlEntities(post.path("title").path("rendered").asText(""))
        val slug = post.path("slug").asText("")
        val isSub = Regex("SUB[\\s-]*ITA\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(raw) ||
            Regex("-sub-ita(?:-\\d+)?$", RegexOption.IGNORE_CASE).containsMatchIn(slug)
        var title = Regex("\\s*[\\u2013\\u2014-]?\\s*SUB[\\s-]*ITA\\s*$", RegexOption.IGNORE_CASE)
            .replace(raw, "")
            .trim()
        var year = 0
        Regex("\\s\\(?((?:19|20)\\d{2})\\)?$").find(title)?.let { m ->
            year = m.groupValues[1].toIntOrNull() ?: 0
            title = title.substring(0, m.range.first).trim()
        }
        val key = slugify(title)
        val parts = title.split(Regex("\\s*(?::|\\s[\\u2013\\u2014-]\\s)\\s*"))
        val tail = if (parts.size > 1) slugify(parts.last()) else ""
        return Post(link, key, tail, year, isSub, false)
    }

    private suspend fun findPosts(meta: MediaMeta, type: String, isMovie: Boolean): List<Post> {
        val titles = listOf(meta.title, meta.originalTitle)
            .filter { it.isNotBlank() }
            .distinct()
        val keys = mutableListOf<String>()
        for (title in titles) {
            val key = slugify(title)
            if (key.isNotBlank() && key !in keys) keys.add(key)
        }
        val both = keys.size >= 2
        if (both) {
            val a = keys[0]
            val b = keys[1]
            listOf("$b-$a", "$a-$b").forEach { if (it !in keys) keys.add(it) }
        }
        val year = meta.year ?: 0

        // Direct slug guesses first: WordPress resolves many titles without a search round-trip.
        val slugs = mutableListOf<String>()
        for (key in keys) {
            if (isMovie) {
                if (year > 0) {
                    for (yy in listOf(year, year - 1, year + 1)) {
                        slugs.add("$key-$yy")
                        slugs.add("$key-$yy-sub-ita")
                    }
                }
            } else {
                slugs.add(key)
                if (year > 0) slugs.add("$key-$year")
            }
        }
        if (slugs.isNotEmpty()) {
            val exact = api(type, "slug=${enc(slugs.joinToString(","))}")
            val ranked = rank(exact.mapNotNull { parsePost(it) }, keys, year, isMovie)
            if (ranked.isNotEmpty()) return ranked
        }

        val found = mutableListOf<Post>()
        for (title in titles) {
            val list = api(type, "search=${enc(title)}")
            found.addAll(list.mapNotNull { parsePost(it) })
            val ranked = rank(found, keys, year, isMovie)
            if (ranked.isNotEmpty() && !ranked[0].weak) return ranked
        }
        return rank(found, keys, year, isMovie)
    }

    private fun rank(posts: List<Post>, keys: List<String>, year: Int, isMovie: Boolean): List<Post> {
        val section = if (isMovie) "/film/" else "/serietv/"
        val seen = HashSet<String>()
        val out = mutableListOf<Post>()
        for (post in posts) {
            if (!post.link.contains(section) || !seen.add(post.link)) continue
            val weak = post.key !in keys
            if (weak && (year == 0 || post.tail.isBlank() || post.tail !in keys)) continue
            if (year > 0 && (isMovie || post.year > 0) &&
                (post.year == 0 || kotlin.math.abs(post.year - year) > 1)
            ) continue
            out.add(post.copy(weak = weak))
        }
        return out.sortedWith(
            compareBy<Post> { it.weak }
                .thenBy { it.isSub }
                .thenBy { if (year > 0 && it.year > 0) kotlin.math.abs(it.year - year) else 0 }
        )
    }

    // ------------------------------------------------------------ page parsing

    private fun pageYear(html: String): Int =
        Regex("Anno:\\s*<i>\\s*((?:19|20)\\d{2})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun filmLinks(html: String): List<String> {
        val clean = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
        val out = mutableListOf<String>()
        Regex("https?://uprot\\.net/msf/[A-Za-z0-9]+", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { if (it.value !in out) out.add(it.value) }
        return out
    }

    private fun episodeLinks(html: String, season: Int, episode: Int): List<String> {
        val clean = html.replace(Regex("<!--[\\s\\S]*?-->"), "")
        val out = mutableListOf<String>()
        Regex(
            "<td>([^<]*?)(\\d{1,2})x(\\d{1,3})(?!\\d)([^<]*)</td>\\s*<td>\\s*<a[^>]+href=[\"'](https?://uprot\\.net/msf/[A-Za-z0-9]+)",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { m ->
            val s = m.groupValues[2].toIntOrNull() ?: return@forEach
            val e = m.groupValues[3].toIntOrNull() ?: return@forEach
            if (s == season && e == episode && m.groupValues[5] !in out) out.add(m.groupValues[5])
        }
        if (out.isNotEmpty()) return out

        // Some pages group rows without a <td> wrapper: grab the surrounding episode block.
        val label = Regex(
            "(?<!\\d)(?:$season|0$season)\\s*(?:x|\\u00d7|&#215;)\\s*0?$episode(?!\\d)",
            RegexOption.IGNORE_CASE
        ).find(clean) ?: return emptyList()
        val start = label.range.first
        val next = Regex(
            "S\\d+E\\d+|\\d+\\s*(?:x|\\u00d7|&#215;)\\s*\\d+",
            RegexOption.IGNORE_CASE
        ).find(clean, start + label.value.length)
        val end = next?.range?.first?.takeIf { it > start } ?: clean.length
        Regex("https?://uprot\\.net/msf/[A-Za-z0-9]+", RegexOption.IGNORE_CASE)
            .findAll(clean.substring(start, end))
            .forEach { if (it.value !in out) out.add(it.value) }
        return out
    }
}
