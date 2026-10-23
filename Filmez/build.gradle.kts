android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 1

cloudstream {
    description = "Filmez: catalogo film WordPress via MaxStream."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/PriSynx/main/assets/logos/Filmez.png"
}