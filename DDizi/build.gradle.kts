version = 9

cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "DDizi v9 hızlı kaynak çözümleme ve sınırlı yedek arama"
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
