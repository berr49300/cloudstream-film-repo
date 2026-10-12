version = 1

cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "Haty DiziPal sağlayıcısı (beta; canlı oynatma doğrulaması bekliyor)"
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=dizipal1430.com"
}

dependencies {
    testImplementation("com.lagradost:cloudstream3:pre-release")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
