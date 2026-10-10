# CloudStream Film Repo

CloudStream için bağımsız film ve dizi sağlayıcıları.

## Geliştirme

Yeni sağlayıcıya başlamadan önce [Site keşif playbook'unu](docs/SITE_DISCOVERY_PLAYBOOK.md) izleyin ve [kabul kontrol listesini](docs/NEW_PROVIDER_ACCEPTANCE_CHECKLIST.md) tamamlayın.

```bash
gradle testDebugUnitTest make makePluginsJson
```

Derleme başarısı, gerçek CloudStream kurulumunu veya video/altyazı oynatımını kanıtlamaz. Dağıtım öncesi gerçek cihaz testleri zorunludur.
