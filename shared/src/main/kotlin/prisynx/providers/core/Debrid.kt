package prisynx.providers.core

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Debrid support for the torrent sources (IlCorsaroViola).
 *
 * The index only returns magnets. When the user configures a debrid service the
 * plugin asks the service to unlock the torrent (Real-Debrid, AllDebrid or
 * TorBox) and emits the direct HTTP link instead of the magnet. Only torrents
 * already cached on the service are used, so the list never waits for a
 * download; everything else falls back to the magnet.
 */
class DebridService(val id: String, val label: String, val keyPage: String)

data class DebridLink(val url: String, val name: String, val size: Long)

object DebridSettings {
    val services = listOf(
        DebridService("realdebrid", "Real-Debrid", "real-debrid.com/apitoken"),
        DebridService("alldebrid", "AllDebrid", "alldebrid.com/apikeys"),
        DebridService("torbox", "TorBox", "torbox.app/settings")
    )

    private const val PREFS = "prisynx_debrid"
    private const val KEY_ON = "enabled"
    private const val KEY_SERVICE = "service"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun attach(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences? = preferences

    fun labelOf(id: String): String =
        services.firstOrNull { it.id == id }?.label ?: "Debrid"

    /** Compact tag used in the stream labels ("RD", "AD", "TB"). */
    fun shortLabel(id: String): String = when (id) {
        "realdebrid" -> "RD"
        "alldebrid" -> "AD"
        "torbox" -> "TB"
        else -> "Debrid"
    }

    var enabled: Boolean
        get() = prefs()?.getBoolean(KEY_ON, true) ?: true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_ON, value)?.apply()
        }

    var service: String
        get() = prefs()?.getString(KEY_SERVICE, services.first().id)
            ?.takeIf { id -> services.any { it.id == id } }
            ?: services.first().id
        set(value) {
            if (services.none { it.id == value }) return
            prefs()?.edit()?.putString(KEY_SERVICE, value)?.apply()
        }

    /** API key of the selected service (each service keeps its own). */
    val apiKey: String
        get() = prefs()?.getString("key_$service", "").orEmpty().trim()

    fun setApiKey(value: String) {
        prefs()?.edit()?.putString("key_$service", value.trim())?.apply()
    }

    fun keyOf(id: String): String =
        prefs()?.getString("key_$id", "").orEmpty().trim()

    /** A debrid service is configured and enabled. */
    fun active(): Boolean = enabled && apiKey.isNotBlank()

    fun maskedKey(): String {
        val key = apiKey
        if (key.isBlank()) return "chiave non impostata"
        if (key.length <= 8) return "•".repeat(key.length)
        return key.take(4) + "••••" + key.takeLast(4)
    }

    fun reset() {
        prefs()?.edit()?.clear()?.apply()
        Debrid.clearCache()
    }
}

object Debrid {
    private const val RD_API = "https://api.real-debrid.com/rest/1.0"
    private const val AD_API = "https://api.alldebrid.com/v4"
    private const val TB_API = "https://api.torbox.app/v1/api"

    private const val SUCCESS_TTL = 30 * 60 * 1000L
    private const val FAILURE_TTL = 5 * 60 * 1000L
    private const val RESOLVE_TIMEOUT = 20_000L

    private class Hit(val link: DebridLink, val at: Long)

    private val hits = ConcurrentHashMap<String, Hit>()
    private val misses = ConcurrentHashMap<String, Long>()

    fun clearCache() {
        hits.clear()
        misses.clear()
    }

    /** Magnet infohash (the debrid APIs all work with the hex hash). */
    private fun hashOf(magnet: String): String? =
        Regex("urn:btih:([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
            .find(magnet)?.groupValues?.get(1)?.lowercase()

    /**
     * Direct link for a magnet, or null when the torrent is not cached (or the
     * service is not configured). Never throws.
     */
    suspend fun resolve(magnet: String): DebridLink? {
        if (!DebridSettings.active()) return null
        val hash = hashOf(magnet) ?: return null
        val now = System.currentTimeMillis()

        hits[hash]?.let { if (now - it.at < SUCCESS_TTL) return it.link else hits.remove(hash) }
        misses[hash]?.let { if (now - it < FAILURE_TTL) return null else misses.remove(hash) }

        val key = DebridSettings.apiKey
        val link = try {
            withTimeoutOrNull(RESOLVE_TIMEOUT) {
                when (DebridSettings.service) {
                    "realdebrid" -> realDebrid(magnet, hash, key)
                    "alldebrid" -> allDebrid(magnet, key)
                    "torbox" -> torBox(magnet, hash, key)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }

        if (link != null) {
            hits[hash] = Hit(link, System.currentTimeMillis())
            misses.remove(hash)
        } else {
            misses[hash] = System.currentTimeMillis()
        }
        return link
    }

    /** Validates a key and returns a message for the settings UI. */
    suspend fun check(service: String, key: String): String {
        if (key.isBlank()) return "Inserisci la chiave API"
        return try {
            when (service) {
                "realdebrid" -> {
                    val user = json.readTree(
                        net.get(
                            "$RD_API/user",
                            headers = mapOf("Authorization" to "Bearer $key"),
                            timeout = 20
                        ).text
                    )
                    val name = user.path("username").asText("")
                    if (name.isBlank()) "Chiave non valida" else "Real-Debrid: $name"
                }

                "alldebrid" -> {
                    val res = json.readTree(
                        net.get("$AD_API/user?agent=priSynx&apikey=$key", timeout = 20).text
                    )
                    val name = res.path("data").path("user").path("username").asText("")
                    if (name.isBlank()) {
                        "Chiave non valida (" + res.path("error").path("message").asText("errore") + ")"
                    } else {
                        "AllDebrid: $name"
                    }
                }

                "torbox" -> {
                    val me = json.readTree(
                        net.get(
                            "$TB_API/user/me",
                            headers = mapOf("Authorization" to "Bearer $key"),
                            timeout = 20
                        ).text
                    )
                    val mail = me.path("data").path("email").asText("")
                    if (mail.isBlank() || !me.path("success").asBoolean(false)) {
                        "Chiave non valida"
                    } else {
                        "TorBox: $mail"
                    }
                }

                else -> "Servizio sconosciuto"
            }
        } catch (e: Exception) {
            "Errore di rete: ${e.message ?: "sconosciuto"}"
        }
    }

    // ------------------------------------------------------------ Real-Debrid

    private suspend fun realDebrid(magnet: String, hash: String, key: String): DebridLink? {
        val headers = mapOf("Authorization" to "Bearer $key")

        // Only cached torrents: no download on the debrid account, no waiting.
        val availability = json.readTree(
            net.get("$RD_API/torrents/instantAvailability/$hash", headers = headers, timeout = 20).text
        )
        val filesNode = availability.path(hash).path("rd")
        val cached = (filesNode.isObject && filesNode.size() > 0) ||
            (filesNode.isArray && filesNode.size() > 0)
        if (!cached) return null
        val cachedFile = if (filesNode.isObject) {
            biggestFile(filesNode)
        } else {
            biggestFile(filesNode.path(0))
        }

        val added = json.readTree(
            net.post(
                "$RD_API/torrents/addMagnet",
                headers = headers,
                data = null,
                requestBody = FormBody.Builder().add("magnet", magnet).build(),
                timeout = 25
            ).text
        )
        val id = added.path("id").asText("")
        if (id.isBlank()) return null

        try {
            val files = cachedFile?.first ?: "all"
            net.post(
                "$RD_API/torrents/selectFiles/$id",
                headers = headers,
                data = null,
                requestBody = FormBody.Builder().add("files", files).build(),
                timeout = 25
            )

            var link = ""
            for (attempt in 0 until 5) {
                val info = json.readTree(
                    net.get("$RD_API/torrents/info/$id", headers = headers, timeout = 20).text
                )
                val links = info.path("links")
                if (links.isArray && links.size() > 0) {
                    link = links.path(0).asText("")
                    if (link.isNotBlank()) break
                }
                if (attempt < 4) kotlinx.coroutines.delay(600)
            }
            if (link.isBlank()) return null

            val unlocked = json.readTree(
                net.post(
                    "$RD_API/unrestrict/link",
                    headers = headers,
                    data = null,
                    requestBody = FormBody.Builder().add("link", link).build(),
                    timeout = 25
                ).text
            )
            val url = unlocked.path("download").asText("")
            if (url.isBlank()) return null
            return DebridLink(
                url = url,
                name = unlocked.path("filename").asText(cachedFile?.second ?: ""),
                size = unlocked.path("filesize").asLong(cachedFile?.third ?: 0)
            )
        } catch (e: Exception) {
            return null
        }
    }

    /** (fileId, filename, size) of the largest file inside an availability node. */
    private fun biggestFile(node: JsonNode): Triple<String, String, Long>? {
        if (!node.isObject) return null
        var best: Triple<String, String, Long>? = null
        node.fields().forEach { (fileId, info) ->
            val size = info.path("filesize").asLong(0)
            if (best == null || size > best!!.third) {
                best = Triple(fileId, info.path("filename").asText(""), size)
            }
        }
        return best
    }

    // ------------------------------------------------------------ AllDebrid

    private suspend fun allDebrid(magnet: String, key: String): DebridLink? {
        val query = "agent=priSynx&apikey=$key"
        val uploaded = json.readTree(
            net.post(
                "$AD_API/magnet/upload?$query",
                data = null,
                requestBody = FormBody.Builder().add("magnets[]", magnet).build(),
                timeout = 25
            ).text
        )
        val entry = uploaded.path("data").path("magnets").path(0)
        val id = entry.path("id").asLong(0)
        val ready = entry.path("ready").asBoolean(false)
        if (id == 0L || !ready) {
            if (id != 0L) {
                runCatching { net.post("$AD_API/magnet/delete?id=$id&$query", data = null, timeout = 15) }
            }
            return null
        }

        var bestLink = ""
        var bestFile: JsonNode? = null
        var bestSize = -1L
        for (attempt in 0 until 5) {
            val status = json.readTree(
                net.get("$AD_API/magnet/status?id=$id&$query", timeout = 20).text
            )
            val magnets = status.path("data").path("magnets")
            val files = magnets.path("files")
            if (files.isArray) {
                files.forEach { file ->
                    val link = file.path("l").asText("")
                    val size = file.path("s").asLong(0)
                    if (link.isNotBlank() && size > bestSize) {
                        bestLink = link
                        bestFile = file
                        bestSize = size
                    }
                }
            }
            if (bestLink.isNotBlank()) break
            if (attempt < 4) kotlinx.coroutines.delay(600)
        }
        if (bestLink.isBlank()) return null
        val file = bestFile
        val link = bestLink

        val unlocked = json.readTree(
            net.post(
                "$AD_API/link/unlock?$query",
                data = null,
                requestBody = FormBody.Builder().add("link", link).build(),
                timeout = 25
            ).text
        )
        val url = unlocked.path("data").path("link").asText("")
        if (url.isBlank()) return null
        return DebridLink(
            url = url,
            name = file?.path("n")?.asText("").orEmpty(),
            size = file?.path("s")?.asLong(0) ?: 0L
        )
    }

    // ------------------------------------------------------------ TorBox

    private suspend fun torBox(magnet: String, hash: String, key: String): DebridLink? {
        val headers = mapOf("Authorization" to "Bearer $key")
        val cached = json.readTree(
            net.get(
                "$TB_API/torrents/checkcached?hash=$hash&format=list&list_files=true",
                headers = headers,
                timeout = 20
            ).text
        )
        val data = cached.path("data")
        val isCached = when {
            data.isArray -> data.any { it.asText("") == hash }
            data.has(hash) -> true
            else -> false
        }
        if (!isCached) return null

        val created = json.readTree(
            net.post(
                "$TB_API/torrents/createtorrent",
                headers = headers,
                data = null,
                requestBody = FormBody.Builder().add("magnet", magnet).build(),
                timeout = 25
            ).text
        )
        val torrentId = created.path("data").path("torrent_id").asLong(0)
        if (torrentId == 0L) return null

        var best: Pair<Long, JsonNode>? = null
        for (attempt in 0 until 5) {
            val list = json.readTree(
                net.get(
                    "$TB_API/torrents/mylist?id=$torrentId&bypass_cache=true",
                    headers = headers,
                    timeout = 20
                ).text
            )
            val files = list.path("data").path("files")
            if (files.isArray) {
                files.forEach { file ->
                    val size = file.path("size").asLong(0)
                    if (best == null || size > best!!.first) best = size to file
                }
            }
            if (best != null) break
            if (attempt < 4) kotlinx.coroutines.delay(600)
        }
        val (size, file) = best ?: return null
        val fileId = file.path("id").asLong(0)
        if (fileId == 0L) return null

        val download = json.readTree(
            net.get(
                "$TB_API/torrents/requestdl?token=$key&torrent_id=$torrentId&file_id=$fileId",
                headers = headers,
                timeout = 25
            ).text
        )
        val url = download.path("data").asText("")
        if (url.isBlank()) return null
        return DebridLink(url = url, name = file.path("name").asText(""), size = size)
    }
}
