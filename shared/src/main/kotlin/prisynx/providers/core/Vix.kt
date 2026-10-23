package prisynx.providers.core

import android.util.Base64

data class VixMaster(
    val url: String,
    val text: String,
    val headers: Map<String, String>,
    val base: String,
    val embedUrl: String
)

data class VixMedia(
    val uri: String,
    val type: String,
    val language: String,
    val name: String,
    val forced: Boolean
)

/** Fetch the signed VixSrc master playlist for a movie or episode. */
suspend fun fetchVixMaster(
    tmdbId: Int,
    isTv: Boolean,
    season: Int,
    episode: Int,
    fallbackBase: String = "https://vixsrc.to"
): VixMaster? {
    val base = try {
        fetchDomains()?.urlOf("vixsrc", fallbackBase) ?: fallbackBase
    } catch (e: Exception) {
        fallbackBase
    }
    val apiPath = if (isTv) {
        "/api/tv/$tmdbId/$season/$episode"
    } else {
        "/api/movie/$tmdbId"
    }
    val apiHeaders = mapOf(
        "User-Agent" to UA_PC,
        "Referer" to "$base/",
        "Accept" to "application/json",
        "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8"
    )
    val payload = try {
        net.get("$base$apiPath?lang=it&_=${System.currentTimeMillis()}", headers = apiHeaders, timeout = 20).text
    } catch (e: Exception) {
        return null
    }
    val src = Regex("\"src\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1) ?: return null
    val embedUrl = absUrl(src, "$base/")
    val html = try {
        net.get(
            embedUrl,
            headers = mapOf("User-Agent" to UA_PC, "Referer" to "$base/"),
            timeout = 20
        ).text
    } catch (e: Exception) {
        return null
    }
    val playlist = Regex("\\burl\\s*:\\s*[\"']([^\"']+/playlist/\\d+[^\"']*)[\"']").find(html)
        ?.groupValues?.get(1) ?: return null
    val token = Regex("[\"']?\\btoken[\"']?\\s*:\\s*[\"']([^\"']+)[\"']").find(html)
        ?.groupValues?.get(1) ?: return null
    val expires = Regex("[\"']?\\bexpires[\"']?\\s*:\\s*[\"']?(\\d+)").find(html)
        ?.groupValues?.get(1) ?: return null
    val fhd = Regex("\\bcanPlayFHD\\s*=\\s*true").containsMatchIn(html) ||
        embedUrl.contains("canPlayFHD=1")

    var path = absUrl(playlist, "$base/").substringBefore("#")
    val queryStart = path.indexOf('?')
    val query = if (queryStart >= 0) path.substring(queryStart + 1) else ""
    if (queryStart >= 0) path = path.substring(0, queryStart)
    if (!path.endsWith(".m3u8", true)) path = path.trimEnd('/') + ".m3u8"
    val retained = query.split('&').filter {
        it.isNotBlank() && it.substringBefore('=').lowercase() !in setOf("token", "expires", "h")
    }
    val parts = mutableListOf<String>()
    parts.addAll(retained)
    parts.add("token=${enc(token)}")
    parts.add("expires=${enc(expires)}")
    if (fhd) parts.add("h=1")
    parts.add("lang=it")
    val masterUrl = "$path?${parts.joinToString("&")}"

    val headers = mapOf(
        "User-Agent" to UA_PC,
        "Referer" to embedUrl,
        "Origin" to base
    )
    val text = try {
        net.get(masterUrl, headers = headers, timeout = 20).text
    } catch (e: Exception) {
        return null
    }
    if (!text.contains("#EXTM3U")) return null
    return VixMaster(masterUrl, text, headers, base, embedUrl)
}

/** Parse `#EXT-X-MEDIA` entries from a VixSrc master playlist. */
fun vixMedia(text: String, masterUrl: String): List<VixMedia> {
    val out = mutableListOf<VixMedia>()
    for (line in text.split('\n')) {
        if (!line.startsWith("#EXT-X-MEDIA:")) continue
        val uri = attribute(line, "URI") ?: continue
        out.add(
            VixMedia(
                uri = absUrl(uri, masterUrl),
                type = attribute(line, "TYPE").orEmpty(),
                language = attribute(line, "LANGUAGE").orEmpty().lowercase(),
                name = attribute(line, "NAME").orEmpty(),
                forced = attribute(line, "FORCED") == "YES"
            )
        )
    }
    return out
}

fun vixAudio(tracks: List<VixMedia>): VixMedia? = tracks.firstOrNull {
    it.type == "AUDIO" && (it.language == "it" || it.language == "ita" || it.name.startsWith("ita", true))
}

fun vixEnglishAudio(tracks: List<VixMedia>): VixMedia? = tracks.firstOrNull {
    it.type == "AUDIO" && (it.language == "en" || it.language == "eng" ||
        it.name.contains("english", true) || it.name.startsWith("eng", true))
}

fun vixSubtitles(tracks: List<VixMedia>): List<VixMedia> =
    tracks.filter { it.type == "SUBTITLES" }

private fun attribute(line: String, key: String): String? {
    val m = Regex("(?:^|[,:])$key=(\"([^\"]*)\"|[^,]*)").find(line) ?: return null
    return m.groupValues[2].ifEmpty { m.groupValues[1] }
}

private data class QualityInfo(val label: String, val resolution: String, val bandwidth: Int)

private fun qualityInfo(quality: Int): QualityInfo = when {
    quality >= 2160 -> QualityInfo("4K", "3840x2160", 16_000_000)
    quality >= 1440 -> QualityInfo("1440p", "2560x1440", 10_000_000)
    quality >= 1080 -> QualityInfo("1080p", "1920x1080", 6_000_000)
    quality >= 720 -> QualityInfo("720p", "1280x720", 3_000_000)
    quality >= 480 -> QualityInfo("480p", "854x480", 1_500_000)
    else -> QualityInfo("360p", "640x360", 800_000)
}

fun qualityInfoLabel(quality: Int): String = qualityInfo(quality).label

private fun quoteSafe(value: String): String =
    value.replace(Regex("[\"\\r\\n]"), "").replace(Regex("[^\\x20-\\x7e]"), "")

/**
 * Build a self-contained HLS master that combines an original-language video
 * rendition with the Italian VixSrc audio track.
 */
fun buildCombinedMasterText(
    videoUrl: String,
    quality: Int,
    audio: VixMedia?,
    subtitles: List<VixMedia>
): String {
    val info = qualityInfo(quality)
    val lines = mutableListOf<String>()
    lines.add("#EXTM3U")
    lines.add("#EXT-X-VERSION:6")
    lines.add("#EXT-X-INDEPENDENT-SEGMENTS")
    if (audio != null) {
        lines.add(
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Italiano\",LANGUAGE=\"it\"," +
                "DEFAULT=YES,AUTOSELECT=YES,URI=\"${quoteSafe(audio.uri)}\""
        )
    }
    subtitles.forEach { sub ->
        val lang = sub.language.ifBlank { "it" }
        val name = sub.name.ifBlank { "Italiano" }
        lines.add(
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${quoteSafe(name)}\"," +
                "LANGUAGE=\"${quoteSafe(lang)}\",DEFAULT=NO,AUTOSELECT=" +
                (if (sub.forced) "YES" else "NO") + ",FORCED=" +
                (if (sub.forced) "YES" else "NO") + ",URI=\"${quoteSafe(sub.uri)}\""
        )
    }
    val audioAttr = if (audio != null) ",AUDIO=\"aud\"" else ""
    val subAttr = if (subtitles.isNotEmpty()) ",SUBTITLES=\"subs\"" else ""
    lines.add(
        "#EXT-X-STREAM-INF:BANDWIDTH=${info.bandwidth},RESOLUTION=${info.resolution}" +
            audioAttr + subAttr
    )
    lines.add(quoteSafe(videoUrl))
    return lines.joinToString("\n") + "\n"
}

/**
 * Publish the combined master on a loopback URL that CloudStream's HTTP-only
 * HLS data source can open. If the local server cannot start, fall back to a
 * `data:` URI (usable by external players that support it).
 */
fun publishCombinedMaster(
    videoUrl: String,
    quality: Int,
    audio: VixMedia?,
    subtitles: List<VixMedia>
): String {
    val text = buildCombinedMasterText(videoUrl, quality, audio, subtitles)
    LocalHls.publish(text)?.let { return it }
    val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return "data:application/vnd.apple.mpegurl;base64,$encoded"
}
