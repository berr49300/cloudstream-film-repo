version = 7

cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "DDizi kaynak tanılama, tüm sezon sayfaları ve YouTube kalite yedeği"
    status = 3
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.ddizi.tel/images/logo.png"
}

dependencies {
    // Coroutines are supplied at runtime by the CloudStream host. This compile-only
    // dependency exposes their API for the bounded parallel episode-page requests
    // without packaging a second coroutines runtime into the plugin.
    "compileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}
