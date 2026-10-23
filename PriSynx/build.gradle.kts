// PriSynx hub: one extension bundling every TMDB based PriSynx source.
// The provider sources of the other modules are copied in (without their
// *Plugin.kt entry points) so there is a single @CloudstreamPlugin class.
val hubSourceDir = layout.buildDirectory.dir("generated/hubsrc").get().asFile

val syncHubSources = tasks.register<Sync>("syncHubSources") {
    from(rootDir) {
        include("*/src/main/kotlin/**/*.kt")
        exclude("*/src/main/kotlin/**/*Plugin.kt")
        exclude("PriSynx/**", "RaiPlay/**", "shared/**")
    }
    into(hubSourceDir)
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
    sourceSets.getByName("main").java.srcDir(hubSourceDir)
}

tasks.named("preBuild").configure { dependsOn(syncHubSources) }

version = 6

cloudstream {
    description = "PriSynx: tutte le sorgenti in un'unica estensione, con menu per scegliere provider, ordine, cataloghi TMDB e TopX."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/PriSynx.png"
}

dependencies {
    // Stubs of the CloudStream app (android Plugin class with openSettings).
    "cloudstream"("com.lagradost:cloudstream3:pre-release")
}
