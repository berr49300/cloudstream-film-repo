version = 22
cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "FilmMakinesi film/dizi sağlayıcısı; HTML, oynatıcı, video ve altyazı ayrıştırma"
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=filmmakinesi.to"
}

dependencies {
    // CloudStream's Android runtime includes Rhino; compile against it here.
    "compileOnly"("org.mozilla:rhino:1.8.1")
    // Unit tests execute the same bounded resolver on the JVM.
    "testImplementation"("org.mozilla:rhino:1.8.1")
}
