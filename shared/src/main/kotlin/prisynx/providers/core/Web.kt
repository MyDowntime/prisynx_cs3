package prisynx.providers.core

import com.fasterxml.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap

const val DOMAINS_URL =
    "https://raw.githubusercontent.com/realbestia1/domains/refs/heads/main/domains.json"
const val GUARDOSERIE_DOMAINS_URL =
    "https://raw.githubusercontent.com/qwertyuiop8899/streamvix/main/config/domains.json"
private const val DOMAINS_TTL_MS = 6 * 60 * 60 * 1000L

private val domainsCache = ConcurrentHashMap<String, Pair<JsonNode, Long>>()

suspend fun fetchDomains(url: String = DOMAINS_URL): JsonNode? {
    val now = System.currentTimeMillis()
    domainsCache[url]?.let { (node, at) ->
        if (now - at < DOMAINS_TTL_MS) return node
    }
    val node = try {
        json.readTree(net.get(url, headers = mapOf("Accept" to "application/json"), timeout = 15).text)
    } catch (e: Exception) {
        null
    }
    if (node != null) domainsCache[url] = node to now
    return node
}

fun JsonNode.domainOf(key: String, fallback: String): String {
    val value = path(key).asText("").trim()
        .removePrefix("https://").removePrefix("http://").trimEnd('/')
    return if (value.matches(Regex("^[a-z0-9.-]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE))) {
        value
    } else {
        fallback
    }
}

fun JsonNode.urlOf(key: String, fallback: String): String {
    val value = path(key).asText("").trim().trimEnd('/')
    return if (value.startsWith("http://") || value.startsWith("https://")) value else fallback
}

fun normalizeTitle(value: String): String {
    val decoded = value
        .replace("&amp;", "&", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace("&rsquo;", "'", true)
        .replace("&lsquo;", "'", true)
        .replace("&hellip;", "...", true)
        .replace("&nbsp;", " ", true)
    val stripped = try {
        java.text.Normalizer.normalize(decoded, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
    } catch (e: Exception) {
        decoded
    }
    return stripped.lowercase()
        .replace("&", " e ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

fun slugify(value: String): String = normalizeTitle(value).replace(Regex("\\s+"), "-")

fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

fun originOf(url: String): String =
    Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: ""
