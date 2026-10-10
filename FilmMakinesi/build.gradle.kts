version = 4

cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "FilmMakinesi film/dizi sağlayıcısı; HTML, oynatıcı, video ve altyazı ayrıştırma"
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=filmmakinesi.to"
}

dependencies {
    // The provider Gradle plugin keeps the runtime CloudStream artifact in its
    // dedicated "cloudstream" configuration; parser tests also need it on test classpath.
    testImplementation(configurations.getByName("cloudstream"))
}
