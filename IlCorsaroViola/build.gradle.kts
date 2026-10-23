android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 1

cloudstream {
    description = "IlCorsaroViola: torrent P2P con seeders e formati 4K/1080p."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/IlCorsaroViola.png"
}