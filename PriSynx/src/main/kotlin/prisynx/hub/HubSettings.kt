package prisynx.hub

import android.content.Context
import android.content.SharedPreferences
import prisynx.providers.core.ProviderOptions
import prisynx.providers.AltadefinizioneProvider
import prisynx.providers.AnimeSaturnProvider
import prisynx.providers.AnimeUnityProvider
import prisynx.providers.AnimeWorldProvider
import prisynx.providers.Cb01Provider
import prisynx.providers.CorsaroViolaProvider
import prisynx.providers.DualSyncProvider
import prisynx.providers.EurostreamingProvider
import prisynx.providers.FilmezProvider
import prisynx.providers.GuardoserieProvider
import prisynx.providers.MovyitaProvider
import prisynx.providers.OnlineSerieTvProvider
import prisynx.providers.PaCCProvider
import prisynx.providers.RealtimeProvider
import prisynx.providers.ToonitaliaProvider
import prisynx.providers.VidxGoProvider
import prisynx.providers.VixSrcProvider
import prisynx.providers.core.TmdbProvider
import prisynx.providers.core.TmdbRow

class HubSource(val id: String, val label: String, factory: () -> TmdbProvider) {
    val api: TmdbProvider by lazy(factory)
}

class HubTmdbRow(val id: String, val defaultOn: Boolean, val row: TmdbRow)

object HubSettings {
    const val DEFAULT_TOPX_URL =
        "https://topx.stremio-italia.eu/c/AmzMr7GQKJFY_AZB6Pdv-RdRZqsMT839KuI7ScaZ23xj5IL6ljF8gEBudpT00Giq5mL0Hb4v1CdjBYqtabrPZNccKuM5FOkj6T0b1q7mLuIP7aI4GuMqjY3zFq3zCXqx3FCkof9PvH5UdpirBCEc9cuZ0gGGY9qir6wNIx-yNmQrwKrYiCKwTaE3RS6qG0W7SpAcZ1XtfJaUyoNZQTxRrPl-stXCR3ziqp90esldxi9e1DF376w5r-RpUlT7XeOmB9Y9UlaqNo4sHwH4n-kjHdM3hjUQ99K09zcDvqjQ5Y12xDQHF4qR3El9JHePJ2XFB3Wr_LuYwd-RiieVkbkyPKwK7OmYpLZvUU-SvyjBofp0g1UFbhPBhKzNSg1EmgJSsB5uvs8YQIfgaLoK2PlLs1RZHvcOg4h-aZRRqhs_FsWOJzrwdxLKSsqfau_dwmJtmVDHEiivgrLFE71UrW9ohwJocnzCSltNtcbmVMVL3FSY9FSIJ-FdnYvJ-F0tmelBdOGyNwWthaM5x3LXXy6x3RY6lOegIVlVX2NMcdDazt5v682mbeOo0YIzAwCEZ7pe7OiJLp3gQYPj_vlj9rkqugXgZoMMZC_lzKhiPZP9AnJ9bIPB9b02gv7kiKXPrbSpYvmjDSAny2dak0PbwEX0sDt6ro1DQTc4XxnKE8Rb5BJjhjRYRl4lAU5PmuDXSjKh0kzNwZVPhOs/manifest.json"

    val sources: List<HubSource> = listOf(
        HubSource("vixsrc", "VixSrc") { VixSrcProvider() },
        HubSource("vidxgo", "VidxGo") { VidxGoProvider() },
        HubSource("guardoserie", "Guardoserie") { GuardoserieProvider() },
        HubSource("eurostreaming", "Eurostreaming") { EurostreamingProvider() },
        HubSource("cb01", "CB01") { Cb01Provider() },
        HubSource("altadefinizione", "Altadefinizione") { AltadefinizioneProvider() },
        HubSource("filmez", "Filmez") { FilmezProvider() },
        HubSource("onlineserietv", "OnlineSerieTV") { OnlineSerieTvProvider() },
        HubSource("movyita", "MovyITA") { MovyitaProvider() },
        HubSource("dualsync", "DualSync") { DualSyncProvider() },
        HubSource("corsaroviola", "IlCorsaroViola") { CorsaroViolaProvider() },
        HubSource("pacc", "PaCC") { PaCCProvider() },
        HubSource("realtime", "Real Time") { RealtimeProvider() },
        HubSource("toonitalia", "Toonitalia") { ToonitaliaProvider() },
        HubSource("animeunity", "AnimeUnity") { AnimeUnityProvider() },
        HubSource("animesaturn", "AnimeSaturn") { AnimeSaturnProvider() },
        HubSource("animeworld", "AnimeWorld") { AnimeWorldProvider() }
    )

    val tmdbRows: List<HubTmdbRow> = listOf(
        HubTmdbRow("trending", true, TmdbRow("Tendenze della settimana", "trending/all/week")),
        HubTmdbRow("movie_popular", true, TmdbRow("Film popolari", "movie/popular", forcedTv = false)),
        HubTmdbRow("tv_popular", true, TmdbRow("Serie TV popolari", "tv/popular", forcedTv = true)),
        HubTmdbRow("movie_top", true, TmdbRow("Film più votati", "movie/top_rated", forcedTv = false)),
        HubTmdbRow("tv_top", true, TmdbRow("Serie TV più votate", "tv/top_rated", forcedTv = true)),
        HubTmdbRow("movie_now", false, TmdbRow("Al cinema", "movie/now_playing", "region=IT", forcedTv = false)),
        HubTmdbRow("movie_upcoming", false, TmdbRow("Film in arrivo", "movie/upcoming", "region=IT", forcedTv = false)),
        HubTmdbRow("tv_on_air", false, TmdbRow("Serie TV in onda", "tv/on_the_air", forcedTv = true))
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("prisynx_hub", Context.MODE_PRIVATE)
        ProviderOptions.torrentMaxResults = torrentMax
    }

    private fun bool(key: String, def: Boolean) = prefs?.getBoolean(key, def) ?: def
    private fun setBool(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    // ------------------------------------------------------------ providers

    fun orderedSources(): List<HubSource> {
        val saved = prefs?.getString("source_order", null)
            ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        val byId = sources.associateBy { it.id }
        val ordered = saved.mapNotNull { byId[it] }
        return ordered + sources.filter { it !in ordered }
    }

    fun saveOrder(order: List<HubSource>) {
        prefs?.edit()?.putString("source_order", order.joinToString(",") { it.id })?.apply()
    }

    fun isSourceEnabled(id: String) = bool("source_on_$id", true)
    fun setSourceEnabled(id: String, on: Boolean) = setBool("source_on_$id", on)

    fun enabledSources(): List<HubSource> = orderedSources().filter { isSourceEnabled(it.id) }

    /** Max IlCorsaroViola torrents in the stream list. */
    var torrentMax: Int
        get() = prefs?.getInt("torrent_max", ProviderOptions.DEFAULT_TORRENT_MAX) ?: ProviderOptions.DEFAULT_TORRENT_MAX
        set(value) {
            val v = value.coerceIn(1, 20)
            prefs?.edit()?.putInt("torrent_max", v)?.apply()
            ProviderOptions.torrentMaxResults = v
        }

    // ------------------------------------------------------------ TMDB rows

    fun isTmdbRowEnabled(row: HubTmdbRow) = bool("tmdb_on_${row.id}", row.defaultOn)
    fun setTmdbRowEnabled(row: HubTmdbRow, on: Boolean) = setBool("tmdb_on_${row.id}", on)

    // ------------------------------------------------------------ TopX

    var topxEnabled: Boolean
        get() = bool("topx_on", true)
        set(value) = setBool("topx_on", value)

    var topxFirst: Boolean
        get() = bool("topx_first", true)
        set(value) = setBool("topx_first", value)

    var topxUrl: String
        get() = prefs?.getString("topx_url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TOPX_URL
        set(value) {
            prefs?.edit()?.putString("topx_url", value.trim())?.apply()
        }

    fun isTopxCatalogEnabled(key: String) = bool("topx_cat_$key", true)
    fun setTopxCatalogEnabled(key: String, on: Boolean) = setBool("topx_cat_$key", on)

    // ------------------------------------------------------------ RaiPlay

    var raiHome: Boolean
        get() = bool("rai_home", true)
        set(value) = setBool("rai_home", value)

    var raiSearch: Boolean
        get() = bool("rai_search", true)
        set(value) = setBool("rai_search", value)

    fun isRaiRowEnabled(key: String) = bool("rai_row_$key", true)
    fun setRaiRowEnabled(key: String, on: Boolean) = setBool("rai_row_$key", on)

    // ------------------------------------------------------------ Anime (Kitsu)

    var kitsuHome: Boolean
        get() = bool("kitsu_home", true)
        set(value) = setBool("kitsu_home", value)

    var kitsuSearch: Boolean
        get() = bool("kitsu_search", true)
        set(value) = setBool("kitsu_search", value)

    /** Hide Japanese animation from TMDB rows/search (Kitsu handles anime). */
    var hideTmdbAnime: Boolean
        get() = bool("kitsu_hide_tmdb", true)
        set(value) = setBool("kitsu_hide_tmdb", value)

    fun isKitsuRowEnabled(id: String) = bool("kitsu_row_$id", true)
    fun setKitsuRowEnabled(id: String, on: Boolean) = setBool("kitsu_row_$id", on)

    // ------------------------------------------------------------ home order

    val homeGroups: List<Pair<String, String>> = listOf(
        "topx" to "Cataloghi TopX",
        "tmdb" to "Cataloghi TMDB",
        "kitsu" to "Anime (Kitsu)",
        "rai" to "RaiPlay"
    )

    fun homeOrder(): List<String> {
        val ids = homeGroups.map { it.first }
        val saved = prefs?.getString("home_order", null)
            ?.split(',')?.filter { it in ids }?.distinct() ?: emptyList()
        val fallback = if (topxFirst) listOf("topx", "tmdb", "kitsu", "rai") else listOf("tmdb", "topx", "kitsu", "rai")
        return saved + fallback.filter { it !in saved }
    }

    fun saveHomeOrder(order: List<String>) {
        prefs?.edit()?.putString("home_order", order.joinToString(","))?.apply()
    }

    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
        ProviderOptions.torrentMaxResults = ProviderOptions.DEFAULT_TORRENT_MAX
    }
}
