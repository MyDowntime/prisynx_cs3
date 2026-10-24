package prisynx.hub

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import prisynx.providers.VidxGo
import prisynx.providers.core.MediaMeta
import prisynx.providers.core.MediaRef
import prisynx.providers.core.TmdbProvider

class PriSynxProvider : TmdbProvider("PriSynx", "https://www.themoviedb.org") {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = HubSettings.tmdbRows.filter { HubSettings.isTmdbRowEnabled(it) }
        return coroutineScope {
            val tmdbJob = rows.map { spec -> async { fetchRow(spec.row, page) } }
            val topxJob = async { if (page <= 1 && HubSettings.topxEnabled) topxRows() else emptyList() }
            val raiJob = async { if (page <= 1 && HubSettings.raiHome) raiRows() else emptyList() }
            val tmdb = tmdbJob.awaitAll()
            val tmdbLists = tmdb.filter { it.list.isNotEmpty() }.map { HomePageList(it.name, it.list) }
            val topx = topxJob.await()
            val rai = raiJob.await()
            val groups = mapOf("tmdb" to tmdbLists, "topx" to topx, "rai" to rai)
            val lists = HubSettings.homeOrder().flatMap { groups[it].orEmpty() }
            newHomePageResponse(lists, tmdb.any { it.hasNext })
        }
    }

    private suspend fun raiRows(): List<HomePageList> =
        RaiHub.rows()
            .filter { HubSettings.isRaiRowEnabled(RaiHub.key(it)) }
            .map { HomePageList("RaiPlay · ${it.name}", it.list, isHorizontalImages = true) }

    // ------------------------------------------------------------ RaiPlay routing

    private suspend fun tmdbSearch(query: String): List<SearchResponse> = super.search(query)

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val rai = async {
            if (!HubSettings.raiSearch) emptyList()
            else withTimeoutOrNull(RAI_TIMEOUT_MS) {
                try {
                    RaiHub.api.search(query)
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList()
        }
        val tmdb = try {
            tmdbSearch(query)
        } catch (e: Exception) {
            emptyList()
        }
        tmdb + rai.await()
    }

    override suspend fun load(url: String): LoadResponse {
        if (MediaRef.parse(url) != null) return super.load(url)
        return RaiHub.api.load(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("RaiPlay: contenuto non disponibile")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (MediaRef.parse(data) != null) return super.loadLinks(data, isCasting, subtitleCallback, callback)
        return RaiHub.api.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun topxRows(): List<HomePageList> {
        val url = HubSettings.topxUrl
        val catalogs = TopX.catalogs(url).filter { HubSettings.isTopxCatalogEnabled(it.key) }
        val gate = Semaphore(6)
        return coroutineScope {
            catalogs.map { catalog ->
                async {
                    gate.withPermit {
                        val cards = TopX.items(url, catalog).map { card(it) }
                        if (cards.isEmpty()) null else HomePageList(catalog.label, cards)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun card(item: TopxItem): SearchResponse {
        val ref = MediaRef(item.tmdbId, item.isTv)
        return if (item.isTv) {
            newTvSeriesSearchResponse(item.title, ref.asWebUrl(), TvType.TvSeries, fix = false) {
                posterUrl = item.poster
                year = item.year
            }
        } else {
            newMovieSearchResponse(item.title, ref.asWebUrl(), TvType.Movie, fix = false) {
                posterUrl = item.poster
                year = item.year
            }
        }
    }

    /**
     * Runs every enabled source in parallel. Links are forwarded in the order
     * chosen in the settings: the source at the head of the queue streams its
     * links immediately, later ones are buffered until the earlier ones finish.
     */
    override suspend fun resolve(
        ref: MediaRef,
        meta: MediaMeta,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val active = HubSettings.enabledSources().filter { source ->
            if (ref.isTv) source.api.supportsTv else source.api.supportsMovies
        }
        if (active.isEmpty()) return false

        val lock = Any()
        val buffers = List(active.size) { mutableListOf<ExtractorLink>() }
        val done = BooleanArray(active.size)
        var head = 0
        var found = false

        fun onLink(index: Int, link: ExtractorLink) = synchronized(lock) {
            found = true
            if (index == head) callback(link) else buffers[index].add(link)
        }

        fun onDone(index: Int) = synchronized(lock) {
            done[index] = true
            while (head < active.size && done[head]) {
                head++
                if (head < active.size) {
                    buffers[head].forEach(callback)
                    buffers[head].clear()
                }
            }
        }

        coroutineScope {
            active.forEachIndexed { index, source ->
                launch {
                    try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            source.api.resolve(ref, meta, subtitleCallback) { onLink(index, it) }
                        }
                    } catch (e: Exception) {
                        // one broken source must not break the others
                    } finally {
                        onDone(index)
                    }
                }
            }
        }
        return found
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        VidxGo.interceptorFor(extractorLink)

    private companion object {
        const val SOURCE_TIMEOUT_MS = 60_000L
        const val RAI_TIMEOUT_MS = 20_000L
    }
}
