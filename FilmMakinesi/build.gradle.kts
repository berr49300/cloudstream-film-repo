version = 24
cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "FilmMakinesi katalog ve CloseLoad/Rapid bağlantı çözücüsü"
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=filmmakinesi.to"
}

dependencies {
    // CloudStream Android runtime includes Rhino; compile against it for the player resolver.
    "compileOnly"("org.mozilla:rhino:1.8.1")
    "testImplementation"("org.mozilla:rhino:1.8.1")
}
