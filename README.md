# AirPods Control for Samsung — rootless v0.2

Özgün, rootsuz Android prototipi. Hedef cihaz: Samsung Galaxy S24 Ultra + AirPods Pro 2.

## v0.2 neler yapar?
- Apple manufacturer BLE (0x004C) AirPods proximity paketlerini filtreler.
- Sol/sağ/kutu pil değerlerini konservatif parser ile gösterir; tanımadığı pakette değer uydurmaz.
- Foreground service ile ekran kapansa da izlemeyi sürdürebilir.
- SYSTEM_ALERT_WINDOW izni verilirse AirPods yakına geldiğinde 4.5 saniyelik üst popup gösterir.
- Telefona eşleşmiş AirPods'u bulur.
- Bluetooth ACL bağlanma/kopma olaylarını ayrıca takip eder; BLE taraması ile bağlantı durumunu birbirinden ayırır.
- ANC/Transparency gibi AACP kontrollerini, Android üzerinde güvenilir kanal uygulanmadan sahte biçimde aktif göstermez.
- Ham BLE paketini tanılama amacıyla gösterir.

## Neden LibrePods'un kopyası değil?
LibrePods ve açık protokol araştırmaları mimari/protokol referansı olarak incelenmiştir. Bu repo onların kodunu içe aktarmayan, Samsung/One UI odaklı ayrı bir uygulama iskeletidir. LibrePods GPL-3.0 lisanslıdır; bu prototipte onların kaynak dosyaları kopyalanmamıştır.

## Tabletten APK üretme
1. Bu klasörün içeriğini GitHub repository'sine yükle.
2. GitHub > Actions > `AirPods Control - APK Build`.
3. `Run workflow`.
4. Build yeşil olduğunda `AirPodsControlRootless-debug-apk` artifact'ını indir.
5. ZIP içindeki `app-debug.apk` dosyasını S24 Ultra'ya kur.

## İlk test
1. AirPods Pro 2'yi Samsung Bluetooth ayarlarından eşleştir.
2. Uygulamayı aç, Bluetooth ve bildirim izinlerini ver.
3. `AirPods monitörünü başlat`.
4. `Apple tarzı popup iznini aç` seçeneğinden diğer uygulamaların üzerinde gösterme iznini ver.
5. AirPods kutusunu kapatıp tekrar aç.
6. Pil değerleri veya `Ham Apple BLE` satırı görünürse tarama çalışıyor.
7. `Tanılama verisini kopyala` ile cihaz/Android sürümü ve gerçek BLE paketini tek dokunuşla panoya al; sonraki protokol testinde bu veri kullanılabilir.

## Teknik sınır
Bazı Android sürümleri/üreticileri Apple'ın L2CAP davranışı ile Android Bluetooth stack'i arasındaki uyumsuzluk nedeniyle AACP bağlantısını rootsuz açamayabilir. Bu nedenle v0.2, BLE/popup/pil işlevlerini gelişmiş AACP kontrol katmanından ayırır.
