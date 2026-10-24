android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 3

cloudstream {
    description = "AnimeUnity: anime sub ITA e ITA con mapping TMDB."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/AnimeUnity.png"
}