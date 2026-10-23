android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 2

cloudstream {
    description = "PaCC (partite.cc): eventi sportivi in diretta e replay."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Live", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/PaCC.png"
}