package prisynx.providers.core

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Applies an audio delay to an HLS audio rendition (MPEG-TS, optionally AES-128) by proxying it
 * through [LocalHls] and moving every PES/PCR timestamp by the delay. ExoPlayer maps all renditions
 * with the video's timestamp offset, so the shift is heard as audio played later (>0) or earlier (<0).
 *
 * The playlist timeline is kept consistent with the moved timestamps: segments that end up before
 * zero are dropped and the gap left at the start is filled with a generated silent AAC segment, so
 * the player neither skips video nor waits for audio.
 */
object AudioShift {
    private const val TS = 188
    private const val PTS_WRAP = 1L shl 33
    private const val CLOCK = 90_000L
    private const val SILENCE_MIN_S = 0.03

    private val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    // One silent AAC-LC raw_data_block per channel configuration (1 = mono, 2 = stereo, 6 = 5.1).
    private val silentFrames = mapOf(
        1 to hex("01182007"),
        2 to hex("211004608c1c"),
        6 to hex("011820010880230460231004608c0c230000e0")
    )

    /** Loopback URI of the delayed audio playlist, or null when the proxy cannot start. */
    fun audio(uri: String, headers: Map<String, String>, delayMs: Int): String? {
        val playlist = AudioPlaylist(uri, headers, delayMs)
        val base = LocalHls.register(playlist) ?: return null
        return "$base/index.m3u8"
    }

    /** Loopback URI of a WebVTT subtitle playlist with every cue moved by [delayMs]. */
    fun subtitles(uri: String, headers: Map<String, String>, delayMs: Int): String? {
        val playlist = SubtitlePlaylist(uri, headers, delayMs)
        val base = LocalHls.register(playlist) ?: return null
        return "$base/index.m3u8"
    }

    // ------------------------------------------------------------ playlists

    private class Segment(
        val index: Int,
        val start: Double,
        val duration: Double,
        val uri: String,
        val keyUri: String?,
        val iv: ByteArray?
    )

    private class Parsed(val segments: List<Segment>)

    private fun parse(text: String, baseUrl: String): Parsed? {
        if (!text.contains("#EXTM3U")) return null
        if (text.contains("#EXT-X-MAP") || text.contains("#EXT-X-BYTERANGE")) return null
        var sequence = 0L
        var keyUri: String? = null
        var iv: ByteArray? = null
        var duration = -1.0
        var start = 0.0
        val segments = mutableListOf<Segment>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD")
                    when (method) {
                        "NONE" -> { keyUri = null; iv = null }
                        "AES-128" -> {
                            keyUri = attr(line, "URI")?.let { absUrl(it, baseUrl) } ?: return null
                            iv = attr(line, "IV")?.let { hex(it.removePrefix("0x").removePrefix("0X")) }
                        }
                        else -> return null
                    }
                }
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") && duration >= 0 -> {
                    val index = segments.size
                    val segIv = if (keyUri != null) iv ?: sequenceIv(sequence + index) else null
                    segments.add(Segment(index, start, duration, absUrl(line, baseUrl), keyUri, segIv))
                    start += duration
                    duration = -1.0
                }
            }
        }
        return if (segments.isEmpty()) null else Parsed(segments)
    }

    private class AudioPlaylist(
        val uri: String,
        val headers: Map<String, String>,
        val delayMs: Int
    ) : LocalHls.Handler {
        private val delay = delayMs / 1000.0
        private val delta = (delayMs * CLOCK / 1000.0).roundToLong()

        @Volatile
        private var parsed: Parsed? = null
        @Volatile
        private var first = 0

        private fun load(): Parsed? {
            parsed?.let { return it }
            synchronized(this) {
                parsed?.let { return it }
                val text = fetchText(uri, headers) ?: return null
                val p = parse(text, uri) ?: return null
                first = if (delay >= 0) 0 else p.segments.indexOfFirst { it.start >= -delay - 0.0005 }.coerceAtLeast(0)
                parsed = p
                return p
            }
        }

        /** Seconds of silence needed before the first kept segment. */
        private fun gap(p: Parsed): Double =
            if (delay >= 0) delay else (p.segments[first].start + delay).coerceAtLeast(0.0)

        override fun serve(path: String): LocalHls.Reply? {
            val p = load() ?: return null
            return when {
                path == "index.m3u8" -> LocalHls.Reply("application/vnd.apple.mpegurl", playlist(p).toByteArray())
                path == "silence.ts" -> silence(p)?.let { LocalHls.Reply("video/mp2t", it) }
                path.startsWith("s") && path.endsWith(".ts") -> {
                    val index = path.substring(1, path.length - 3).toIntOrNull() ?: return null
                    val body = segment(p, index) ?: return null
                    LocalHls.Reply("video/mp2t", body)
                }
                else -> null
            }
        }

        private fun playlist(p: Parsed): String {
            val kept = p.segments.subList(first, p.segments.size)
            val gap = gap(p)
            val longest = maxOf(kept.maxOf { it.duration }, gap)
            val out = StringBuilder()
            out.append("#EXTM3U\n#EXT-X-VERSION:3\n")
            out.append("#EXT-X-TARGETDURATION:${ceil(longest).toInt().coerceAtLeast(1)}\n")
            out.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            if (gap >= SILENCE_MIN_S) out.append("#EXTINF:${fmt(gap)},\nsilence.ts\n")
            kept.forEach { out.append("#EXTINF:${fmt(it.duration)},\ns${it.index}.ts\n") }
            out.append("#EXT-X-ENDLIST\n")
            return out.toString()
        }

        private fun raw(p: Parsed, index: Int): ByteArray? {
            val seg = p.segments.getOrNull(index) ?: return null
            segments[seg.uri]?.let { return it }
            var body = fetchBytes(seg.uri, headers) ?: return null
            if (seg.keyUri != null) body = decrypt(body, key(seg.keyUri, headers) ?: return null, seg.iv!!) ?: return null
            segments[seg.uri] = body
            return body
        }

        private fun segment(p: Parsed, index: Int): ByteArray? {
            if (index < first) return null
            val body = raw(p, index)?.copyOf() ?: return null
            shiftTs(body, delta)
            return body
        }

        private fun silence(p: Parsed): ByteArray? {
            val gap = gap(p)
            if (gap < SILENCE_MIN_S) return null
            val next = raw(p, first) ?: return null
            val info = audioInfo(next) ?: return null
            // The silence ends where the first kept segment starts once shifted.
            val end = (info.firstPts + delta).mod(PTS_WRAP)
            return silentSegment(next, info, end, gap)
        }
    }

    private class SubtitlePlaylist(
        val uri: String,
        val headers: Map<String, String>,
        val delayMs: Int
    ) : LocalHls.Handler {
        @Volatile
        private var parsed: Parsed? = null

        private fun load(): Parsed? {
            parsed?.let { return it }
            synchronized(this) {
                parsed?.let { return it }
                val text = fetchText(uri, headers) ?: return null
                return parse(text, uri)?.also { parsed = it }
            }
        }

        override fun serve(path: String): LocalHls.Reply? {
            val p = load() ?: return null
            if (path == "index.m3u8") {
                val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
                out.append("#EXT-X-TARGETDURATION:${ceil(p.segments.maxOf { it.duration }).toInt().coerceAtLeast(1)}\n")
                out.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
                p.segments.forEach { out.append("#EXTINF:${fmt(it.duration)},\nv${it.index}.vtt\n") }
                out.append("#EXT-X-ENDLIST\n")
                return LocalHls.Reply("application/vnd.apple.mpegurl", out.toString().toByteArray())
            }
            if (path.startsWith("v") && path.endsWith(".vtt")) {
                val seg = p.segments.getOrNull(path.substring(1, path.length - 4).toIntOrNull() ?: return null) ?: return null
                val text = fetchText(seg.uri, headers) ?: return null
                return LocalHls.Reply("text/vtt", shiftVtt(text, delayMs).toByteArray())
            }
            return null
        }
    }

    // ------------------------------------------------------------ MPEG-TS

    private class AudioInfo(val pid: Int, val firstPts: Long, val sampleRate: Int, val srIndex: Int, val channels: Int, val profile: Int)

    /** Moves PTS/DTS of every PES and the PCR of every packet by [delta] (90 kHz units). */
    fun shiftTs(data: ByteArray, delta: Long) {
        var i = 0
        while (i + TS <= data.size) {
            if (data[i] != 0x47.toByte()) { i++; continue }
            val afc = (data[i + 3].toInt() shr 4) and 3
            var payload = i + 4
            if (afc and 2 != 0) {
                val len = data[i + 4].toInt() and 0xff
                if (len > 0 && (data[i + 5].toInt() and 0x10) != 0 && len >= 7) shiftPcr(data, i + 6, delta)
                payload = i + 5 + len
            }
            val start = (data[i + 1].toInt() and 0x40) != 0
            if (start && afc and 1 != 0 && payload + 14 <= i + TS && isPes(data, payload)) {
                val flags = (data[payload + 7].toInt() and 0xff) shr 6
                if (flags and 2 != 0) writeTs(data, payload + 9, readTs(data, payload + 9) + delta)
                if (flags == 3 && payload + 19 <= i + TS) writeTs(data, payload + 14, readTs(data, payload + 14) + delta)
            }
            i += TS
        }
    }

    private fun isPes(d: ByteArray, o: Int): Boolean {
        if (d[o].toInt() != 0 || d[o + 1].toInt() != 0 || d[o + 2].toInt() != 1) return false
        val sid = d[o + 3].toInt() and 0xff
        if (sid == 0xBC || sid == 0xBE || sid == 0xBF || sid == 0xF0 || sid == 0xF1 || sid == 0xFF || sid == 0xF2 || sid == 0xF8) return false
        return (d[o + 6].toInt() and 0xC0) == 0x80
    }

    private fun readTs(d: ByteArray, o: Int): Long =
        ((d[o].toLong() shr 1) and 0x07 shl 30) or
            ((d[o + 1].toLong() and 0xff) shl 22) or
            (((d[o + 2].toLong() and 0xff) shr 1) shl 15) or
            ((d[o + 3].toLong() and 0xff) shl 7) or
            ((d[o + 4].toLong() and 0xff) shr 1)

    private fun writeTs(d: ByteArray, o: Int, value: Long) {
        val v = value.mod(PTS_WRAP)
        d[o] = ((d[o].toInt() and 0xF0) or (((v shr 30) and 0x07).toInt() shl 1) or 1).toByte()
        d[o + 1] = ((v shr 22) and 0xff).toByte()
        d[o + 2] = ((((v shr 15) and 0x7f).toInt() shl 1) or 1).toByte()
        d[o + 3] = ((v shr 7) and 0xff).toByte()
        d[o + 4] = (((v and 0x7f).toInt() shl 1) or 1).toByte()
    }

    private fun shiftPcr(d: ByteArray, o: Int, delta: Long) {
        val base = ((d[o].toLong() and 0xff) shl 25) or ((d[o + 1].toLong() and 0xff) shl 17) or
            ((d[o + 2].toLong() and 0xff) shl 9) or ((d[o + 3].toLong() and 0xff) shl 1) or
            ((d[o + 4].toLong() and 0xff) shr 7)
        val v = (base + delta).mod(PTS_WRAP)
        d[o] = (v shr 25).toByte()
        d[o + 1] = (v shr 17).toByte()
        d[o + 2] = (v shr 9).toByte()
        d[o + 3] = (v shr 1).toByte()
        d[o + 4] = (((v and 1).toInt() shl 7) or (d[o + 4].toInt() and 0x7f)).toByte()
    }

    /** First audio PES of the segment: PID, PTS and the ADTS configuration. */
    private fun audioInfo(d: ByteArray): AudioInfo? {
        var i = 0
        while (i + TS <= d.size) {
            if (d[i] == 0x47.toByte() && (d[i + 1].toInt() and 0x40) != 0) {
                val afc = (d[i + 3].toInt() shr 4) and 3
                var o = i + 4
                if (afc and 2 != 0) o = i + 5 + (d[i + 4].toInt() and 0xff)
                if (afc and 1 != 0 && o + 14 <= i + TS && isPes(d, o) && (d[o + 3].toInt() and 0xE0) == 0xC0 &&
                    ((d[o + 7].toInt() and 0xff) shr 6) and 2 != 0
                ) {
                    val pid = ((d[i + 1].toInt() and 0x1f) shl 8) or (d[i + 2].toInt() and 0xff)
                    val pts = readTs(d, o + 9)
                    val h = o + 9 + (d[o + 8].toInt() and 0xff)
                    if (h + 7 > i + TS || d[h] != 0xFF.toByte() || (d[h + 1].toInt() and 0xF0) != 0xF0) return null
                    val profile = ((d[h + 2].toInt() and 0xff) shr 6) + 1
                    val sr = ((d[h + 2].toInt() and 0xff) shr 2) and 0x0f
                    val ch = ((d[h + 2].toInt() and 1) shl 2) or ((d[h + 3].toInt() and 0xff) shr 6)
                    if (sr >= sampleRates.size) return null
                    return AudioInfo(pid, pts, sampleRates[sr], sr, ch, profile)
                }
            }
            i += TS
        }
        return null
    }

    /**
     * A segment of silent AAC frames that ends exactly at [endPts], reusing the PAT/PMT of [template]
     * so the player sees the same program layout as the real segments.
     */
    private fun silentSegment(template: ByteArray, info: AudioInfo, endPts: Long, seconds: Double): ByteArray? {
        if (info.profile != 2) return null
        val raw = silentFrames[info.channels] ?: return null
        val frameTicks = 1024.0 * CLOCK / info.sampleRate
        val count = (seconds * CLOCK / frameTicks).roundToInt()
        if (count <= 0) return null
        val out = ByteArrayOutputStream()
        // Program tables from the template (PID 0 and the PMT it points to).
        var pmtPid = -1
        var i = 0
        while (i + TS <= template.size) {
            val pid = ((template[i + 1].toInt() and 0x1f) shl 8) or (template[i + 2].toInt() and 0xff)
            if (pid == 0 && pmtPid < 0) {
                out.write(template, i, TS)
                val o = i + 5 + (template[i + 4].toInt() and 0xff)
                pmtPid = ((template[o + 10].toInt() and 0x1f) shl 8) or (template[o + 11].toInt() and 0xff)
            } else if (pid == pmtPid) {
                out.write(template, i, TS)
                break
            }
            i += TS
        }
        if (pmtPid < 0) return null
        val adts = ByteArray(7)
        val frameLen = 7 + raw.size
        adts[0] = 0xFF.toByte()
        adts[1] = 0xF1.toByte()
        adts[2] = ((((info.profile - 1) and 3) shl 6) or (info.srIndex shl 2) or ((info.channels shr 2) and 1)).toByte()
        adts[3] = ((((info.channels and 3) shl 6)) or ((frameLen shr 11) and 3)).toByte()
        adts[4] = ((frameLen shr 3) and 0xff).toByte()
        adts[5] = ((((frameLen and 7) shl 5)) or 0x1f).toByte()
        adts[6] = 0xFC.toByte()
        val frame = adts + raw
        var cc = 0
        val perPes = 32
        var n = 0
        while (n < count) {
            val take = minOf(perPes, count - n)
            val pts = (endPts - ((count - n) * frameTicks).roundToLong()).mod(PTS_WRAP)
            val es = ByteArrayOutputStream()
            repeat(take) { es.write(frame) }
            val pes = pesPacket(es.toByteArray(), pts)
            cc = packetize(out, info.pid, pes, cc)
            n += take
        }
        return out.toByteArray()
    }

    private fun pesPacket(es: ByteArray, pts: Long): ByteArray {
        val header = ByteArray(14)
        header[2] = 1
        header[3] = 0xC0.toByte()
        val len = 8 + es.size
        header[4] = (len shr 8).toByte()
        header[5] = len.toByte()
        header[6] = 0x80.toByte()
        header[7] = 0x80.toByte()
        header[8] = 5
        header[9] = 0x21
        writeTs(header, 9, pts)
        return header + es
    }

    private fun packetize(out: ByteArrayOutputStream, pid: Int, pes: ByteArray, startCc: Int): Int {
        var cc = startCc
        var pos = 0
        var first = true
        while (pos < pes.size) {
            val packet = ByteArray(TS) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = (((if (first) 0x40 else 0) or ((pid shr 8) and 0x1f))).toByte()
            packet[2] = pid.toByte()
            val left = pes.size - pos
            if (left >= TS - 4) {
                packet[3] = (0x10 or cc).toByte()
                System.arraycopy(pes, pos, packet, 4, TS - 4)
                pos += TS - 4
            } else {
                // Adaptation field stuffing so the payload ends the packet.
                val afLen = TS - 4 - 1 - left
                packet[3] = (0x30 or cc).toByte()
                packet[4] = afLen.toByte()
                if (afLen > 0) packet[5] = 0
                System.arraycopy(pes, pos, packet, TS - left, left)
                pos += left
            }
            out.write(packet)
            cc = (cc + 1) and 0x0f
            first = false
        }
        return cc
    }

    // ------------------------------------------------------------ WebVTT

    private val cueTime = Regex("(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})")

    fun shiftVtt(text: String, delayMs: Int): String {
        val out = StringBuilder()
        val blocks = text.replace("\r\n", "\n").split(Regex("\n{2,}"))
        for (block in blocks) {
            val lines = block.split('\n')
            val timing = lines.indexOfFirst { it.contains("-->") }
            if (timing < 0) {
                out.append(block).append("\n\n")
                continue
            }
            val parts = lines[timing].split("-->")
            val startMs = toMs(parts[0].trim()) ?: continue
            val rest = parts[1].trim()
            val endText = rest.substringBefore(' ')
            val settings = rest.substringAfter(' ', "")
            val endMs = toMs(endText) ?: continue
            val newEnd = endMs + delayMs
            if (newEnd <= 0) continue
            val newStart = (startMs + delayMs).coerceAtLeast(0L)
            val line = vttTime(newStart) + " --> " + vttTime(newEnd) + if (settings.isNotBlank()) " $settings" else ""
            val copy = lines.toMutableList()
            copy[timing] = line
            out.append(copy.joinToString("\n")).append("\n\n")
        }
        return out.toString()
    }

    private fun toMs(value: String): Long? {
        val m = cueTime.matchEntire(value) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        return ((h * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong()
    }

    private fun vttTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms % 1000)
    }

    // ------------------------------------------------------------ helpers

    private val keys = lru<String, ByteArray>(8)

    // Decrypted segments shared by every proxied playlist: HEAD + GET and the silence template reuse them.
    private val segments = lru<String, ByteArray>(8)

    private fun key(uri: String, headers: Map<String, String>): ByteArray? {
        keys[uri]?.let { return it }
        val k = fetchBytes(uri, headers)?.takeIf { it.size == 16 } ?: return null
        keys[uri] = k
        return k
    }

    private fun decrypt(body: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(body)
    } catch (t: Throwable) {
        null
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (b in 0 until 8) iv[15 - b] = (sequence shr (8 * b)).toByte()
        return iv
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? = try {
        runBlocking { net.get(url, headers = headers, timeout = 20).text }
    } catch (t: Throwable) {
        null
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? = try {
        runBlocking {
            val res = net.get(url, headers = headers, timeout = 30)
            if (res.code in 200..299) res.body.bytes() else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun attr(line: String, key: String): String? {
        val m = Regex("(?:^|[,:])$key=(\"([^\"]*)\"|[^,]*)").find(line) ?: return null
        return m.groupValues[2].ifEmpty { m.groupValues[1] }
    }

    private fun fmt(seconds: Double): String = "%.6f".format(java.util.Locale.US, seconds)

    private fun hex(s: String): ByteArray {
        val clean = if (s.length % 2 == 1) "0$s" else s
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun <K, V> lru(size: Int): MutableMap<K, V> = Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = this.size > size
        }
    )
}
