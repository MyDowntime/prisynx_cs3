package prisynx.hub

import com.lagradost.cloudstream3.HomePageList
import kotlinx.coroutines.withTimeoutOrNull
import prisynx.raiplay.RaiPlayProvider

/**
 * RaiPlay inside the hub. The provider is renamed "PriSynx" so the
 * search/load responses it builds point back to the hub (CloudStream routes
 * loadLinks by apiName).
 */
object RaiHub {
    val api: RaiPlayProvider by lazy { RaiPlayProvider().apply { name = "PriSynx" } }

    private var cache: List<HomePageList> = emptyList()
    private var cachedAt = 0L

    suspend fun rows(force: Boolean = false): List<HomePageList> {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && now - cachedAt < CACHE_MS) return cache
        val rows = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                api.homeRows()
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
        if (rows.isNotEmpty()) {
            cache = rows
            cachedAt = now
        }
        return rows.ifEmpty { cache }
    }

    fun key(row: HomePageList): String =
        row.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private const val CACHE_MS = 20 * 60_000L
    private const val TIMEOUT_MS = 25_000L
}
