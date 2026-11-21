# Test matrisi



Tüm durumlar başlangıçta **çalıştırılmadı**. Hatalı istek kontrolleri normal performans ölçümünden ayrı tutulur.

| ID | Kontrol | Beklenen | Başlama koşulu |
|---|---|---|---|
| QA-01 | Hazır olma ve veri kimliği | Readiness UP; seed/checksum doğrulanmış; sürüm kaydedilmiş | P2 + ilgili yönetim uçları |
| QA-02 | S1 hasta 3 özeti | Hasta 3; son rapor 101; 10 tahlil kalemi | M1 REST; diğer protokoller hazır oldukça |
| QA-03 | S2 hasta 1 geçmişi | 100 rapor ve toplam 1000 kalem, doğru sıralama | M1 REST; diğer protokoller hazır oldukça |
| QA-04 | Hasta 2 | Hasta mevcut; summary.latestReport null; history.reports boş; başarı | API |
| QA-05 | Hasta 1001 | REST 404/PATIENT_NOT_FOUND; GraphQL errors; gRPC NOT_FOUND | Her adaptör |
| QA-06 | ID 0 / negatif | REST 400; GraphQL INVALID_ARGUMENT; gRPC INVALID_ARGUMENT | Her adaptör |
| QA-07 | ID abc / taşma | REST ve GraphQL kontrollü hata | HTTP adaptörleri; gRPC'de tip dönüşüm hatası ayrı istemci testi |
| QA-08 | Protokoller arası eşdeğerlik | ID/tarih/default normalizasyonundan sonra aynı içerik | P7a REST+GraphQL; P7b üçlü |
| QA-09 | GraphQL HTTP 200 içinde errors veya null kök alan | Başarısız işlem olarak sayılır | GraphQL |
| QA-10 | Düşük yük 50 VU | Hata ve gecikmeler kayıtlı; temel kararlılık | Smoke + ortam |
| QA-11 | S1 200 VU, 2 dakika | Ölçüm penceresi 120 sn; ısınma hariç; 0 protokol hatası hedefi | Eşdeğerlik + sabit ortam |
| QA-12 | S2 tam alanlar | Aynı hasta, aynı veri; payload ve ağ trafiği ayrı | P7 + ölçüm ortamı |
| QA-13 | S2b dar GraphQL alanları | Yalnız testName/resultValue; ayrı over-fetching deneyi | GraphQL S2b |
| QA-14 | 500 VU + spike | Doyma, hata ve toparlanma kaydı; eşikler ekipçe belirlenecek | 50/200 ön deneme + Doruk |
| QA-15 | DB kesintisi ve geri gelmesi | Kontrollü hata ve toparlanma | Backend/Doruk ile izole ortam |
| QA-16 | İstemci iptali | İstemci + sunucu kanıtı; havuz toparlanır; sonraki normal istek başarılı | P8; gRPC ayağı Berkan |
| QA-17 | İzleme açık/kapalı | En az 5 rastgele sıralı çift; kapsam ve belirsizlik raporu | P6 + Doruk ile ortam |
| QA-18 | UI protokol değiştirme | Gösterilen veri ve ms/KB'nin ölçüm sınırı doğru | BFF + frontend |
| QA-19 | UI test başlatma/sonuç | Koşu kimliği, durum, seçilen senaryo, doğru sonuç | Runner API + frontend |
| QA-20 | Zaman aşımı/bağlantı hatası | HTTP status 0 timeout_or_connection_failure olarak işaretlenir | K6 birim: contract.test |
| QA-21 | GraphQL eksik data kökü | errors veya null root başarısız işlem; missing data sessiz geçmez | K6 birim: contract.test |
| QA-22 | HTTP 2xx/3xx anomalisi | 200 dışı tüm 2xx/3xx cevapları başarısız/anormal sayılır | K6 birim: contract.test |
| QA-23 | Yanlış content-type | application/json veya application/graphql-response+json dışında cevap başarısız sayılır | K6 birim: contract.test |
| QA-24 | Hatalı özet/metadata | Şema, mock, smoke, incomplete/failed durum ve bozuk metrikler CSV'ye girmez | K6 birim: export.test |


HTTP hazırlığı: QA-02..09 ve QA-13 smoke koduyla kapsanır; QA-10..14 yük profilleri hazırlanmıştır. Gerçek backend durumlarının tamamı çalıştırılmadı. QA-01 kısmi metadata kaydı; QA-15..19 bekliyor. gRPC bütün maddelerde bekliyor.
Sınır ve format kontrolleri QA-20..24 sentetik birim testleriyle kapsanır; bunlar backend veya benchmark kanıtı değildir.


## 21 Kasım sözleşme kontrolü

- Main: ortak API/veritabanı var; gerçek adaptör doğrulaması yok. #15 seed hastaları (3/1/2/1001) mevcut test matrisiyle uyumlu.
- #16 Java paket düzeni HTTP cevap alanlarını değiştirmiyor. #17 ortak sorgu, hata sınıflandırması ve DB read-inactivity davranışı getiriyor; toplam istemci timeout'u ekipçe belirlenmeli.
- #6 proto hâlâ GetPatientSummary ve nested hasta/rapor yapısına uyum bekliyor. gRPC hazırlanmış sayılmaz.
- #36 GraphQL ön kontrolü ortak queries.S1 kullanacak şekilde düzeltildi. Readiness HTTP 2xx yanında JSON status=UP ister. Windows PowerShell 5.1 için UseBasicParsing kullanılır.
- #37 frontend yeni yüklendi; eksik hastakayıt import hedefi ve İG-5 kapsamı görüşmede teyit edilecek. QA-18/19 çalıştırılmadı.
- Güncel yöntem önerileri DENEY-PLANI.md; bağımlılık ve görüşme soruları GORUSME-VE-KOD-INCELEME.md içinde. Gerçek test durumları değişmedi.
