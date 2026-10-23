package prisynx.providers

import com.fasterxml.jackson.databind.JsonNode
import prisynx.providers.core.decodeB64
import prisynx.providers.core.json
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Abyss/Hydrax player (mloadm.cam and friends). The page embeds
 * `const datas = "<base64 json>"` whose `media` field is AES-256-CTR encrypted
 * with md5("user_id:slug:md5_id"); the decrypted JSON lists mp4 renditions that
 * are served through a `/sora/<size>/<token>` route.
 */
object Abyss {
    data class Source(val url: String, val quality: Int, val label: String)

    val HOST_REGEX = Regex("loadm|abyss|hydrax|short\\.icu", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<Source> {
        val encoded = Regex("(?:const|var|let)\\s+datas\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val raw = decodeB64(encoded) ?: return emptyList()
        val text = String(raw, Charsets.ISO_8859_1)
        val slug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return emptyList()
        val md5Id = Regex("\"md5_id\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1) ?: return emptyList()
        val userId = Regex("\"user_id\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1) ?: return emptyList()

        val media: JsonNode = run {
            val start = text.indexOf("\"media\":\"")
            val end = text.indexOf("\",\"config\"")
            if (start >= 0 && end > start) {
                val cipherText = unescape(text.substring(start + 9, end))
                val bytes = ByteArray(cipherText.length) { (cipherText[it].code and 0xff).toByte() }
                val plain = ctr(bytes, "$userId:$slug:$md5Id")
                json.readTree(String(plain, Charsets.UTF_8))
            } else {
                json.readTree(String(raw, Charsets.UTF_8)).path("media")
            }
        }

        val mp4 = media.path("mp4")
        val domains = mp4.path("domains").map { it.asText() }
        return mp4.path("sources")
            .sortedByDescending { it.path("size").asLong() }
            .mapNotNull { source ->
                val label = source.path("label").asText("")
                val quality = Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val direct = source.path("file").asText("")
                if (direct.startsWith("http")) return@mapNotNull Source(direct, quality, label)
                val size = source.path("size").asLong()
                val resId = source.path("res_id").asText("")
                val sub = source.path("sub").asText("")
                if (size <= 0 || resId.isBlank() || sub.isBlank()) return@mapNotNull null
                val domain = domains.firstOrNull { it.contains(sub) } ?: return@mapNotNull null
                val path = "/mp4/$md5Id/$resId/$size?v=$slug"
                val token = b64(b64(ctr(path.toByteArray(), size.toString())).toByteArray())
                val host = if (domain.startsWith("http")) domain.trimEnd('/') else "https://$domain"
                Source("$host/sora/$size/$token", quality, label)
            }
    }

    private fun b64(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP).trimEnd('=')

    private fun key(seed: String): ByteArray {
        val source = if (seed.all { it.isDigit() }) {
            ByteArray(seed.length) { (seed[it] - '0').toByte() }
        } else {
            seed.toByteArray(Charsets.UTF_8)
        }
        val digest = MessageDigest.getInstance("MD5").digest(source)
        return digest.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
    }

    private fun ctr(data: ByteArray, seed: String): ByteArray {
        val key = key(seed)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOf(16)))
        return cipher.doFinal(data)
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val n = value[i + 1]
                if (n == 'u' && i + 5 < value.length) {
                    val code = value.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                out.append(
                    when (n) {
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                        else -> n
                    }
                )
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
