android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 1

cloudstream {
    description = "AnimeWorld: anime sub ITA e ITA con mapping TMDB."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/AnimeWorld.png"
}