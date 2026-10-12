version = 1
cloudstream {
    authors = listOf("berr49300")
    language = "tr"
    description = "Haty CanlıTribün spor sağlayıcısı (beta; oynatma doğrulaması bekliyor)"
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?sz=128&domain=canlitribun938.top"
}

dependencies {
    testImplementation("com.lagradost:cloudstream3:pre-release")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
