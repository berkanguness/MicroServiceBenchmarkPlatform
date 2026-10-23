# RFC 0001 — LabResultService ↔ gRPC Sözleşmesi ve Ölçüm Adilliği

| Alan | Değer |
|---|---|
| **Durum** | Tartışmada (Draft) |
| **Yazar** | Backend A — Berkay Şahin (LabResultService + PostgreSQL) |
| **Karar verici** | Backend B — Berkan Güneş (PatientService/BFF, common-proto, gRPC) |
| **Bilgi** | QA & Benchmarking (Madde 5), DevOps (portlar, Madde 3.2) |
| **Tarih** | 23.10.2026 |
| **Proje** | YM491 – Mikroservis Mimarilerinde İletişim Protokollerinin Başarım Analizi (REST / gRPC / GraphQL) |

> Bu RFC bir **öneridir**. Aşağıdaki maddelerin hepsi Backend B'nin onayına/kararına açıktır. Yorumlar bu PR üzerinden yapılır; PR birleştiğinde kabul edilen kararlar bağlayıcı olur ve durum "Kabul edildi" olarak güncellenir.

---

## 0. Bağlam

Backend A tarafında LabResultService tasarımı tamamlandı. Üç protokolün (REST, GraphQL, gRPC) **aynı servis katmanını çağırması** ve **eşdeğer alanları döndürmesi** araştırmanın geçerliliği için şart. Mevcut `lab.proto` buna yetmiyor; ayrıca gRPC tarafında karşılaştırmayı çarpıtabilecek birkaç ayar var.

Ekip içinde netleşen kararlar (Backend A + ekip):

- Spring Boot sürümü **4.1.1** (repo ile aynı; gereksinim belgesindeki "Spring Boot 3" ifadesi güncellenmeli).
- **Senaryo 1:** hasta profili + en son tahlil raporu + o raporun kalemleri.
  (Not: Gereksinim belgesi "vital bulgu" diyor ama veri modelinde vital alanı yok; final raporda bu yorum açıkça belirtilecek.)
- **Senaryo 2:** tek hastanın **1.000 tahlil kalemi** (100 rapor × 10 kalem).
- **k6 yük testleri doğrudan LabResultService'e** gidecek (üç protokol de aynı hop sayısıyla). → Backend B ve QA'nın teyidi gerekiyor (bkz. Madde 5).

---

## 1. Proto şeması (`services/CommonProto/src/main/proto/lab.proto`)

### Sorun
Mevcut proto yalnızca `GetPatientLabHistory` RPC'sini ve düz (raporlara göre gruplanmamış) bir kalem listesini içeriyor. Eksikler:

- Hasta alanları (`tcNo`, `fullName`, `birthDate`, `bloodType`) yok.
- Rapor alanları (`doctorName`, `sampleType`, `createdAt`) yok; kalemler raporlara göre gruplanmıyor.
- Senaryo 1 için bir RPC yok.

Bu haliyle gRPC, REST ve GraphQL'den **daha az alan** taşır ve sonuçlar yapay olarak gRPC lehine çıkar.

### Önerilen şema

```proto
syntax = "proto3";
package hospital;
option java_package = "com.benchmark.proto";
option java_multiple_files = true;

import "google/protobuf/timestamp.proto";

message PatientRequest { int64 patient_id = 1; }                         // değişmedi

message Patient {                                                        // YENİ
  int64  id         = 1;
  string tc_no      = 2;
  string full_name  = 3;
  string birth_date = 4;   // ISO-8601 YYYY-MM-DD (REST/GraphQL ile aynı metin)
  string blood_type = 5;
}

message LabItemResponse {                                                // değişmedi (alan 1..6)
  int64 id = 1; string test_code = 2; string test_name = 3;
  double result_value = 4; string unit = 5; bool is_critical = 6;
}

message LabReport {                                                      // YENİ
  int64  id          = 1;
  string doctor_name = 2;
  string sample_type = 3;
  google.protobuf.Timestamp created_at = 4;
  repeated LabItemResponse items = 5;
}

message PatientSummaryResponse {                                         // YENİ — Senaryo 1
  Patient   patient       = 1;
  LabReport latest_report = 2;   // hastanın raporu yoksa boş
}

message PatientLabHistoryResponse {                                      // DEĞİŞTİ — Senaryo 2
  reserved 1, 2;
  reserved "patient_id", "items";
  Patient patient = 3;
  repeated LabReport reports = 4;
}

service LabService {
  rpc GetPatient           (PatientRequest) returns (Patient);                   // YENİ
  rpc GetPatientSummary    (PatientRequest) returns (PatientSummaryResponse);    // YENİ — Senaryo 1
  rpc GetPatientLabHistory (PatientRequest) returns (PatientLabHistoryResponse); // DEĞİŞTİ — Senaryo 2
}
```

### Backend B'nin karar vermesi gerekenler

| # | Karar | Seçenekler | Önerimiz |
|---|---|---|---|
| 1.1 | Geçiş stili | (a) Mevcut mesajı değiştir + eski numaraları `reserved` yap · (b) Eskiyi koru, yeni `…V2` RPC'ler ekle · (c) Numaraları baştan düzenle | **(a)** — henüz proto'yu kullanan kod yok, tek RPC/senaryo kalır, `reserved` numara çakışmasını önler. (b) de kabul edilebilir. |
| 1.2 | `createdAt` tipi | `google.protobuf.Timestamp` · ISO string | **Timestamp** — her protokol kendi doğal kodlamasıyla ölçülmeli; boyut farkı raporlanacak bir bulgu. |
| 1.3 | `birthDate` tipi | ISO string · `google.type.Date` | **String** — performans açısından fark yaratmaz, ek eşleme gerektirmez. |
| 1.4 | Sürüm kontrolü | — | Proto derlemesi import için **protobuf-java 3.25.9** kaynaklarını açıyor, çalışma zamanı **4.35.1**. protoc/gencode sürümünün runtime'dan büyük olmadığını doğrulayın (muhtemelen sorun yok). |

### Bilinen, protokolün doğasından gelen farklar (düzeltilmeyecek, raporda belirtilecek)

- GraphQL `ID` tipi JSON'da string (`"1"`), REST'te sayı (`1`), gRPC'de `int64`.
- proto3 varsayılan değerleri (`is_critical=false`, `result_value=0.0`) ağa yazmaz; JSON yazar. Bu Protobuf'un gerçek bir avantajı.
- `Timestamp` seçilirse `createdAt` gRPC'de binary taşınır.

---

## 2. gRPC sunucusunun yeri ve sahipliği

| # | Karar | Önerimiz |
|---|---|---|
| 2.1 | gRPC sunucusu nerede çalışacak? | **LabResultService içinde.** `spring-boot-starter-grpc-server` bağımlılığı zaten LabResultService'in `pom.xml`'inde; PatientService'te ise grpc-client var. |
| 2.2 | gRPC adaptörünü kim yazacak? | **Backend B.** Paket: `com.benchmark.lab_result_service.adapter.grpc`. Backend A bu paketi boş bırakır. |
| 2.3 | Adaptör kuralları | Adaptör yalnızca Backend A'nın yayımlayacağı `core.api` arayüzünü (`LabResultQueryService`) çağırır; **her RPC tam olarak bir servis metodunu bir kez** çağırır; sadece eşleme/serileştirme yapar; repository veya entity'lere erişmez (ArchUnit kuralıyla denetlenecek). |

Backend A'nın sağlayacağı servis arayüzü (taslak):

```java
public interface LabResultQueryService {
    PatientView getPatient(long patientId);                       // GetPatient
    PatientSummaryView getPatientSummary(long patientId);         // GetPatientSummary   — Senaryo 1
    PatientLabHistoryView getPatientLabHistory(long patientId);   // GetPatientLabHistory — Senaryo 2
}
```

`core.api` paketi hazır olduğunda (yol haritasında P3) Backend B'ye haber verilecek; adaptör bu noktadan sonra yazılabilir.

---

## 3. Performans karşılaştırmasının adilliği (gRPC ayarları)

| # | Konu | Sorun | Önerimiz |
|---|---|---|---|
| 3.1 | **Thread havuzu** | grpc-java 1.83.1, özel bir executor verilmezse **sınırsız** bir `CachedThreadPool` kullanıyor (Spring Boot 4.1.1'de varsayılan bir executor bean'i yok). REST/GraphQL'in çalıştığı Tomcat ise **200 thread** ile sınırlı. 500 VU'da gRPC 500 eşzamanlı işleyiciyi 20 bağlantılık veritabanı havuzuna yığarken REST bağlantı kuyruğunda bekler → kuyruk modelleri farklılaşır, **p99 çarpıtılır**. | Sabit **200 thread'lik** havuz döndüren bir `GrpcServerExecutorProvider` bean'i. Backend A tarafında da `server.tomcat.threads.max=200` açıkça yazılacak. İki değer de test çalıştırma kayıtlarına eklenmeli. |
| 3.2 | **Port** | `spring.grpc.server.port` için Boot 4.1.1'de varsayılan değer yok; açıkça ayarlanmalı. | **9091** — 9090 Prometheus'un varsayılan portu, aynı compose yığınında çakışır. (HTTP: 8080, management: 8081.) |
| 3.3 | **Reflection** | `spring.grpc.server.reflection.enabled` varsayılan olarak `true`. | Benchmark profilinde **kapatılmalı**; servis yüzeyi protokoller arasında eşit kalsın. |
| 3.4 | **Bağlantı** | — | Benchmark'ta sıkıştırma yok, TLS politikası üç protokolde aynı; istemci tarafında kanal yeniden kullanımı (QA ile birlikte). |

---

## 4. Hata eşlemesi

Gereksinim belgesi gRPC hata kodlarının kaydedilmesini istiyor. Önerilen eşleme:

| Backend A exception (`core.api`) | gRPC durum kodu | REST karşılığı |
|---|---|---|
| `InvalidRequestException` (örn. `patientId <= 0`) | `INVALID_ARGUMENT` | 400 |
| `PatientNotFoundException` | `NOT_FOUND` | 404 |
| Veritabanına erişilemiyor | `UNAVAILABLE` | 503 |
| Diğer | `INTERNAL` | 500 |

Uygulama: bir `GrpcExceptionHandler` bean'i (spring-grpc-core 1.1.1'de mevcut). Sunucu taraflı hatalar (`INTERNAL`, `UNAVAILABLE`) ERROR seviyesinde loglanmalı.

---

## 5. BFF (PatientService) ve ölçüm yolu — QA ile birlikte

| # | Karar | Önerimiz |
|---|---|---|
| 5.1 | k6 hangi servise istek atacak? | **Doğrudan LabResultService** (Backend A tarafında onaylandı). Backend B ve QA teyit etmeli. |
| 5.2 | BFF ölçüme girecek mi? | Ana karşılaştırmada **hayır**. Girecekse **üç protokol için de** BFF üzerinden gidilmeli ve servisler arası (BFF → LabResultService) süre ayrıca raporlanmalı. Bir protokol doğrudan, diğeri BFF üzerinden ölçülürse karşılaştırma geçersiz olur. |
| 5.3 | Veri sahipliği | Patient, LabReport ve LabTestItem tablolarının tamamı Backend A'da. PatientService **ayrı bir yazma noktası veya ikinci bir Patient tablosu tutmamalı**; veriyi LabResultService'ten okumalı. |

---

## 6. Özet — Backend B'den beklenen yanıtlar

- [ ] 1.1 Proto geçiş stili: `reserved` (önerilen) / V2 / yeniden numaralama
- [ ] 1.2 `createdAt`: Timestamp (önerilen) / string
- [ ] 1.3 `birthDate`: string (önerilen) / `google.type.Date`
- [ ] 1.4 protobuf derleme/runtime sürüm uyumu kontrol edildi
- [ ] 2.1–2.2 gRPC sunucusu LabResultService içinde, adaptör Backend B'de — onay
- [ ] 3.1 200 thread'lik `GrpcServerExecutorProvider` — onay
- [ ] 3.2 gRPC portu 9091 — onay
- [ ] 3.3 Benchmark profilinde reflection kapalı — onay
- [ ] 4 Hata eşlemesi — onay
- [ ] 5.1–5.3 Ölçüm yolu ve veri sahipliği — onay (QA ile)

Sorular ve itirazlar için bu PR üzerinden satır yorumu bırakabilirsiniz.
