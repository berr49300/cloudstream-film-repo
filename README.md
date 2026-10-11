# CloudStream Film Repo

CloudStream için bağımsız film ve dizi sağlayıcıları.

## Geliştirme

Yeni sağlayıcıya başlamadan önce [Site keşif playbook'unu](docs/SITE_DISCOVERY_PLAYBOOK.md) izleyin ve [kabul kontrol listesini](docs/NEW_PROVIDER_ACCEPTANCE_CHECKLIST.md) tamamlayın.

```bash
gradle testDebugUnitTest make makePluginsJson
```

Derleme başarısı, gerçek CloudStream kurulumunu veya video/altyazı oynatımını kanıtlamaz. Dağıtım öncesi gerçek cihaz testleri zorunludur.

## Lisanslar ve üçüncü taraf kaynakları

Depodaki her dosyanın lisansını yalnızca kök `LICENSE` dosyasına bakarak varsaymayın. Modül içindeki `LICENSE` dosyaları ve kaynak dosyalarındaki SPDX bildirimleri öncelikle kontrol edilmelidir.

- Kök `LICENSE`: Ayrı bir lisans bildirimi bulunmayan depo içeriği için MIT lisans metnini sağlar.
- `FilmMakinesi/`: Ayrı bir modüldür ve GPL-3.0-or-later lisansını belirtir. Bu modüle ait lisans ve atıf bilgileri için [FilmMakinesi üçüncü taraf bildirimlerine](FilmMakinesi/THIRD_PARTY_NOTICES.md) bakın.
- Kaynak kökeni ve henüz doğrulanması gereken noktalar için [kod kökeni ve lisans notlarına](docs/CODE_ORIGIN_AND_LICENSES.md) bakın.

Bir dosya başka bir projeden uyarlanmışsa, kaynak atfını ve ilgili lisans koşullarını dosya/modül düzeyinde koruyun. Kaynağı veya lisansı kesinleşmemiş kod için yeni bir lisans varsaymayın.
