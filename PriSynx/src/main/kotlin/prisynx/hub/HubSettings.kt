package prisynx.hub

import android.content.Context
import android.content.SharedPreferences
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
        "https://topx.stremio-italia.eu/c/42cYMqtV1jf6w-NDv64rEGp7Dd5hltffL_DQkFUq2lNo2XVo4KLUMeGAn6e20F9HzjSsH908_oJOHYhWosfPEwR_dPgWNXOZ7VyO_UFL_uPkXxjtHsyb_tTuqy6V7IYiVcrSDRfnmOyAfPBfVXR7zG9oKnefi9uGPmfDOiOvG6R95BC9nNccl8Q8_xXyJTXBxcQOraVCnUOPvM-FIWtdktTlDn0_CHltQnDnS_czAGDyPKO3k9aDLbJtHGtkp_HKsQDhSCBZwNmBxqlewYl2cVBYbxqrrp819Ix0HE0t_he0aa59qp1dzyDcUy4JL1bYrQR083AdT_tKnSs_RWWHb79xA8R8GIT9sJRwaKmwtIXTeREPGLdrpIorX4HGhduwaZOChVECnuikAQoEFE0nxWJ-ita3Sfb3BEieAWoq2_HLYhBxcAsaEfRyj5BmkF7WVTxArvCp-xu_wRM06C4Wfx9VOXkLJhlWoxnsCigJWQfpqwiRCyt-j9pqLJch3wSUdmS788CnXNUgdBd0L-Z67k0t2wQZanuHGuNZC8mpk8Wz7OR5HOEtF61tHtguE_HX4ApFeSkytpHGpY1ZrhGNXVk6rctcPyyujBva_uX0J3BZKA8NqCNGbowrYQT35z7LDwNBms9xqlBTmOFXLDGONxTg5SM06JIVzJOwkf_haB2Ip3_BFwkg8uZMz20HVfo/manifest.json"

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

    fun resetAll() {
        prefs?.edit()?.clear()?.apply()
    }
}
