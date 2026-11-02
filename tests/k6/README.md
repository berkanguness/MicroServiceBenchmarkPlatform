# Azra — QA ve k6 hazırlığı

Talimat Rev.2 adım 5–11 ve implementation-contract §7 esas alınmıştır. Bu paket HTTP test hazırlığıdır. Gerçek backend ve performans doğrulaması henüz yapılmadı. Docker/Compose/Prometheus/Grafana Doruk'un görevi; bu değişiklikler o dosyalara dokunmaz. Gerçek testler ortam ve servis teslimine bağlıdır. Doğrulanan k6 sürümü 2.3.0; yardımcı öz kontroller için Node.js 20+ gerekir (çalıştırılan sürüm 26.1.0). npm bağımlılığı yoktur.

## Dosyalar

- `smoke.js`: S1/S2, boş hasta, olmayan/geçersiz ID, GraphQL errors, S2b ve REST/GraphQL içerik eşdeğerliği. `PROTOCOL=rest` ile yalnız hazır olan REST başlayabilir.
- `load.js`: S1/S2/S2b; ayrı ısınma penceresi, ölçülen operasyon metrikleri, sabit 50/200/500 VU ve yapılandırılabilir spike.
- `run.ps1`: tarihli sonuç klasörü, ham k6 çıktısı, özet, konsol, çıkış kodu ve varsa `/actuator/info` kaydı.
- `summarize.mjs`: gerçek ölçüm özetlerini CSV'ye çevirir; mock, smoke ve shakedown kayıtlarını reddeder. CPU/RAM/ağ ölçülmeden sıfır yazılmaz.
- `unit/`: test kodunun doğrulanması için sentetik fixture ve loopback mock; proje verisi değildir.

## Şimdi, backend beklemeden

1. Repo kökünde `node --test tests/k6/unit/contract.test.mjs` çalıştırılabilir; Docker gerekmez.
2. k6 bilgisayarda varsa `node tests/k6/unit/runtime-check.mjs <k6-exe-yolu>` test paketini sahte yerel sunucuyla kontrol eder. Bu çıktılar sunumda yalnız "test altyapısı öz kontrolü" diye anlatılır.
3. Test kapsamı ve bekleyen kararlar için `docs/qa/HAZIRLIK.md` okunur.

## Berkay ilk REST uçlarını ve veriyi hazır ettiğinde

Önce Doruk ortam URL'lerini verir. `127.0.0.1` yalnız servis aynı makinedeyse doğrudur. k6 bir container içindeyse servis DNS adı kullanılır; Docker ağını Doruk belirler. Kurulum ve kaynak sınırları bu scriptin işi değildir.

Repo kökünde PowerShell:

```powershell
./tests/k6/run.ps1 -Mode smoke -Protocol rest -BaseUrl http://HOST:8080 -ManagementUrl http://HOST:8081
# GraphQL geldiğinde aynı işi iki protokolde karşılaştır:
./tests/k6/run.ps1 -Mode smoke -Protocol both -BaseUrl http://HOST:8080 -ManagementUrl http://HOST:8081
```

`k6` PATH'te değilse `-K6 'C:/.../k6.exe'` eklenir. `HOST` gerçek adresle değiştirilir. Smoke negatif testleri de gönderir; o sırada oluşan beklenen 400/404 cevapları performans hata oranı değildir. Smoke, performans ölçümünden ayrı koşar. Hasta bilgileri konsola basılmaz.

macOS/Linux veya doğrudan k6 kullanımı: `k6 run -e BASE_URL=http://HOST:8080 -e PROTOCOL=both tests/k6/smoke.js`. PowerShell wrapper kullanılmıyorsa tarihli çıktı klasörü, `--out json=...`, `SUMMARY_PATH`, sürüm/ortam ve Actuator kayıtları ayrıca sağlanmalıdır. Doğrudan k6'nın çıkış kodu eksik işlemleri tek başına göstermez; load özetindeki `status` da kontrol edilir.

## İlk kısa deneme — benchmark değildir

```powershell
$env:PROFILE='shakedown'
$env:WORKLOAD='S1'
$env:VUS='1'
$env:WARMUP_SECONDS='5'
$env:DURATION_SECONDS='10'
$env:THINK_TIME_SECONDS='0'
./tests/k6/run.ps1 -Mode load -Protocol rest -BaseUrl http://HOST:8080
```

Bu 5/10 saniye yalnız kısa teknik deneme örneğidir; resmî deney yöntemi değildir. Isınma, tekrar, cache, timeout ve think-time gerçek ölçümden önce ekipçe sabitlenir. Varsayılan istek timeout'u 10 saniyedir; ortam için gözden geçirilir.

## Gerçek ölçüme geçiş

Ön koşullar: doğru seed + full verification kanıtı, çalışan API, smoke başarısı, eşdeğerlik kanıtı, Doruk'un kaynak limitleri ve telemetrisi, kaydedilmiş protokol sırası/tekrar/cache politikası. `environment.example.json` ayrı bir ortam kaydına kopyalanıp gerçek bilgilerle doldurulur. Uygulama her protokol/tekrar öncesinde Doruk ile yeniden başlatılır; veri silinmez. PostgreSQL/OS cache'in aynı kaldığı ayrıca belirtilir.

```powershell
$env:PROFILE='s1-200' # S1 için 200 VU, 120 saniye sabit
$env:WORKLOAD='S1'
$env:WARMUP_SECONDS='<ekibin belirlediği saniye>'
$env:THINK_TIME_SECONDS='0' # seçim kaydedilir, bütün protokollerde aynı
$env:READY_FOR_MEASUREMENT='yes' # ön koşulları gerçekten kontrol ettikten sonra
./tests/k6/run.ps1 -Mode load -Protocol rest -BaseUrl http://HOST:8080 -ManagementUrl http://HOST:8081 -EnvironmentRecord ./environment.actual.json
```

Diğer profiller: `low` = 50 VU, `high` = 500 VU; her ikisinde `DURATION_SECONDS` açıkça girilir. S2/S2b de bu profillerle çalışır. `spike` için başlangıç `VUS`, toplam `DURATION_SECONDS` ve `SPIKE_STAGES='[{"seconds":...,"target":...},...]'` gerekir; toplam süre tutarsızsa kod durur. Spike profili kararlaştırılmadan sayı uydurulmaz. `s1-200`, `low`, `shakedown` sıfır işlem hatası ister; high/spike yalnız işlem oluşmasını ister, doyma hataları raporda değerlendirilir. Mutlak p95 hedefi belgede yoktur, eklenmedi.

Tek protokol bir seferde çalıştırılır. Ana hedef doğrudan LabResultService'tir; BFF/UI ayrı deneydir. S2b yalnız GraphQL'dir; S2 tam alanlarla aynı sonuç tablosunda eşdeğer deney diye karıştırılmaz.

## Metrikleri doğru okuma

- `qa_operation_ms`: çağrı başlangıcından gövde alınmasına kadar istemci süresi; JSON parse ve doğrulama bu süreye dahil değil. İstemcinin doğrulama maliyeti yine üretebildiği RPS'yi etkileyebilir; k6 kaynakları izlenmelidir.
- `qa_operations`, `qa_successes`, `qa_failure_rate`: sadece ölçüm penceresinde BAŞLAYAN işlemler. Toplam/başarılı RPS, ölçüm saniyesine bölünür. Son isteğin tamamlanması pencereyi aşabilir; kayıt bunun içindir.
- Dahili `http_*` metrikleri ısınmayı da içerir. Ana karşılaştırmada `qa_*` kullanılır. Ham JSON ayrıca saklanır.
- Graceful stop 15 saniye; spike düşüşünde gracefulRampDown 0 saniyedir. `qa_started` ile tamamlanan `qa_operations` arasındaki fark `incompleteOperations` olarak kaydedilir. Eksik işlem varsa özet `failed-or-incomplete` olur; wrapper başarılı sonuç döndürmez ve CSV dönüştürücü koşuyu reddeder. Log incelenir, yöntem gözden geçirilir. Bu ilk sürüm, drop/abort metrikleri için sunucu telemetrisi yerine geçmez.
- Payload: UTF-8 HTTP cevap gövdesi baytı; ağ trafiği değildir. Hata gövdeleri de byte metriğine girebilir; hatalı koşu payload kıyaslaması için kullanılmaz. gRPC protobuf boyutu backend'in payload tablosundan gelecek.
- CPU/RAM/ağ ve serileştirme özette `null`; ölçülmediler. Doruk'un aynı zaman aralığındaki telemetrisiyle tamamlanacak.
- Çıkış 0 tek başına deneyin geçerli olduğunu kanıtlamaz. Ortam, sürüm, veri, kesilen istekler ve eşdeğerlik kayıtları incelenir.

## Bekleyen işler

gRPC istemcisi: PR6 şeması RFC/ortak API ile uyumlu olunca; VU başına bağlantı ve normalize edilmiş cevaplar eklenecek. Şimdilik `PROTOCOL=grpc` açık hata verir. İzleme açık/kapalı %3 deneyi, DB kesintisi ve istemci iptali, UI düğmesi ve runner API bu pakette uygulanmış sayılmaz. Doruk'un ortamı geldiğinde birlikte tamamlanır.

Resmî k6 başvuruları: [lifecycle](https://grafana.com/docs/k6/latest/using-k6/test-lifecycle/), [thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/), [custom summary](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/).
