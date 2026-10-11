# Uygulama Planı — Güvenilirlik Altyapısı

Tarih: 2026-10-11  
Durum: İnceleme için plan; henüz uygulanmadı  
Depo: `berr49300/cloudstream-film-repo`  
Çalışma dalı: `infra/reliability-design`

## İncelemeden çıkan mevcut durum

- `.github/workflows/build.yml` zaten Java 17, Android SDK, Gradle 8.12 kuruyor; `gradle testDebugUnitTest make makePluginsJson` komutunu çalıştırıyor, paket/index çıktısını kontrol ediyor ve artefakt yüklüyor.
- Aynı workflow şu anda `main` dalına yapılan her push'ta yayın adımını da çalıştırıyor. Manuel `workflow_dispatch` için `publish=false` varsayılanı mevcut olsa da `main` push'u otomatik olarak yayın tetiklediği için tasarımdaki “test ve yayın ayrıdır, yayın manuel onaylıdır” hedefine ters düşüyor. İlk öncelik bunu güvenli şekilde ayırmak.
- Depoda zaten `docs/SITE_DISCOVERY_PLAYBOOK.md`, `docs/NEW_PROVIDER_ACCEPTANCE_CHECKLIST.md`, `docs/SITE_PROFILE_TEMPLATE.md` ve sağlayıcı mühendislik notları bulunuyor. Bunları baştan yazmak yerine eksik kalan otomasyon ve asistan talimatları tamamlanmalı.
- Gradle ayarları `shared/src/main/kotlin` ve `shared/src/test/kotlin` kaynaklarını sağlayıcı modüllerine ekliyor; dolayısıyla testler modüller üzerinden çalışacak şekilde tasarlanmış.
- Bu plan kapsamında sağlayıcı kaynak kodları değiştirilmeyecek. Dizilla ve FullHDFilmizlesene korunacak. FilmMakinesi kodu yeniden oluşturulmayacak veya değiştirilmeyecek.

## Değişiklik 1 — Yayın güvenliği (en yüksek öncelik)

Dosya: `.github/workflows/build.yml`

1. CI tetikleyicisini PR'lar ve `main` push'ları için doğrulama/derleme/test ile sınırla.
2. `main` push'unun doğrudan `builds` dalına yazmasına izin veren koşulu kaldır.
3. Yayın adımını açıkça manuel başlatılan `workflow_dispatch` ve `publish=true` koşuluna bağla. Varsayılan `false` kalmalı.
4. İş izinlerini mümkün olduğunca daralt: doğrulama işi için salt okunur içerik izni; yayın işi için yalnızca gereken yazma izni. Yayın işini ayrı job olarak ayırmak, test başarısız olduğunda yayın yolunun çalışmasını engellemek için tercih edilir.
5. Artefaktın beklenen modüllerden üretildiğini, `plugins.json` biçimini ve paket adlarını kontrol et. Yayın öncesinde `builds` çıktısının kaynak dalı silme/temizleme işlemleriyle yanlışlıkla bozulmaması için mevcut yayın davranışını dikkatle koru.
6. Aynı anda birden fazla yayın işleminin birbirini ezmesini engelleyen concurrency koruması bulunsun.

Kabul testi:
- PR ve `main` push'u test/derleme çalıştırır ama `builds` dalına yazmaz.
- Manuel çalıştırma `publish=false` iken yayın yapmaz.
- Manuel çalıştırma `publish=true` iken yalnızca tüm doğrulamalar başarılı olursa yayın yapar.
- İş akışında YAML sözdizimi, izinler ve artefakt yolları gözden geçirilir.

## Değişiklik 2 — Depo ve paket doğrulama aracı

Yeni dosya önerisi: `tools/validate_repo.py`

Küçük, harici bağımlılıksız bir Python aracı:
- Sağlayıcı modüllerinde beklenen temel Gradle ve kaynak dosyalarının varlığını kontrol eder.
- `repo.json` ve üretildiğinde `plugins.json` JSON yapısını doğrular.
- Derleme sonrasında en az bir `.cs3` dosyası üretildiğini ve artefakt yolunun boş olmadığını kontrol eder.
- Test veya kaynak kodlarında açıkça tanımlanmış gizli bilgi desenleri için temel uyarı üretir; bu kontrol bir güvenlik tarayıcısının yerine geçmez.
- Başarısızlıkta sıfır olmayan çıkış kodu verir; başarılı/başarısız kontrolleri okunaklı şekilde listeler.

Aracı önce sabit test girdileriyle doğrula; mevcut sağlayıcı kodlarını değiştirmesin ve ağ erişimi gerektirmesin.

Kabul testi:
- Geçerli örnekler PASS verir.
- Bozuk JSON, eksik dosya veya eksik artefakt kontrollü biçimde FAIL verir.
- Canlı site erişimi bu araca dahil edilmez.

## Değişiklik 3 — CI kalite kapıları

Dosya: `.github/workflows/build.yml` (yayın güvenliği değişikliğinden sonra)

- Python depo doğrulamasını Gradle doğrulamasına ekle.
- Birim test, paket üretimi, manifest/index doğrulaması ve artefakt yüklemesi adımlarını açıkça adlandır.
- Hata çıktısında hangi adımın başarısız olduğu belli olsun.
- GitHub Actions'ın mevcut ücretsiz kullanım kotasını gözetmek için her PR'da sınırsız canlı site taraması çalıştırma. İlk aşamada CI deterministik, sabit girdili testlerle sınırlı olsun.
- Üçüncü taraf site erişimi gereken testler daha sonra sınırlı, zamanlanmış veya manuel ayrı bir iş olarak değerlendirilsin; CAPTCHA/403/erişim kısıtları PASS sayılmasın, BLOCKED raporlansın.

## Değişiklik 4 — AI asistan talimatları

Yeni dosyalar:
- `AGENTS.md`
- `.github/copilot-instructions.md`
- `GEMINI.md`

Dosyalar aynı ortak kuralları yansıtmalı:
- Önce ilgili sağlayıcıyı, ortak yardımcıları, testleri ve mevcut belgeleri oku.
- Site API'si, HTML seçicisi, iframe zinciri, altyazı veya oynatma davranışı hakkında tahminde bulunma; kanıtı belirt.
- Değişiklikten sonra uygun testleri çalıştır ve sonucu dürüstçe raporla.
- Gerçek cihazda test edilmediyse gerçek oynatma çalışıyor iddiasında bulunma.
- Test veya günlüklerde oturum çerezleri, tokenlar ve imzalı medya URL'leri tutma.
- FilmMakinesi açık kullanıcı talebi olmadan oluşturulmayacak/değiştirilmeyecek.
- `main` veya `builds` dalına doğrudan yayın/merge yapılmayacak; yayın manuel kapıdan geçecek.
- AI önerisi tek başına doğrulama sayılmaz.

Talimatlar birbiriyle çelişmemeli; uzun ve yinelenen kurallar yerine kısa, uygulanabilir kontrol listeleri kullanılsın.

## Değişiklik 5 — Sağlayıcı test şablonu ve hata öğrenme döngüsü

- Mevcut `docs/SITE_PROFILE_TEMPLATE.md` ve kabul kontrol listesi korunur; yeni bir site için test raporu şablonu eklenir veya mevcut şablon yeterliyse ona küçük eklemeler yapılır.
- Rapor her alanı `PASS / FAIL / BLOCKED / NOT TESTED` olarak ayırır: katalog/arama, detay/episode eşlemesi, kaynak çıkarımı, altyazı, performans, paketleme ve gerçek cihaz.
- Her gerçek arıza için belirti → aşama → kanıt → kök neden → düzeltme → regresyon testi → sonuç kaydedilir.
- Önce ortak ayrıştırıcılar ve mevcut testler incelenir; gereksiz test altyapısı veya yeni programlama dili eklenmez.

## Uygulama ve doğrulama sırası

1. Bu planı incele; henüz uygulama yapma.
2. Uygulama onayı alındıktan sonra yalnızca `infra/reliability-implementation` gibi ayrı bir dalda çalış.
3. Önce yayın tetikleme güvenliğini düzelt; farkı ve yeni davranışı kontrol et.
4. Python doğrulama aracını testlerle ekle; ardından CI'a bağla.
5. AI talimatları ve sağlayıcı rapor şablonunu ekle.
6. Mevcut Gradle testleri ve derleme komutunu çalıştır. Araç ortamı bunları çalıştıramıyorsa bunu açıkça belirt; çalıştırılmamış testi geçmiş gibi raporlama.
7. CI sonuçlarını, dosya farklarını ve yayın koşullarını gözden geçir.
8. PR açılması ve `main` dalına birleştirme ayrı karar olarak kalır; `builds` yayını ayrıca manuel onay gerektirir.

## Maliyet ve güvenlik sınırları

- Codespaces kullanılmasını gerektirmez.
- Gemini/Copilot API anahtarı, ücretli çağrı veya yeni ücretli servis eklemez.
- GitHub Actions ücretsiz kotasını aşmayacağı garanti edilemez; iş sayısı ve çalışma süresi düşük tutulur ve hesap kotası kullanıcı tarafından kontrol edilir.
- Gerçek CloudStream uygulaması/cihazı testinin yerine CI sonucu konmaz.
- Sıfır hata garantisi verilmez; amaç hataları daha erken ve kanıtla yakalamaktır.

## Onaydan sonra uygulanacak ilk küçük dilim

İlk PR yalnızca şunları kapsar:
1. Otomatik `main` push yayınını kaldırıp yayın akışını manuel kapıya bağlamak.
2. PR/main doğrulama iş akışının yayın yapmadığını doğrulamak.
3. Bu davranış ve test adımlarını belgelemek.

İlk dilim güvenli çalıştığı doğrulanmadan başka otomasyon eklenmez.
