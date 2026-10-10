# CloudStream Film Repo — TEST AŞAMASINDA

> **Geçici uyarı:** Dizilla sağlayıcısı test aşamasındadır. Lütfen bu depoyu şu an CloudStream'e eklemeyin. Herkese açık kurulum listesi geçici olarak kapatılmıştır; altyazı ve katalog testleri tamamlanınca yeniden açılacaktır.

Bu depo, CloudStream sağlayıcılarının kaynak kodunu ve derleme/test altyapısını barındırır. Kullanıcıya açık dağıtımın açılması için aşağıdaki kontrol listesinin tamamlanması gerekir: [Yeni sağlayıcı kabul kontrol listesi](docs/NEW_PROVIDER_ACCEPTANCE_CHECKLIST.md).

## Geliştirme mimarisi

- Her site için ayrı `MainAPI` sağlayıcı modülü.
- Birden fazla sitenin ortak kullandığı HTML/JSON/URL işlemleri `shared/` kaynak kodunda tutulur.
- Oynatıcı ailelerine özgü bağlantı çözücüleri ayrı `ExtractorApi` sınıflarında tutulur.
- Otomatik derleme ve birim testleri, gerçek CloudStream oynatma testiyle ayrı sonuçlar olarak değerlendirilir.
- Sağlayıcı hata ayıklama bulguları ve genellenebilir dersler [Provider Engineering Notes](docs/PROVIDER_ENGINEERING_NOTES.md) içinde tutulur.

## Dizilla — bilinen durum

- Video açılış hızı kullanıcı testinde iyileşmiştir.
- Altyazı menüsünde Türkçe/İngilizce seçenekleri görünebilse de uygulama altyazının yüklenmediğini bildiriyor; altyazı hâlâ **çözülmemiş** bir sorundur.
- Bazı ana sayfa kategori şeritleri boş görünüyor veya tür dağılımı beklenenden farklı; katalog doğrulaması tamamlanmamıştır.
- Doğrudan bölüm açma ve “Sonraki bölüm” davranışı ayrı ayrı doğrulanmalıdır.
- Derlemenin başarılı olması gerçek kaynakların ve altyazıların çalıştığını kanıtlamaz.

## Derleme

Gereksinimler: JDK 17, Android SDK ve Gradle 8.12.

```bash
gradle testDebugUnitTest make makePluginsJson
```

Lütfen kullanıcıya açık dağıtımı, gerçek CloudStream cihaz testleri tamamlanmadan tekrar etkinleştirmeyin.

## Lisans

Bu depodaki özgün kod MIT lisansı ile sunulur. CloudStream ve üçüncü taraf extractor'lar kendi lisanslarına tabidir.
