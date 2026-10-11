# CloudStream Film Repo — Güvenilirlik Altyapısı Tasarımı

Tarih: 2026-10-11  
Durum: Tasarım incelemesi için taslak  
Kapsam: `berr49300/cloudstream-film-repo`  
Koruma kuralı: FilmMakinesi sağlayıcısı kullanıcı açıkça yeniden başlatılmasını istemedikçe yeniden oluşturulmayacak veya değiştirilmemelidir.

## 1. Hedefler ve başarı ölçütleri

Bu çalışma, yeni sağlayıcıların daha öngörülebilir biçimde eklenmesini ve hataların yayın öncesinde yakalanmasını hedefler. Üçüncü taraf siteler değişebildiği, erişimi engelleyebildiği veya oynatıcı servisleri bozulabildiği için sıfır hata garantisi verilemez. Başarı; tekrarlanabilir testler, açık doğrulama raporları, güvenli yayın kapıları ve bilinen hataların regresyon testleriyle ölçülür.

Başarı ölçütleri:
- Her PR'da otomatik derleme ve birim testleri çalışır.
- Test başarısızlığı yayın sürecini engeller.
- `builds` dalına yayın yalnızca açıkça başlatılan yayın iş akışıyla yapılır; sıradan test/PR akışı yayın yapmaz.
- Her yeni sağlayıcıda en azından ayrıştırıcı testleri, depo/paket doğrulaması ve belgelenmiş manuel oynatma kontrol listesi bulunur.
- Canlı test sonuçları `PASS`, `FAIL` veya `BLOCKED` olarak ayrılır; erişim engeli gerçek kod hatası gibi raporlanmaz.
- Hiçbir test gerçek cihazda oynatma doğrulanmadığı hâlde çalışır oynatma iddiasında bulunmaz.
- Dizilla ve FullHDFilmizlesene korunur; FilmMakinesi bu tasarımın uygulama kapsamı dışındadır.

## 2. Önerilen mimari

### A. Kotlin sağlayıcı ve birim testleri
Kotlin, CloudStream sağlayıcıları ve ayrıştırıcıların ana dili olarak kalır. Ayrıştırıcıların test edilebilir bölümleri mümkün olduğunca saf fonksiyonlara ayrılır. HTML/JSON örnekleri, iframe adresleri, göreli URL'ler, yönlendirme ve altyazı ayrıştırma davranışları için sabit test girdileri kullanılır. Hassas oturum belirteçleri, kişisel bilgiler veya geçici imzalı bağlantılar test örneklerine eklenmez.

### B. Python depo doğrulama araçları
Python, uygulama kodunun yerine geçmez; tekrarlanabilir kalite kontrolleri için kullanılır:
- eklenti manifestosu ve `.cs3` çıktısı tutarlılığı,
- beklenen modül/konfigürasyon dosyalarının varlığı,
- sağlayıcı kabul kontrol listesinin bulunması,
- yanlışlıkla eklenmiş sırlar ve şüpheli geçici dosyalar için sınırlı kontroller,
- test sonuçlarının okunabilir özet raporu.

İlk sürümde bu kontroller küçük, bağımlılığı az ve çevrimdışı çalışabilir olmalıdır. Ağ erişimi gerektiren testler ayrı tutulur.

### C. GitHub Actions kalite kapıları
İş akışları iki sorumluluğa ayrılır:
1. **CI / doğrulama:** PR'larda ve ana dala yönelik değişikliklerde derleme, birim testleri, depo doğrulaması ve paket doğrulaması.
2. **Yayın:** Yalnızca manuel olarak başlatılan, başarılı doğrulama çıktısını kullanan ayrı bir süreç. Varsayılan davranış yayın yapmamak olmalıdır.

Yayın süreci başlamadan önce hedef dal, paket sürümü ve yayımlanacak sağlayıcılar görünür olmalıdır. CI başarısızsa yayın yapılamamalıdır. Mevcut iş akışındaki tetikleyiciler ve `publish` davranışı uygulama aşamasında yeniden incelenip bu ayrım güvenli şekilde uygulanacaktır.

### D. Canlı site ve oynatma doğrulaması
Canlı kontroller ayrı bir katman olacaktır. Üçüncü taraf sitelerin erişilebilirliği, HTTP yanıtı, beklenen sayfa/oynatıcı işaretleri ve kaynak bağlantısı çıkarımı mümkün olduğu ölçüde denetlenir. CAPTCHA, coğrafi kısıtlama, anti-bot veya geçici ağ engeli varsa sonuç `BLOCKED` olur. Canlı testlerin her PR'da zorunlu olup olmayacağı; kararlılık ve erişim koşulları görüldükten sonra belirlenecektir.

Gerçek CloudStream uygulamasında oynatma, altyazı ve ses/parça seçimi cihaz üzerinde ayrıca kontrol edilir. CI'daki HTTP veya ayrıştırıcı testi tek başına gerçek oynatmayı kanıtlamaz.

## 3. AI destekli geliştirme

Depoda asistanlara yönelik talimat dosyaları önerilir:
- `AGENTS.md`: genel geliştirme, test ve güvenlik kuralları,
- `.github/copilot-instructions.md`: Copilot için depo özelindeki inceleme kuralları,
- `GEMINI.md`: Gemini tabanlı araçlar desteklediğinde kullanılacak proje bağlamı.

Bu dosyalar birbirleriyle çelişmemeli ve aynı temel kuralları paylaşmalıdır. AI çıktıları kanıt sayılmaz: önerilen her değişiklik test, diff incelemesi ve uygun olduğunda ikinci incelemeyle doğrulanır. AI araçları için ilk aşamada API anahtarı, otomatik ücretli çağrı veya gizli bilgi paylaşımı gerektiren entegrasyon eklenmez.

AI incelemesinde öncelikler: derleme/test sonuçları, bozuk URL birleştirme, yanlış kaynak seçimi, altyazı kaybı, hata yutma, güvenli olmayan yönlendirme, sırların sızması ve yayın akışının yanlış tetiklenmesi.

## 4. Tablet üzerinden çalışma

Kullanıcı Android tablet kullanıyor ve yerel masaüstü Android Studio kurulumu bu iş akışının ön koşulu olmayacak. Kod düzenleme ve Gradle çalıştırma için GitHub Codespaces bir seçenek olarak kullanılabilir; ancak Codespaces oluşturma/başlatma işlemi mevcut entegrasyon araçlarıyla doğrudan yapılamıyorsa kullanıcı tarayıcıda yalnızca gereken onayı veya tıklamayı yapar. ChatGPT, Codespaces terminaline kendiliğinden bağlı kabul edilmemelidir.

GitHub Actions bulutta derleme/test için kullanılır. Codespaces veya Actions sonuçları gerçek Android/CloudStream cihaz testinin yerine geçmez. Gemini uygulaması bağımsız inceleme için kullanılabilir; Copilot özelliklerinin kullanılabilirliği hesap/plan koşullarına bağlıdır.

## 5. Uygulama sırası

1. Bu tasarımın kullanıcı tarafından incelenip onaylanması.
2. Ayrı bir uygulama planının hazırlanması ve ayrıca onaylanması.
3. İzole bir dalda CI/yayın ayrımının uygulanması.
4. Var olan testlerin ve sağlayıcı doğrulama araçlarının değerlendirilip eksiklerin eklenmesi.
5. Sağlayıcı kabul şablonu ve AI talimatlarının eklenmesi.
6. CI sonuçları ve değişiklik farklarının incelenmesi; ana dala birleştirme ve yayın ayrı onay gerektirir.
7. Dizilla ve FullHDFilmizlesene için regresyon testleri ve cihaz kontrol listesi.
8. FilmMakinesi'ne ilişkin ayrı bir iş, yalnızca kullanıcı açıkça talep ettiğinde başlatılır.

## 6. Kapsam dışı

- FilmMakinesi'ni yeniden oluşturmak veya değiştirmek.
- Üçüncü taraf erişim kısıtlamalarını aşmak.
- Sıfır hata veya her kaynağın sürekli çalışacağı garantisi vermek.
- İlk aşamada ücretli AI API'leri veya gizli anahtarlar gerektiren otomasyon.
- Onay alınmadan `main` veya `builds` dalına yayın yapmak.

## 7. Açık kararlar

Uygulama planından önce kullanıcı şu konuları değerlendirebilir:
- Canlı site testleri ilk sürümde yalnızca manuel mi, yoksa sınırlı zamanlanmış kontroller de olsun mu?
- Yayın her zaman manuel onaylı mı kalsın? Öneri: evet.
- Copilot/Gemini incelemesi başlangıçta manuel mi olsun? Öneri: evet; otomatik entegrasyon daha sonra değerlendirilir.
