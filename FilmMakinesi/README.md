# FilmMakinesi

CloudStream sağlayıcısı: https://filmmakinesi.to/

Kaynak çözme akışı sade tutulmuştur: oynatıcı iframe'i film sayfası referansı ve iframe gezinme başlıklarıyla yüklenir; CloseLoad/Rapid player içindeki JavaScript kaynak değişkeni sınırlı Rhino ortamında çözülür. Eski `file_link` Base64 formatı ve güncel `dc_hello` / dizi tabanlı akış biçimi yedek yol olarak desteklenir. Diğer bilinen oynatıcılar CloudStream'in `loadExtractor` mekanizmasına bırakılır.

Lisans: GPL-3.0-or-later.

CI derlemesi kaynak kodunun derlenip birim testlerinin çalıştığını doğrular; canlı sitede video oynatımının doğrulandığı anlamına gelmez. Gerçek cihaz oynatım testi henüz tamamlanmadı.
