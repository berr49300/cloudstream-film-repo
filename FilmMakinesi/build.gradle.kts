version = 13

cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "FilmMakinesi film/dizi sağlayıcısı; HTML, oynatıcı, video ve altyazı ayrıştırma"
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=filmmakinesi.to"
}


dependencies {
    // ExtractorApi is supplied through the CloudStream compile configuration;
    // make it available to JVM tests that exercise the extractor companion.
    testImplementation("com.lagradost:cloudstream3:pre-release")
}
