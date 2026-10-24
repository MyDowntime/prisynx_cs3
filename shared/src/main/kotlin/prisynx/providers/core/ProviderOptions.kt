package prisynx.providers.core

/** Runtime options set by the PriSynx hub menu; stand-alone plugins keep the defaults. */
object ProviderOptions {
    const val DEFAULT_TORRENT_MAX = 3

    @Volatile
    var torrentMaxResults: Int = DEFAULT_TORRENT_MAX

    /** MovyITA/DualSync: only the ITA links whose audio offset is known and applied. */
    @Volatile
    var syncedOnly: Boolean = true
}
