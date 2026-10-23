package prisynx.providers.core

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Audio sync between an original-language video and the Italian VixSrc audio, as measured by
 * ToastFlix / AutoSync (same data and verdicts as the Nuvio MovyITA and DualSync plugins).
 * A measured offset is applied by [AudioShift]; positive = Italian audio played later.
 */
object AudioSync {
    private const val TOASTFLIX = "https://toastflix.stremio-italia.eu"
    private const val AUTOSYNC = "https://autosync.stremio-italia.eu/plugin/jobs"
    private const val AUTOSYNC_KEY = "prisynx-hmac-secure-2026"

    /** Offsets within one 25 ms step are imperceptible. */
    private const val OK_SECONDS = 0.025
    private const val RATE_TOLERANCE = OK_SECONDS / 7200
    private const val STEP_MS = 25
    /** Larger offsets make every seek buffer for too long: those stay red. */
    const val MAX_DELAY_MS = 60_000
    /** One encode always has the same playlist length; other encodes differ by 0.5 s or more. */
    private const val LENGTH_TOLERANCE_S = 0.25

    /** Movy streams the vidfast files: ToastFlix files Movy measures under that name. */
    private fun aliases(provider: String): Set<String> = when (val p = provider.lowercase()) {
        "movy" -> setOf("movy", "vidfast")
        else -> setOf(p)
    }

    private val fpsPairs = listOf(23.976 to 24.0, 24.0 to 25.0, 23.976 to 25.0)

    data class Measure(
        val provider: String,
        val status: String,
        val offset: Double?,
        val rate: Double,
        val hasCuts: Boolean,
        val videoDuration: Double
    )

    data class Jobs(
        val status: String? = null,
        val stage: String? = null,
        val queueAhead: Int? = null,
        val errors: Map<String, String> = emptyMap()
    )

    data class Info(val mediaKey: String?, val measures: List<Measure>, val jobs: Jobs)

    enum class Level(val rank: Int) { GREEN(0), YELLOW(1), UNKNOWN(2), RED(3) }

    /** [delayMs] is the delay to apply, null when the audio must be left untouched. */
    data class Verdict(val level: Level, val badge: String, val delayMs: Int?, val measured: Boolean) {
        val rank: Int get() = level.rank * 10 + if (measured) 0 else 5
    }

    fun mediaKey(imdbId: String?, isTv: Boolean, season: Int, episode: Int): String? {
        if (imdbId.isNullOrBlank()) return null
        return (if (isTv) "series" else "movie") + ":" + imdbId + ":" + (if (isTv) season else 0) + ":" + (if (isTv) episode else 0)
    }

    /** ToastFlix measured renditions merged with finished AutoSync jobs. */
    suspend fun lookup(imdbId: String?, isTv: Boolean, season: Int, episode: Int): Info {
        val key = mediaKey(imdbId, isTv, season, episode) ?: return Info(null, emptyList(), Jobs())
        return coroutineScope {
            val candidates = async { candidates(imdbId!!, isTv, season, episode) }
            val jobs = async { jobs(key) }
            val list = candidates.await().toMutableList()
            val (state, done) = jobs.await()
            done.forEach { d ->
                if (list.none { it.provider == d.provider && abs(it.videoDuration - d.videoDuration) <= LENGTH_TOLERANCE_S }) list.add(d)
            }
            Info(key, list, state)
        }
    }

    /** ToastFlix verdict for one provider when the playlist length is unknown (Movy: miami/boise). */
    suspend fun status(imdbId: String?, isTv: Boolean, season: Int, episode: Int, provider: String): Measure? {
        if (imdbId.isNullOrBlank()) return null
        val url = "$TOASTFLIX/dual/offset/status?imdb=${enc(imdbId)}&type=${if (isTv) "series" else "movie"}" +
            "&season=${if (isTv) season else 0}&episode=${if (isTv) episode else 0}&provider=$provider&audio_source=vixsrc"
        val node = getJson(url) ?: return null
        if (!node.path("found").asBoolean(false)) return null
        return measureOf(node, provider, Double.NaN)
    }

    /**
     * Only measures of the same provider: different providers can serve encodes of identical
     * length that still start at a different point (F1: cinejoy +5.95 s vs Movy -4.06 s).
     */
    fun match(info: Info, videoLength: Double, provider: String): Measure? = info.measures
        .filter { it.provider.lowercase() in aliases(provider) && abs(it.videoDuration - videoLength) <= LENGTH_TOLERANCE_S }
        .minByOrNull { abs(it.videoDuration - videoLength) }

    /** Same decision tree as `syncFor` in the Nuvio DualSync plugin. */
    fun verdict(info: Info, videoLength: Double, provider: String, fallback: Measure? = null): Verdict {
        val hit = (if (videoLength > 0) match(info, videoLength, provider) else null) ?: fallback
        if (hit != null && hit.status == "ok") return classify(hit)
        info.jobs.errors[provider.lowercase()]?.let { return Verdict(Level.RED, "\u274C fallito", null, false) }
        jobVerdict(info.jobs)?.let { return it }
        if (hit != null && hit.status == "incompatible") return Verdict(Level.YELLOW, "\u23F3 da verificare", null, false)
        if (hit != null) return Verdict(Level.RED, "\u26D4 sync n/d", null, true)
        return Verdict(Level.YELLOW, "\u23F3 da misurare", null, false)
    }

    private fun jobVerdict(jobs: Jobs): Verdict? = when (jobs.status) {
        "running" -> if (jobs.stage == "tier2") Verdict(Level.YELLOW, "\uD83D\uDD2C analisi approfondita", null, false)
        else Verdict(Level.YELLOW, "\u2699\uFE0F in calcolo (~10s)", null, false)
        "queued" -> Verdict(
            Level.YELLOW,
            "\u23F3 " + if ((jobs.queueAhead ?: 0) == 0) "prossimo in coda" else "${(jobs.queueAhead ?: 0) + 1}\u00B0 in coda",
            null,
            false
        )
        "incompatible" -> Verdict(Level.RED, "\u26D4 versioni diverse", null, true)
        else -> null
    }

    private fun classify(m: Measure): Verdict {
        if (abs(m.rate - 1) > RATE_TOLERANCE) return Verdict(Level.RED, "\u26D4 " + speedLabel(m.rate), null, true)
        if (m.hasCuts) return Verdict(Level.RED, "\u26D4 tagli", null, true)
        val offset = m.offset ?: 0.0
        if (abs(offset) <= OK_SECONDS) return Verdict(Level.GREEN, "\uD83D\uDD0A\u2705 in sync", 0, true)
        val delayMs = (offset * 1000 / STEP_MS).roundToInt() * STEP_MS
        if (abs(delayMs) > MAX_DELAY_MS) {
            return Verdict(Level.RED, "\u26A0\uFE0F ${formatDelay(delayMs)} \u00B7 player esterno", null, true)
        }
        return Verdict(Level.YELLOW, "\u23F1\uFE0F ${formatDelay(delayMs)} auto", delayMs, true)
    }

    /** Ask AutoSync to measure encodes ToastFlix does not know yet (fire and forget). */
    suspend fun report(
        mediaKey: String?,
        itaAudio: String,
        engAudio: String?,
        audioHeaders: Map<String, String>,
        items: List<Map<String, Any?>>
    ) {
        if (mediaKey == null || items.isEmpty()) return
        val tracks = mutableListOf(mapOf("lang" to "ita", "base_url" to itaAudio, "headers" to audioHeaders, "source" to "vixsrc"))
        if (engAudio != null) tracks.add(mapOf("lang" to "eng", "base_url" to engAudio, "headers" to audioHeaders, "source" to "vixsrc"))
        val payload = mapOf(
            "media_key" to mediaKey,
            "audio_tracks" to tracks,
            "audio" to mapOf("source" to "vixsrc", "base_url" to itaAudio, "headers" to audioHeaders),
            "items" to items.take(3)
        )
        try {
            net.post(
                AUTOSYNC,
                headers = mapOf("X-PriSynx-Key" to AUTOSYNC_KEY),
                requestBody = json.writeValueAsString(payload).toRequestBody("application/json".toMediaTypeOrNull()),
                timeout = 4
            )
        } catch (e: Exception) {
            // best effort
        }
    }

    fun playlistLength(text: String): Double {
        var total = 0.0
        Regex("#EXTINF:([\\d.]+)").findAll(text).forEach { total += it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return Math.round(total * 100) / 100.0
    }

    suspend fun playlistLength(url: String, headers: Map<String, String>): Double = try {
        playlistLength(net.get(url, headers = headers, timeout = 15).text)
    } catch (e: Exception) {
        0.0
    }

    // ------------------------------------------------------------ remote

    private suspend fun candidates(imdbId: String, isTv: Boolean, season: Int, episode: Int): List<Measure> {
        val url = "$TOASTFLIX/dual/offset/candidates?imdb=${enc(imdbId)}&type=${if (isTv) "series" else "movie"}" +
            "&season=${if (isTv) season else 0}&episode=${if (isTv) episode else 0}&audio_source=vixsrc"
        val node = getJson(url) ?: return emptyList()
        return node.path("items").mapNotNull { measureOf(it, it.path("provider").asText(""), it.path("video_duration").asDouble(0.0)) }
    }

    private suspend fun jobs(mediaKey: String): Pair<Jobs, List<Measure>> {
        val node = getJson("$AUTOSYNC/status?media_key=${enc(mediaKey)}") ?: return Jobs() to emptyList()
        val items = node.path("items").toList()
        if (items.isEmpty()) return Jobs() to emptyList()
        val running = items.firstOrNull { it.path("status").asText() == "running" }
        val queued = items.filter { it.path("status").asText() == "queued" }
        val status = when {
            running != null -> "running"
            queued.isNotEmpty() -> "queued"
            items.any { it.path("status").asText() == "incompatible" } -> "incompatible"
            else -> null
        }
        val ahead = queued.mapNotNull { if (it.path("queue_ahead").isNumber) it.path("queue_ahead").asInt() else 0 }.minOrNull()
        val errors = mutableMapOf<String, String>()
        val done = mutableListOf<Measure>()
        items.forEach { it ->
            when (it.path("status").asText()) {
                "failed" -> errors[it.path("provider").asText("").lowercase()] =
                    it.path("error").asText("").ifBlank { it.path("last_error").asText("errore analisi") }.take(60)
                "done" -> {
                    val result = it.path("result")
                    if (result.path("status").asText() == "ok") {
                        measureOf(result, it.path("provider").asText(""), it.path("video_duration").asDouble(0.0))?.let(done::add)
                    }
                }
            }
        }
        val stage = running?.path("stage")?.asText("")?.ifBlank { "tier1" }
        return Jobs(status, stage, ahead, errors) to done
    }

    private fun measureOf(node: JsonNode, provider: String, videoDuration: Double): Measure? {
        val status = node.path("status").asText("")
        if (status.isBlank()) return null
        return Measure(
            provider = provider,
            status = status,
            offset = if (node.path("offset").isNumber) node.path("offset").asDouble() else null,
            rate = if (node.path("rate").isNumber) node.path("rate").asDouble() else 1.0,
            hasCuts = node.path("has_cuts").asBoolean(false),
            videoDuration = videoDuration
        )
    }

    private suspend fun getJson(url: String): JsonNode? = try {
        json.readTree(net.get(url, headers = mapOf("Accept" to "application/json"), timeout = 8).text)
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ labels

    private fun speedLabel(rate: Double): String {
        fpsPairs.forEach { (a, b) ->
            val ratio = a / b
            if (abs(rate - ratio) < 0.0003 || abs(rate - 1 / ratio) < 0.0003) return "${itNumber(a)}\u2194${itNumber(b)} fps"
        }
        return "velocit\u00E0 \u00D7" + itNumber(Math.round(rate * 10000) / 10000.0)
    }

    fun formatDelay(ms: Int): String = (if (ms >= 0) "+" else "") + itNumber(ms / 1000.0) + " s"

    private fun itNumber(value: Double): String =
        "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.').replace('.', ',')
}
