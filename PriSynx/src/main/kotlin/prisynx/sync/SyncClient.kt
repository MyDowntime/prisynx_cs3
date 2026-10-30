package prisynx.sync

import android.os.Build
import android.util.Base64
import prisynx.providers.core.net
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SyncClient communicates with the VPS vault.
 * The endpoint is obfuscated in bytecode to prevent automated scraping.
 */
object SyncClient {
    private val mapper = jacksonObjectMapper()

    // Obfuscated string: "https://prisync.stremio-italia.eu" (XOR 0x5A)
    private val OBFUSCATED_ENDPOINT = byteArrayOf(
        (0x32).toByte(), (0x2E).toByte(), (0x2E).toByte(), (0x2A).toByte(), (0x29).toByte(),
        (0x60).toByte(), (0x75).toByte(), (0x75).toByte(), (0x2A).toByte(), (0x28).toByte(),
        (0x33).toByte(), (0x29).toByte(), (0x23).toByte(), (0x34).toByte(), (0x39).toByte(),
        (0x74).toByte(), (0x29).toByte(), (0x2E).toByte(), (0x28).toByte(), (0x3F).toByte(),
        (0x37).toByte(), (0x33).toByte(), (0x35).toByte(), (0x77).toByte(), (0x33).toByte(),
        (0x2E).toByte(), (0x3B).toByte(), (0x36).toByte(), (0x33).toByte(), (0x3B).toByte(),
        (0x74).toByte(), (0x3F).toByte(), (0x2F).toByte()
    )

    fun getDefaultServerUrl(): String {
        val out = CharArray(OBFUSCATED_ENDPOINT.size)
        for (i in OBFUSCATED_ENDPOINT.indices) {
            out[i] = (OBFUSCATED_ENDPOINT[i].toInt() xor 0x5A).toChar()
        }
        return String(out)
    }

    data class PushResponse(
        val success: Boolean = false,
        val updatedAt: Long = 0L,
        val message: String? = null,
        val error: String? = null
    )

    data class PullResponse(
        val success: Boolean = false,
        val salt: String? = null,
        val payload: String? = null,
        val deviceName: String? = null,
        val updatedAt: Long = 0L,
        val error: String? = null
    )

    /**
     * Uploads encrypted backup to the server.
     */
    suspend fun pushVault(serverUrl: String, pin: String, plaintextJson: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val base = serverUrl.trimEnd('/')
            val saltBytes = CryptoEngine.generateSalt()
            val ciphertext = CryptoEngine.encrypt(plaintextJson, pin, saltBytes)
            val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
            val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

            val body = mapOf(
                "pin" to pin,
                "salt" to saltBase64,
                "payload" to ciphertext,
                "deviceName" to device
            )

            val jsonBody = mapper.writeValueAsString(body)
            val res = net.post(
                "$base/api/vault/push",
                headers = mapOf("Content-Type" to "application/json"),
                json = jsonBody,
                timeout = 15
            )

            if (res.code in 200..299) {
                val parsed = mapper.readValue<PushResponse>(res.text)
                if (parsed.success) {
                    true to "Backup cifrato caricato con successo!"
                } else {
                    false to (parsed.error ?: "Errore sconosciuto del server")
                }
            } else {
                false to "Errore server (${res.code}): ${res.text.take(100)}"
            }
        } catch (e: Exception) {
            false to "Errore di connessione: ${e.message}"
        }
    }

    /**
     * Downloads encrypted backup and decrypts it locally with PIN.
     */
    suspend fun pullVault(serverUrl: String, pin: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val base = serverUrl.trimEnd('/')
            val body = mapOf("pin" to pin)
            val jsonBody = mapper.writeValueAsString(body)

            val res = net.post(
                "$base/api/vault/pull",
                headers = mapOf("Content-Type" to "application/json"),
                json = jsonBody,
                timeout = 15
            )

            if (res.code in 200..299) {
                val parsed = mapper.readValue<PullResponse>(res.text)
                if (!parsed.success || parsed.payload == null || parsed.salt == null) {
                    return@withContext false to (parsed.error ?: "Risposta non valida dal server")
                }

                val saltBytes = Base64.decode(parsed.salt, Base64.NO_WRAP)
                val decryptedJson = try {
                    CryptoEngine.decrypt(parsed.payload, pin, saltBytes)
                } catch (e: Exception) {
                    return@withContext false to "Impossibile decifrare: il PIN potrebbe essere errato!"
                }

                true to decryptedJson
            } else {
                false to "Errore (${res.code}): ${res.text.take(120)}"
            }
        } catch (e: Exception) {
            false to "Errore di connessione: ${e.message}"
        }
    }
}
