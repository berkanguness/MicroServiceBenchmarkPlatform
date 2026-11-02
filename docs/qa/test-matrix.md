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


HTTP hazırlığı: QA-02..09 ve QA-13 smoke koduyla kapsanır; QA-10..14 yük profilleri hazırlanmıştır. Gerçek backend durumlarının tamamı çalıştırılmadı. QA-01 kısmi metadata kaydı; QA-15..19 bekliyor. gRPC bütün maddelerde bekliyor.
