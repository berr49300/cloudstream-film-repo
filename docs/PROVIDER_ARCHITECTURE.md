# Sağlayıcı mimarisi ve geliştirme kapıları

## Temel karar

Bu depo tek bir “her siteyi otomatik çözen” sağlayıcıya dayanmayacak. Her web sitesinin katalog modeli, arama akışı, sayfa verisi, koruma mekanizması ve video oynatıcısı farklı olabilir. Bu nedenle her site, CloudStream `MainAPI` sözleşmesini uygulayan bağımsız bir sağlayıcı modülü olacak; ortak ve gerçekten tekrar eden işler paylaşılan kaynak kodunda tutulacak.

## Katmanlar

1. **Site sağlayıcısı (`MainAPI`)**: ana sayfa, arama, dizi/film ayrıntısı, sezon-bölüm ve sitenin kendi API/JSON/HTML akışı. `ckey`, `cValue`, imza veya şifreli alan isimleri genellenmez; bunlar yalnızca ilgili sitenin istek/yanıt biçiminde doğrulanır.
2. **Ortak kaynak ayrıştırma**: HTML/JSON içinde yaygın `src`, `data-src`, `url`, `file` gibi alanlardan HTTP(S) adaylarını çıkarmak ve göreli URL'leri normalize etmek. Bu, her oynatıcıyı çözme iddiası taşımaz.
3. **Oynatıcı çözücüleri (`ExtractorApi`)**: ContentX/Pichive gibi aynı oynatıcı ailesini paylaşan hostlar için ortak çözücü; imza, token, API veya özel JavaScript gerekiyorsa ayrı host/aile çözücüsü.
4. **Yayın hattı**: bütün sağlayıcıların birim testleri, eklenti derlemesi ve `plugins.json` üretimi; doğrulanan çıktılar `builds` dalına yayımlanır.

## Bir site için kod yazmadan önce zorunlu keşif

- Ana alan adı ve yönlendirme/ayna alan adları.
- Ana sayfa ve arama isteği; aramanın normal HTML, JSON API veya JavaScript arkasındaki verilerden hangisini kullandığı.
- Dizi/film ayrıntısı ve bölüm listesinin nereden geldiği; tek istek mi, ek API çağrıları mı?
- Bölümün kaynak verisi; iframe/oynatıcı hostu, gerekli referer/başlıklar ve birden fazla dil/kaynak olup olmadığı.
- Oynatıcının URL, JSON, imzalı URL, token veya şifreli yanıt kullanması; bu alanların gerçek istekte/yanıtta kanıtlanması.
- Kaynağın doğrudan medya mı yoksa iframe mi olduğu; HLS/DASH/MP4 türü ve olası cihaz codec kısıtları.
- Gerçek siteye erişimin test ortamından mümkün olup olmadığı. 403/CAPTCHA/oturum engeli varsa bu, boş sonuçtan farklı bir teşhistir.

İstek parametreleri veya şifreleme anahtarları tahmin edilmez. İlgili sitenin genel istemci kodu ve izin verilen istek/yanıt biçimi incelenir; korumalı erişim veya DRM atlatılmaz.

## Test kapıları

- Ortak URL/HTML/JSON aday ayrıştırıcısı için sabit örneklerle birim testleri.
- Her sağlayıcının arama, yükleme ve kaynak çıkarma ayrıştırıcıları için gerçek yanıttan hassas verileri çıkarılmış sabit test örnekleri.
- Derlemede bütün modüllerin test edilmesi; başarısız test varsa yayın adımına geçilmemesi.
- Canlı entegrasyon testi yalnızca erişilebilir bir örnek üzerinden ayrıca doğrulanır. “Derleme başarılı” ifadesi, bütün sitelerin canlı çalıştığı anlamına gelmez.

## Hata teşhisi: yanlış pozitif başarıyı engelle

- Arama boş: istek/parametre, HTTP yanıtı, JSON çözümleme ve sonuç modeli ayrı ayrı kontrol edilir.
- Dizi/bölüm görünmüyor: HTML/JSON modeli ve bölüm slug'ları kontrol edilir.
- Hiç video kaynağı bulunmuyor: bölüm kaynak alanı, iframe çıkarımı ve ExtractorApi çağrısı kontrol edilir.
- Oynatıcı siyah ekranda kalıyor: kaynak isteği, referer/başlık, playlist ve ağ erişimi kontrol edilir.
- `ERROR_CODE_DECODING_FAILED` veya `NO_EXCEEDS_CAPABILITIES`: bu, link çıkarma hatasından farklı bir medya codec/cihaz uyumluluğu sorunu olabilir. Uyumlu alternatif akış varsa sunulur; kaynağın kendisi desteklenmiyorsa sağlayıcı tek başına videoyu dönüştüremez.

## Ortak altyapının sınırı

Paylaşılan kod URL normalleştirme, yaygın HTML/JSON kaynak biçimlerini anlama ve test/yayın disiplinini sağlar. Siteye özgü API alanlarını, şifreleme biçimini, token üretimini, erişim kurallarını veya özel player protokolünü otomatik olarak tahmin edemez. Yeni site için hedef, bütün kodu kopyalamak değil; yalnızca kanıtlanmış site adaptörü/oynatıcı çözücüsü eklemektir.
