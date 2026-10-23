package prisynx.providers

/**
 * Tuple of four values shared by the anime providers (AnimeSaturn/AnimeUnity/AnimeWorld).
 * It used to live inside AnimeSaturnProvider; it is now in the shared source set so each
 * provider module can compile independently.
 */
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
