android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 1

cloudstream {
    description = "DualSync: video 4K/FHD con audio ITA sincronizzato."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/DualSync.png"
}