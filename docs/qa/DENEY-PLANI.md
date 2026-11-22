# QA deney planı — 21 Kasım 2026

Durum: ekip görüşmesine sunulan öneri. Aşağıdaki öneri değerler onaylanmış deney koşulları değildir; backend benchmark sonucu üretilmedi. Takip: #29, #31, #32, #33.

## Sabit kapsam ve ölçüm sınırı

- Talimat Rev.2: S1 her protokol için 200 VU ve 120 saniye; ısınma dışarıda. S2 hasta 1 için 100 rapor / 1000 sonuç. S2b yalnız GraphQL testName/resultValue alanlarıyla ayrı deney.
- Backend sözleşmesi: S1 hasta 3 / son rapor 101 / 10 kalem; hasta 2 boş; 1001 bulunamadı. Seed 4912026, 1000 hasta / 10080 rapor / 100800 kalem; kampanya öncesinde full verification gerekir.
- k6 doğrudan LabResultService'i ölçer. BFF/UI zamanları ayrı tutulur. GraphQL ve REST başarı durumları yanında içerik doğruluğu aranır; gRPC gelince aynı veri normalizasyonu gerekir.
- Ana gecikme qa_operation_ms p95/p99; toplam ve başarılı RPS, hata oranı ayrı. Isınmayı içerebilen http_* metrikleriyle karıştırılmaz. İstemci doğrulama maliyeti ve k6 kaynak tüketimi raporlanır.
- HTTP yanıt gövdesi baytı, protobuf serileştirilmiş mesaj boyutu ve ağ giriş/çıkışı farklı büyüklüklerdir. Serileştirme süresi ayrıca ölçülmeden çıkarılamaz.

## Görüşmede kesinleştirilecek öneriler

| Parametre | Öneri | Karar sahibi |
|---|---|---|
| Isınma | Her koşu öncesi 30 saniye; kararlılık gözlenmezse uzatıp tüm kollarda aynı uygula | Azra + backend |
| Tekrar | Her protokol/iş yükü/yük seviyesi için 3 tekrar | Ekip |
| Sıra | Tekrar 1 REST→GraphQL→gRPC; 2 GraphQL→gRPC→REST; 3 gRPC→REST→GraphQL | Azra |
| Düşük/yüksek süre | 50 ve 500 VU için 120 saniye ölçüm | Ekip |
| Think time | 0 saniye, bütün protokollerde aynı; kapalı VU modeli olduğu açık belirtilir | Ekip |
| İstek timeout | Başlangıç önerisi 30 saniye; 5 saniyelik DB read-inactivity süresi toplam çağrı bütçesi değildir | Berkay + Berkan + Azra |
| Spike | 50 VU başlangıç; 10 sn 50, 10 sn 500'e çıkış, 30 sn 500, 10 sn 50'ye dönüş, 60 sn 50; toplam 120 sn | Azra + Doruk |
| Cache | Her tekrar öncesi uygulamayı yeniden başlat; DB/OS cache temizlendi iddiası yok; aynı politika ve ısınma | Doruk + backend |
| Ortam | Aynı makine, image sürümleri, heap/CPU/RAM sınırları; k6 ile sunucu kaynak rekabetini kaydet | Doruk |

S1'in resmî 200/120 profili değiştirilmez. Diğer öneriler kabul edilmeden environment kaydına gerçek karar diye yazılmaz. Yük koşuları eşzamanlı değil sırayla çalıştırılır. 500 VU öncesinde düşük yükte servis sağlığı kontrol edilir; kontrolsüz kaynak tüketiminde koşu durdurulur ve başarısızlık kaydı korunur.

## Çalıştırma sırası ve giriş kapıları

1. Servis adresi ve tam commit alınır. Wrapper yerel HEAD ile uygulama SHA'sını karşılaştırdığı için ölçüm checkout'u birleşik servis/test sürümü olmalıdır; farklı sürümle kapı atlanmaz.
2. Seed ve full verification kanıtı, readiness UP ve ortam bilgisi kaydedilir. environment.example.json içindeki null alanlar tahminle doldurulmaz.
3. Önce REST smoke; GraphQL geldikçe iki protokol eşdeğerliği. gRPC adaptörü ve istemcisi hazır olunca üçlü eşdeğerlik. Boş/olmayan/geçersiz hasta kontrolleri ölçüm koşularından ayrı.
4. Kısa shakedown; benchmark tablosuna eklenmez.
5. Isınma + düşük yük + S1 200/120 + S2 + yüksek yük + ayrı spike; tekrarlar. S2b ayrı tablo.
6. runId ile ham k6 çıktısı, summary, ortam/commit, seed ve aynı zaman aralığındaki telemetri eşleştirilir.
7. Başarısız/eksik koşular başarı CSV'sine sokulmaz; doygunluk/hata bulguları ayrı tabloda korunur. p95 yüzdelikleri tek küresel yüzdelik gibi ortalanmaz; tekrar başına sonuç ve tekrarlar arası medyan/aralık sunulur.

## İzleme ek yükü ve hata senaryoları

En az 5 rastgele sıralı açık/kapalı çift önerilir. bench/bench-nomon yanında Prometheus/cAdvisor host yükünün kapalı kolda ne olacağı Doruk ile yazılı belirlenmelidir. Aynı donanım, iş yükü ve cache politikası kullanılır.

Gecikme farkı = 100 × (p95_açık / p95_kapalı − 1). Kapasite kaybı = 100 × (başarılıRPS_kapalı − başarılıRPS_açık) / başarılıRPS_kapalı. Sıfır payda varsa oran raporlanmaz. Hangi ölçütün %3 gereksinimini temsil ettiği danışman/ekiple belirlenir. Her çift, değişkenlik ve örnek sayısı gösterilir; tek koşuyla %3 sağlandı denmez.

DB kesintisi ve istemci iptali izole ortamda backend/Doruk koordinasyonuyla, normal benchmark dışında yapılır. Beklenen hata kodu, hata süresi, bağlantı/havuz toparlanması ve sonraki başarılı istek kaydedilir. Veri silme/reset komutu QA planının parçası değildir.

## Eksik telemetri

PR #26 Docker Desktop ağ paneli tüm Docker ortamını gösteriyor; tek servis trafiği sayılmaz. Gelen ve giden ağ için hangi hedefin ölçüleceği, p95/p99/RPS panelleri ve uygulama scrape hedefi görüşmede kesinleşmelidir. CPU/RAM/ağ elde edilmediyse null/bekliyor olarak kalır.
