android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 1

cloudstream {
    description = "Toonitalia: cartoni animati e anime in italiano."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/Toonitalia.png"
}