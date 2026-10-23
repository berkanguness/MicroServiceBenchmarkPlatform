# Backend A — Arayüz Tabloları (P0)

| Alan | Değer |
|---|---|
| Hazırlayan | Backend A — Berkay Şahin (LabResultService + PostgreSQL) |
| Tarih | 23.10.2026 |
| Kaynak | `docs/backend-a/implementation-contract.md` (bağlayıcı teknik sözleşme; bu belge onun ekip için özetidir) |
| Durum | Taslak. Her ekip üyesi kendi bölümünü onaylar veya PR/issue üzerinden itiraz eder. |

Her bölümde üç sütun var: **Backend A sağlar** (bizim teslim ettiğimiz), **Sizden beklenen** (sizin karar vermeniz ya da yapmanız gereken) ve **Açık soru** (sahibi ve önerilen son tarih). Tarihler öneridir; ekip toplantısında netleşir.

Sabit değerler (her bölüm için geçerli):

| Ne | Değer |
|---|---|
| HTTP (REST + GraphQL) | `8080` |
| Management (health, info, Prometheus) | `8081` |
| gRPC | `9091` (9090 Prometheus'un varsayılan portu olduğu için kullanılmıyor) |
| Senaryo 1 hastası | **3** (hasta + en son rapor + kalemleri; son rapor id 101, 10 kalem) |
| Senaryo 2 hastası | **1** (100 rapor × 10 kalem = **1.000 kalem**) |
| Boş geçmişli hasta | **2** (var, raporu yok; yük testinde kullanılmaz) |
| Olmayan hasta (404) | **1001** |
| Veri seti | 1.000 hasta, 10.080 rapor, 100.800 kalem; seed `4912026`, sabit; tcNo'lar 11 haneli ve kontrol hanesi **kasıtlı olarak geçersiz** (gerçek kimlikle çakışamaz) |
| Spring Boot | **4.1.1**, Java 21 |

---

## 1. Backend B — Berkan Güneş (gRPC, BFF, proto)

| Backend A sağlar | Sizden beklenen | Açık soru (sahibi, son tarih önerisi) |
|---|---|---|
| **`core.api` paketi** (P1a): `PatientView`, `LabTestItemView`, `LabReportView`, `PatientSummaryView`, `PatientLabHistoryView` record'ları; `LabResultQueryService` arayüzü (`getPatient`, `getPatientSummary`, `getPatientLabHistory`); exception'lar `InvalidRequestException`, `PatientNotFoundException`, `ServiceUnavailableException`; `ErrorCode`, `Protocol`, `LabErrorRecorder`. Tam imzalar: sözleşme §3. Yayımlandığında PR #3'e commit linkiyle yorum düşeceğiz. | gRPC adaptörünü bu arayüze karşı yazmanız. Adaptör, RPC başına **tam olarak bir** servis metodunu **bir kez** çağırır ve sadece eşleme yapar. | — |
| **Paket kuralları**: adaptör `com.benchmark.lab_result_service.adapter.grpc` paketinde, sizin. Biz bu pakete dosya koymayız. ArchUnit testi `adapter..` paketlerinin `core.service`, `persistence` ve `seed` paketlerine erişmesini ve adaptörlerin birbirine bağımlı olmasını engeller. | Adaptörün yalnızca `core.api` ve `com.benchmark.proto` kullanması. | — |
| **PR #3 (RFC 0001) maddeleri**: proto şeması önerisi (§1), sunucu yeri/sahiplik (§2), adillik ayarları (§3), hata eşlemesi (§4), ölçüm yolu (§5). https://github.com/berkanguness/MicroServiceBenchmarkPlatform/pull/3 | RFC §6'daki kutucuklar için kararlarınız (1.1 `reserved` mı V2 mi, 1.2 `Timestamp`, 1.3 `birthDate` string, 1.4 protobuf sürüm uyumu, 2.x, 3.x, 4, 5.x). | Proto şeması kabulü — **Berkan, 28.10.2026** |
| **Executor**: Tomcat için `server.tomcat.threads.max=200` açıkça yazılı. `/actuator/info` gRPC executor boyutunu raporlar; bean yoksa "default (unbounded cached pool)" yazar. | Sabit 200 thread'lik `GrpcServerExecutorProvider` bean'i, kapanış (shutdown) davranışı ve kuyruk politikası. İsterseniz bean'i biz `config` paketinde yazarız; PR #3'te belirtin. | Executor bean'ini kim yazacak, yaşam döngüsü — **Berkan, 28.10.2026** |
| **Port**: `spring.grpc.server.port=9091` bizim `application.yaml`'da. `bench` profilinde `spring.grpc.server.reflection.enabled=false`; zorla bağlantı yenileme yok (`keepalive.connection.max-age` / `max-idle-time` ayarlanmıyor). | Bu değerlerle çakışan bir ayar eklememeniz; değişiklik gerekiyorsa PR #3'te söylemeniz. | — |
| **Hata eşlemesi** (sözleşme §7.4): `INVALID_ARGUMENT` → `INVALID_ARGUMENT`, `PATIENT_NOT_FOUND` → `NOT_FOUND`, `UNAVAILABLE` (`ServiceUnavailableException`) → `UNAVAILABLE`, diğer her şey → `INTERNAL`. İstemci iptali → `CANCELLED`. Servis, veritabanına erişilemediği durumları zaten `ServiceUnavailableException`'a çevirir; Spring exception'larını tanımanız gerekmez. | `GrpcExceptionHandler` bean'i ile bu eşlemeyi uygulamanız ve her hatalı çağrıda `LabErrorRecorder.record(Protocol.GRPC, code, cause)` metodunu bir kez çağırmanız (ortak log + `lab.errors` sayacı). | — |
| **gRPC payload boyutu (P7b)**: `ProtocolEquivalenceIT` REST ve GraphQL için hazır olacak (P7a). gRPC ayağı için `message.getSerializedSize()` değerleri `payload-sizes.json`'a eklenecek. | Adaptörünüz hazır olunca P7b'yi birlikte bağlamamız: test, gRPC yanıtını kanonik ağaçla karşılaştırır. | P7b tarihi, adaptör hazır olunca — **Berkan + Berkay** |
| Ölçüm yolu: k6 üç protokolde de doğrudan LabResultService'e gider (U3). | Teyidiniz; BFF süresi ölçülecekse üç protokolde de ayrıca raporlanması. | U3 teyidi — **Berkan + Azra, 28.10.2026** |

---

## 2. DevOps — Doruk Çelik (Docker, Compose, Prometheus, Grafana)

| Backend A sağlar | Sizden beklenen | Açık soru (sahibi, son tarih önerisi) |
|---|---|---|
| **Build tarifi**: build context = **depo kökü** (kök POM ve `CommonProto` modülü gerekiyor). Kökte Maven wrapper yok; modülün wrapper'ı kullanılır (Maven 3.10.0'ı build sırasında indirir): `services/LabResultService/mvnw -f pom.xml -pl services/LabResultService -am -DskipTests -Dgit.sha=$GIT_SHA package`. Çıktı: `services/LabResultService/target/lab-result-service-0.0.1-SNAPSHOT.jar`. | Dockerfile: **JDK 21** builder (ör. `eclipse-temurin:21-jdk`) ve **JRE 21** runtime (ör. `eclipse-temurin:21-jre`), digest'leri sabitlenmiş. Image build içinde test yok (`-DskipTests`): Testcontainers Docker ister. | Kök dizine ortak bir `mvnw` eklemek ister misiniz? — **Doruk, 30.10.2026** |
| **Portlar**: 8080 (REST + GraphQL), 8081 (management), 9091 (gRPC, h2c). | Compose'da bu portların açılması. | — |
| **Ortam değişkenleri**: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SPRING_PROFILES_ACTIVE`, `APP_DATASET_VERIFY_MODE` (`cheap` varsayılan, kampanya öncesi bir kez `full`), `APP_CORS_ALLOWED_ORIGINS` (bench'te etkisiz: CORS bileşeni `bench`/`bench-nomon` profilinde hiç yüklenmez), `JAVA_TOOL_OPTIONS`. | Compose'da bu değişkenlerin verilmesi; şifrenin `.env`'den gelmesi. | — |
| **Profiller**: her açılışta `SPRING_PROFILES_ACTIVE=bench,seed` (seed idempotent: veri varsa ve doğruysa atlar; ilk açılışta yükler). İzleme kapalı kol: `bench,bench-nomon,seed`. Uyumsuz veri varsa servis **açılmaz**; hiçbir zaman silme/truncate yapmaz. | Veri sıfırlama gerektiğinde bunun insan eliyle yapılması (`docker compose down -v`). | — |
| **Sağlık uçları** (8081): readiness `/actuator/health/readiness`, liveness `/actuator/health/liveness`. Readiness, seed ve veri doğrulaması bitince `UP` olur. | `lab-result-service` healthcheck'i readiness'a, k6/diğer servislerin `depends_on`'u `service_healthy`'ye bağlanmalı. | — |
| **PostgreSQL 17 önerisi**: `postgres:17-alpine@sha256:<pin>`, `POSTGRES_DB=labdb`, `POSTGRES_USER=lab`, `POSTGRES_PASSWORD=${LAB_DB_PASSWORD}`, `command: postgres -c max_connections=100 -c shared_buffers=256MB`, healthcheck `pg_isready -U lab -d labdb` (5 s aralık), volume `/var/lib/postgresql/data`. Şemayı Flyway uygulama içinden kurar; `docker-entrypoint-initdb.d` kullanılmaz. | Tam minor sürümü ve digest'i sabitlemeniz. `postgres` servis bloğunu sizin mi yazacağınıza, yoksa bizim taslak PR mı açacağımıza karar vermeniz (dosya sizde kalır). | PG blok sahipliği ve digest — **Doruk, 30.10.2026** |
| **JVM bayrakları önerisi**: `JAVA_TOOL_OPTIONS=-XX:+UseG1GC -Xms512m -Xmx512m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -XX:+PrintCommandLineFlags`. Aynı bayraklar her koşuda. Servis etkin bayrakları `/actuator/info` içinde gösterir. Gerekçe: GC ve heap'i açıkça sabitlemek, JVM'in konteyner sınırlarına göre kendi seçtiği ayarları (ergonomics) devre dışı bırakır; böylece sınır değişse de çalışma zamanı ayarları değişmez. (2 CPU / 1 GB'ta JVM'in SerialGC seçeceği bir **çıkarımdır**, seçilen Java 21 imajında denenmedi.) | Heap boyutunu ve CPU sayısını sizin belirlemeniz ve compose'a yazmanız. | Kesin heap/CPU değerleri — **Doruk, 30.10.2026** |
| **Kaynak sınırları önerisi**: lab-result-service 2 CPU / 1 GiB, postgres 2 CPU / 1 GiB. k6 ayrı `cpuset` üzerinde; Prometheus, cAdvisor ve Grafana için CPU/bellek sınırı. | Bütün konteynerlerin limitlerini compose'da sabitlemeniz; k6'nın servisle aynı çekirdekleri paylaşmaması. | Laptop/sunucu kaynak dağılımı — **Doruk, 30.10.2026** |
| **Prometheus scrape**: `http://lab-result-service:8081/actuator/prometheus`, 15 s aralık. Ortak etiket `application="lab-result-service"`. Önemli metrikler: `http_server_requests_seconds`, `graphql_request_seconds`, `grpc_server_*`, `lab_service_calls_seconds`, `lab_errors_total`, `hikaricp_connections_*`, `jvm_*`, `process_cpu_usage`. | Scrape ayarı ve Grafana panoları (protokol, senaryo, hata oranı, havuz doygunluğu). | — |
| Ağ trafiği bizim ölçtüğümüz bir şey değil. | cAdvisor `container_network_{receive,transmit}_bytes_total` ile ölçüm penceresi boyunca ağ giriş/çıkış ölçümü. | — |
| — | CI (GitHub Actions'ta Docker'lı `verify`) isteğe bağlı; bizim kritik yolumuzda değil. | CI isteniyor mu? — **Doruk, 06.11.2026** |

---

## 3. QA & Benchmarking — Azra Tunçsezen (k6, analiz, rapor)

| Backend A sağlar | Sizden beklenen | Açık soru (sahibi, son tarih önerisi) |
|---|---|---|
| **Fixture id'leri**: S1 → hasta **3**; S2/S2b → hasta **1** (1.000 kalem); boş geçmiş → **2**; 404 → **1001**; geçersiz → `0`, `abc`. Veri seti sabit (seed 4912026); kampanya öncesi bir kez `APP_DATASET_VERIFY_MODE=full` ile tam doğrulama. | k6 senaryolarında bu sabit id'lerin kullanılması (Talimat 7: "aynı hasta verisi sorgusu"). Rastgele hasta dağılımı ayrı etiketli bir deney olur. | — |
| **Senaryo istekleri**: REST S1 `GET :8080/api/v1/patients/3/summary`; REST S2 `GET :8080/api/v1/patients/1/lab-history`; GraphQL `POST :8080/graphql` S1/S2/S2b sorguları (sözleşme §7.2, birebir); gRPC `LabService/GetPatientSummary` ve `GetPatientLabHistory`, `patient_id` 3 ve 1, `:9091` (proto Backend B'nin onayına bağlı). Hepsi Postman koleksiyonunda: `docs/backend-a/api/` (P4). | k6 scriptlerinin bu isteklerle yazılması. | — |
| **Hata oranı tanımı**: REST → HTTP ≠ 200; GraphQL → HTTP ≠ 200 **veya** `errors` dizisi dolu **veya** hedef alan `null`; gRPC → durum ≠ `OK`; ayrıca bağlantı hataları ve iptaller (k6 `error_code`). Sunucu tarafında aynı olaylar `lab_errors_total{protocol,code}` olarak sayılır. | Bu tanımın k6 `checks`/eşiklerinde aynen uygulanması. GraphQL hataları HTTP 200 ile döndüğü için gövdeye bakılmalı. | — |
| **GraphQL başlıkları**: `Content-Type: application/json` ve **`Accept: application/json`** sabit. (Bu, seçtiğimiz ve testlerde kullanacağımız iletim kuralı. `application/graphql-response+json` ile istek hataları 4xx dönebilir; bu kendi başına yanlış değil, ama tüm koşularda tek bir kural kullanılması için `application/json` sabitlendi. Her iki durumda da HTTP ≠ 200 veya `errors` hata sayılır.) | Bütün GraphQL isteklerinde bu iki başlık. | — |
| **Keep-alive / bağlantı kuralı**: `bench` profilinde Tomcat bağlantıyı 100 istekte kapatmaz (`max-keep-alive-requests=-1`, `keep-alive-timeout=60s`); gRPC'de zorla yenileme yok. | k6'da HTTP keep-alive açık kalmalı (`noConnectionReuse` yok). gRPC `client.connect()` VU başına **bir kez** (`exec.vu.iterationInScenario == 0`), her iterasyonda değil. S2'de `discardResponseBodies` kullanılmamalı. | — |
| **Koşu düzeni**: tek JVM üç protokolü birden barındırıyor; sıra etkisi (JIT, heap, havuz, PG cache) karşılaştırmayı bozar. | Her (protokol × tekrar) için **servis konteynerini yeniden başlatma**, protokol sırasını **rastgele** belirleme, aynı ısınma süresi, sonra ölçüm penceresi. RAM'i pencere içi working set ve boşta referansa göre fark olarak raporlama. | — |
| **Bayt sayımı**: isimlerde Türkçe karakter var (ç, ğ, ı, ö, ş, ü). | k6'da gövde boyutu **UTF-8 bayt** olarak sayılmalı, `body.length` (karakter sayısı) değil. | — |
| **Payload referans tablosu** (R2): `payload-sizes.json`; S1/S2/S2b için REST ve GraphQL HTTP gövdesi baytı, gRPC için `getSerializedSize()`. Başlıklar ve framing hariç. P7a'da üretilir, P8'de `docs/backend-a/payload-sizes.json` olarak dondurulur. | Raporda "payload boyutu" için bu tablonun, "ağ trafiği" için cAdvisor'ın kullanılması; ikisinin karıştırılmaması. | — |
| **Serileştirme süresi**: ayrıca ölçülmüyor. `lab_service_calls_seconds` servis süresini verir; "sunucu istek süresi − servis süresi" farkı **"protokol + serileştirme ek yükü"** olarak etiketlenir. | Raporda "serileştirme süresi ölçülmedi" ifadesi (Talimat 8 buna izin veriyor). | — |
| **İzleme ek yükü (D13)**: `bench` (izleme açık) ve `bench,bench-nomon` (gözlemler + tüm metrikler + Prometheus export + gRPC gözlemi kapalı) profilleri. | Yöntem: ≥5 rastgele sıralı açık/kapalı çifti, belirsizlik aralığı, gürültülüyse "kanıtlanamadı". Kapalı kolun neyi dışarıda bıraktığının (cAdvisor/Prometheus'un host yükü) açıkça yazılması. | Kapalı kolun kapsamı — **Azra, 04.11.2026** |
| **Koşu meta verisi**: `GET :8081/actuator/info` → build/git SHA, veri seti kimliği ve checksum, aktif profiller, Tomcat/Hikari/gRPC ayarları, JVM bayrakları. | Her koşunun ham çıktısının yanına bu JSON'un kaydedilmesi (Talimat 1, 11). | — |
| **Manuel istemci iptali kontrolü (P8, R8)**: otomatik test yok, yavaş uç nokta yok. REST/GraphQL (Berkay + Azra): 20 × `curl -s -o /dev/null --limit-rate 2k --max-time 1 http://<host>:8080/api/v1/patients/1/lab-history` ve aynısı `POST /graphql` S2 gövdesiyle (`-H 'Content-Type: application/json' -H 'Accept: application/json' --data @s2.json`). ≈150 KB'lık gövde 2 KB/s ile 1 saniyede bitmez, istemci yanıt ortasında kopar; curl çıkış kodu **28**. gRPC (**Berkan**; bench'te reflection kapalı olduğu için proto açıkça verilir): `grpcurl -plaintext -max-time 0.005 -import-path services/CommonProto/src/main/proto -proto lab.proto -d '{"patient_id": 1}' <host>:9091 hospital.LabService/GetPatientLabHistory`, 20 kez. Import çözülmezse `protoc --include_imports --descriptor_set_out=lab.protoset …` ile `-protoset lab.protoset` kullanılır. Hiç kesilme olmazsa süre düşürülür (`0.002`, `0.001`) ya da 200 VU aşamasında tekrarlanır. **Kabul (protokol başına):** en az bir iptalin *gerçekten olduğu* hem istemci tarafında (curl 28 / grpcurl `DeadlineExceeded`/`Canceled`) **hem** sunucu tarafında (`lab_errors_total{protocol,code="CLIENT_ABORTED"}` ≥ 1 artış ve aynı zaman aralığında tek satırlık WARN, stack trace yok) görülmeli. Ayrıca ERROR/5xx yok, Hikari `active` 0'a döner, sonraki normal istek 200 döner. Sunucu tarafında iptal görülmeyen protokol **"denenemedi"** olarak raporlanır, geçti sayılmaz. | Ön denemede bu kontrolün koşulması ve sonucun P8 raporuna yazılması; gRPC ayağının Backend B tarafından koşulması. | — |
| **Kademeli ön deneme (P8)**: 50 VU → 200 VU × 2 dk → 500 VU + spike, birlikte; ölçüm sayılmaz. Hedef: 200 VU'da 0 protokol hatası; 500/spike'ta doyma noktasının belgelenmesi. | Bu ön denemeyi birlikte koşmamız ve run book'u birlikte gözden geçirmemiz. | Ön deneme tarihi (M1 + compose hazır olunca) — **Azra + Doruk + Berkay** |
| — | "Tek tıkla yük testi" (İG-5) için k6'yı tetikleyecek servis/API'nin sahibi. | k6 tetikleyici sahibi — **Azra + Berkan + Aylin, 30.10.2026** |

---

## 4. Frontend — Aylin Naz Avcı (React arayüzü)

| Backend A sağlar | Sizden beklenen | Açık soru (sahibi, son tarih önerisi) |
|---|---|---|
| **REST uçları** (8080): `GET /api/v1/patients/{id}` (profil), `…/{id}/summary` (Senaryo 1), `…/{id}/lab-history` (Senaryo 2). JSON alan adları: `patient{id,tcNo,fullName,birthDate,bloodType}`, `latestReport` / `reports[]` → `{id,doctorName,sampleType,createdAt,items[]}`, `items[]` → `{id,testCode,testName,resultValue,unit,isCritical}`. Tarihler ISO (`"1984-03-12"`, `"2025-12-31T17:03:21Z"`). Hatalar `application/problem+json`, `code` alanıyla (`INVALID_ARGUMENT`, `PATIENT_NOT_FOUND`, `UNAVAILABLE`, `INTERNAL`). | Arayüzün bu şekillere göre yazılması. | — |
| **GraphQL**: `POST /graphql`, şema `patient`, `patientSummary`, `patientLabHistory` (sözleşme §7.2'deki SDL); S1/S2/S2b örnek sorguları Postman'de. GraphQL'de `id` string döner (`"3"`), REST'te sayı (`3`): protokolün doğası, düzeltilmeyecek. | Arayüzde iki tipin de karşılanması. | — |
| **CORS**: `APP_CORS_ALLOWED_ORIGINS` (virgülle ayrılmış origin listesi; boşsa kapalı) `/api/**` ve `/graphql` için GET/POST/OPTIONS açar. `bench`/`bench-nomon` profilinde her zaman kapalı (ortam değişkeni verilse bile; profil koşuluyla). | Tarayıcının hangi origin'den geleceğini söylemeniz. Aynı origin'li bir proxy seçilirse CORS hiç gerekmez. | Origin listesi / proxy kararı — **Aylin + Doruk, 30.10.2026** |
| **KB/ms ölçümü** (öneri): süre için `performance.now()` ile `fetch` çevresi; boyut için `new TextEncoder().encode(await res.text()).length` (UTF-8 bayt) ya da `PerformanceResourceTiming.encodedBodySize`. İkincisi cross-origin'de `Timing-Allow-Origin` başlığı ister; isterseniz ekleriz. | Hangi yöntemi kullanacağınızı söylemeniz (gerekiyorsa `Timing-Allow-Origin` isteği). | `Timing-Allow-Origin` gerekiyor mu? — **Aylin, 30.10.2026** |
| Tarayıcı native gRPC konuşamaz; LabResultService'teki gRPC sunucusu yalnızca k6 ve servisler içindir. | gRPC butonu için yol: PatientService (BFF) üzerinden mi, aynı origin'li proxy mi, gRPC-Web (Envoy) mi? gRPC butonunda gösterilen ms/KB'nin neyi ölçtüğü (BFF iç çağrısı mı, tarayıcı gidiş-dönüşü mü). | Tarayıcı → gRPC yolu — **Berkan + Aylin + Doruk, 30.10.2026** |

---

## 5. Tüm ekip

| Backend A sağlar | Sizden beklenen | Açık soru (sahibi, son tarih önerisi) |
|---|---|---|
| **Talimat Şablonu §1 rol hatası**: belgede PostgreSQL tasarımı ve sentetik veri Azra'ya, Patient + LabResult servisleri hem Berkan'a hem Berkay'a yazılmış. Doğrusu: **Berkay** = LabResultService, PostgreSQL şeması (Flyway), sentetik veri/seed, REST, GraphQL; **Berkan** = PatientService/BFF, `lab.proto`/CommonProto, gRPC adaptörü; **Azra** = k6, kalite kontrol, analiz, rapor; **Doruk** = Docker/Compose, Prometheus/Grafana; **Aylin** = Frontend. | Talimat'ın hazırlayanı (Azra) ve denetleyeni (Berkan) tarafından §1'in düzeltilmesi; o zamana kadar §1'e göre şema/seed işi başlatılmaması (çift şema riski). | Düzeltilmiş revizyon — **Azra + Berkan, 30.10.2026** |
| **Spring Boot sürümü**: repo **4.1.1** kullanıyor (kullanıcı kararı). Gereksinim belgesi §3.3 "Uygulama", Talimat adım 1 ve `README.md` (satır 9) "Spring Boot 3" diyor. | Bu üç yerde ifadenin "Spring Boot 4.1.1" olarak güncellenmesi, final raporda sapmanın açıkça belirtilmesi. README düzeltmesini repo sahibi (Berkan) ya da ilgili kişi PR ile yapar; biz README'ye dokunmuyoruz. | Kim güncelleyecek — **Berkan, 30.10.2026** |
| **Senaryo 1 yorumu**: gereksinim belgesi "vital bulgu" diyor ama §4.3 veri modelinde vital alanı yok. S1 = hasta + en son tahlil raporu + kalemleri (kullanıcı kararı U1). | Final raporda bu yorumun açıkça yazılması. | — |
| **Sözleşme**: `docs/backend-a/implementation-contract.md`. Değişiklik önerileri RFC PR'ı olarak (`docs/rfc/`), kabul edilen kararlar ADR olarak (`docs/adr/`). | Kendi bölümünüzü onaylamanız ya da PR/issue ile itiraz etmeniz. | Onaylar — **herkes, 30.10.2026** |
