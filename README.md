# CloudStream Film Repo — Dizilla

Bu depo, CloudStream için **Dizilla'nın herkese açık HTML sayfalarını** okuyan bir sağlayıcı ve otomatik derleme/yayınlama altyapısı içerir.

## CloudStream'e ekleme

1. CloudStream uygulamasını aç.
2. **Ayarlar → Eklentiler → Depo ekle** bölümüne gir.
3. Şu depo adresini ekle:

   https://raw.githubusercontent.com/berr49300/cloudstream-film-repo/builds/repo.json

4. Depoda **Dizilla** eklentisini açıp **Yükle** seçeneğine bas.

İlk yayınlama, GitHub Actions derlemesi tamamlandıktan sonra oluşur. Ana dal kaynak kodunu, builds dalı ise derlenmiş .cs3 dosyasını ve CloudStream'in kullandığı plugins.json manifestini içerir.

## Sağlayıcı özellikleri

- Dizilla'nın ana sayfası ve tanımlı katalog sayfaları için listeleme.
- Kullanıcıya açık HTML arama sayfasından arama.
- Dizi detay sayfasından başlık, kapak görseli, açıklama, yıl ve sayfa üzerinde açıkça listelenen bölüm bağlantılarını okuma.
- Bölüm sayfasının HTML'sinde açık olarak yer alan standart video/iframe kaynaklarını CloudStream'in yerleşik extractor'larına gönderme.
- Bölüm sezon/bölüm numaralarını URL veya görünen bölüm metninden ayıklamak için birim testleri.
- main dalına her push'ta otomatik test, derleme ve builds dalına yayınlama.

## Durum ve sınırlamalar

Bu sağlayıcı, site tarafından normal HTML içinde herkese açık olarak sunulan verilerle sınırlıdır. Site erişim engeli (ör. HTTP 403), JavaScript arkasında tutulan kapalı veriler veya izin gerektiren/korumalı oynatma kaynakları nedeniyle bazı sayfalar ya da oynatıcılar çalışmayabilir. Bu proje erişim denetimini aşmaz, gizlenmiş sayfa verilerinin şifresini çözmez ve DRM/abonelik korumasını atlatmaz. Dolayısıyla alan adının herkesten erişilebilir olduğu veya her oynatıcının çalıştığı doğrulanmış değildir.

Site tasarımını değiştirdiğinde HTML seçicileri güncellemek gerekebilir. Çalışmayan bir sayfa için mümkünse sayfanın herkese açık HTML yapısına dair bilgiyle bir GitHub issue aç.

## Kaynak kodunu yerel derleme

Gereksinimler: JDK 17, Android SDK ve Gradle 8.12.

~~~bash
gradle :Dizilla:testDebugUnitTest
gradle make makePluginsJson
~~~

Derleme başarılı olduğunda .cs3 dosyası üretilir. GitHub Actions aynı işi otomatik yapıp artefaktları builds dalına yayımlar.

## Dosya yapısı

~~~
.
├── .github/workflows/build.yml
├── Dizilla/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/com/kra/cloudstream/dizilla/
│       │       ├── Dizilla.kt
│       │       ├── DizillaParser.kt
│       │       └── DizillaPlugin.kt
│       └── test/kotlin/com/kra/cloudstream/dizilla/
│           └── DizillaParserTest.kt
├── build.gradle.kts
├── gradle.properties
├── repo.json
└── settings.gradle.kts
~~~

## Lisans

Bu depodaki özgün kod MIT lisansı ile sunulur. CloudStream ve üçüncü taraf extractor'lar kendi lisanslarına tabidir.
