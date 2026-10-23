android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 1

cloudstream {
    description = "CB01: film e serie TV in streaming."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/CB01.png"
}