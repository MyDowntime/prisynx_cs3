package prisynx.providers

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Kotlin port of KissKH's per-episode token generator.
 *
 * The site's JavaScript builds a pipe-separated payload, inserts a Java-style
 * string hash, encrypts it with AES-128-CBC using a static key/IV and returns
 * the uppercase hex ciphertext. The same token authorizes both the video
 * endpoint (VI_GUID) and the subtitle endpoint (SUB_GUID).
 */
internal object KissKHCrypto {
    const val VI_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"
    const val SUB_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"

    private const val APP_VERSION = "2.8.10"
    private const val PLATFORM_VERSION = 4830201L
    private const val APP_NAME = "kisskh"

    // First four AES round-key words, big-endian. The site ships the full key
    // schedule, but only the initial 128-bit key is needed for encryption.
    private val KEY_WORDS = intArrayOf(
        0x4f6bdaa3, -0x61d07350, 0x7f5e722d, -0x61210cec
    )

    private val IV_WORDS = intArrayOf(
        0x1504af3, 0x56e619cf, 0x2e42bba6, -0x73c08f07
    )

    fun makeKkey(episodeId: String, guid: String): String {
        val parts = mutableListOf(
            "",
            episodeId,
            "",
            "mg3c3b04ba",
            APP_VERSION,
            guid,
            PLATFORM_VERSION.toString(),
            APP_NAME.take(48),
            APP_NAME.lowercase().take(48),
            APP_NAME.take(48),
            APP_NAME,
            APP_NAME,
            APP_NAME,
            "00",
            ""
        )
        parts.add(1, javaHash(parts.joinToString("|")).toString())
        val payload = parts.joinToString("|")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(wordsToBytes(KEY_WORDS), "AES"),
            IvParameterSpec(wordsToBytes(IV_WORDS))
        )
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.ISO_8859_1))
        return buildString(encrypted.size * 2) {
            for (byte in encrypted) {
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    /**
     * The site's `h = (h << 5) - h + charCode` keeps the untruncated numeric
     * value of `h` between iterations; only `h << 5` is reduced to Int32.
     * Using a plain Int for `h` would change the token for long payloads.
     */
    private fun javaHash(value: String): Long {
        var h = 0L
        for (character in value) {
            h = (h.toInt() shl 5).toLong() - h + character.code
        }
        return h
    }

    private fun wordsToBytes(words: IntArray): ByteArray {
        val bytes = ByteArray(words.size * 4)
        for (i in words.indices) {
            val word = words[i]
            bytes[i * 4] = (word ushr 24).toByte()
            bytes[i * 4 + 1] = (word ushr 16).toByte()
            bytes[i * 4 + 2] = (word ushr 8).toByte()
            bytes[i * 4 + 3] = word.toByte()
        }
        return bytes
    }

    private const val HEX = "0123456789ABCDEF"
}
