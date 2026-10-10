# CloudStream Film Repo

CloudStream için site sağlayıcıları ve otomatik derleme/yayınlama altyapısı. Şu anda bu depoda Dizilla sağlayıcısı bulunur; yeni siteler ayrı modüller olarak eklenir.

## CloudStream'e ekleme

1. CloudStream uygulamasını aç.
2. **Ayarlar → Eklentiler → Depo ekle** bölümüne gir.
3. Şu adresi ekle:

   `https://raw.githubusercontent.com/berr49300/cloudstream-film-repo/builds/repo.json`

4. Depoda **Dizilla** eklentisini kur.

Ana dal kaynak kodunu, `builds` dalı ise derlenmiş `.cs3` dosyasını ve `plugins.json` dizinini içerir. `main` dalına her push yapıldığında GitHub Actions birim testlerini çalıştırır, eklentileri derler ve başarılı çıktıları `builds` dalına yayımlar.

## Mimari

- Her site için ayrı `MainAPI` sağlayıcı modülü.
- Sitelerin ortak kullandığı HTML/JSON/URL işleme işlemleri için `shared/` kaynak kodu.
- Oynatıcı ailelerine özgü bağlantı çözücüleri (`ExtractorApi`).
- Bütün modüller için otomatik birim testleri ve derleme/yayınlama hattı.

Bir siteyi eklemeden önce hangi arama API'sini kullandığı, dizi/bölüm verisinin nereden geldiği, iframe/video kaynağının nasıl sunulduğu, gerekli başlıklar ve varsa token/şifreleme biçimi araştırılır. Alan adları ve parametreler tahminle varsayılmaz. Ayrıntılı kararlar ve test kapıları için [Sağlayıcı mimarisi belgesi](docs/PROVIDER_ARCHITECTURE.md) dosyasına bak.

## Dizilla sağlayıcısı

Dizilla'nın arama yanıtı `/api/bg/searchContent` üzerinden alınır; ayrıntı ve bölüm verileri sayfanın `__NEXT_DATA__` içindeki `secureData` alanından çözülür. Bölüm kaynakları ayrı ayrı işlenir: yaygın HTML/JSON/URL biçimleri ortak ayrıştırıcıdan geçirilir, ContentX/Pichive iframe'leri ilgili extractor'a gönderilir, diğer hostlar CloudStream extractor kayıt defterinde aranır.

Bu yaklaşım yaygın kaynak biçimlerinin kaçırılmasını azaltır fakat her video hostunun kendine özgü API, imza veya token mantığını otomatik olarak çözmez. Yeni bir host özel protokol kullanıyorsa ona özel extractor gerekir.

## Bilinen sınırlar

- **Derlemenin başarılı olması çalışma zamanında her içeriğin açılacağını kanıtlamaz.** Canlı site, kaynak hostu, gerekli referer/başlıklar ve gerçek video akışı ayrıca doğrulanır.
- `ERROR_CODE_DECODING_FAILED` / `NO_EXCEEDS_CAPABILITIES` gibi hatalar, doğru URL alınmış olsa bile cihazın video profilini/bit derinliğini çözümleyememesinden kaynaklanabilir. Sağlayıcı, sunulan videoyu kendi başına farklı codec'e dönüştürmez; uyumlu alternatif kaynak varsa onu sunmak gerekir.
- HTTP 403, CAPTCHA, oturum gereksinimi ve sitenin değişen API/HTML yapısı ayrı teşhislerdir. Erişim denetimi veya DRM atlatılmaz.

## Derleme ve test

Gereksinimler: JDK 17, Android SDK ve Gradle 8.12. Yerel derleme:

```bash
gradle testDebugUnitTest make makePluginsJson
```

CI aynı test ve derleme adımlarını uygular. Testler geçmeden yayın yapılmamalıdır.

## Lisans

Bu depodaki özgün kod MIT lisansı ile sunulur. CloudStream ve üçüncü taraf extractor'lar kendi lisanslarına tabidir.
