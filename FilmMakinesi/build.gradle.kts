version = 29
cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "FilmMakinesi ve sadeleştirilmiş CloseLoad/Rapid çözücüsü"
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=filmmakinesi.to"
}

dependencies {
    // Rhino is provided by CloudStream; compile/test compile only.
    "compileOnly"("org.mozilla:rhino:1.8.1")
    "testImplementation"("org.mozilla:rhino:1.8.1")
}
