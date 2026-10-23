package prisynx.providers

/** Filmez: WordPress film catalogue, MaxStream behind uprot's image captcha. */
class FilmezProvider : WpUprotProvider(
    "Filmez",
    "https://filmez.club",
    filmType = "movie",
    seriesType = null
)
