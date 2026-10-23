android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 2

cloudstream {
    description = "Eurostreaming: film e serie TV in streaming."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/Eurostreaming.png"
}