package prisynx.providers

/** OnlineSerieTV: WordPress film/series catalogue, MaxStream behind uprot's image captcha. */
class OnlineSerieTvProvider : WpUprotProvider(
    "OnlineSerieTV",
    "https://onlineserietv.mom",
    filmType = "film",
    seriesType = "serietv"
)
