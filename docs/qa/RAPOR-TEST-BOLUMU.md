# Final raporu için test yöntemi ve sonuç düzeni

Durum: yöntem/sonuç şablonu hazır; gerçek deneyler ve final raporu tamamlanmadı. Takip #35. 20 Kasım ilerleme raporu ayrı bir teslimdir; bu dosya onun gönderildiğini kanıtlamaz.

## Yöntem metni

Bu çalışmada REST, GraphQL ve gRPC arayüzleri ortak iş mantığı ve aynı sentetik veri kümesiyle karşılaştırılacaktır. k6 doğrudan LabResultService'e istek gönderecek; BFF ve kullanıcı arayüzü ölçümleri ayrı ele alınacaktır. Her protokolün aynı alanları ve kayıtları döndürdüğü doğrulandıktan sonra yük testlerine geçilecektir. S1 senaryosu 200 sanal kullanıcı ile 120 saniye ölçüm içerir. Isınma ölçüm penceresinin dışındadır. S2 senaryosu bir hastanın 1000 tahlil kalemini içerir. GraphQL alan azaltma deneyi S2b olarak ayrıca raporlanacaktır.

Koşuların sürümü, veri checksum'ı, çalışma ortamı, kaynak sınırları, tekrar ve sıra bilgisi saklanacaktır. Gecikme p95/p99, toplam/başarılı işlem/s ve hata oranı ile CPU, RAM ve ağ metrikleri incelenecektir. Yanıt gövdesi boyutu ile ağ trafiği ayrı ölçülerdir. Nihai yöntem metni, deney planı ekipçe kesinleştikten sonra geçmiş zamanla gerçek uygulamaya göre güncellenecektir.

## Sonuç tabloları

Şu anda aşağıdaki tabloların hiçbirinde gerçek sonuç yoktur. Ölçülmeyen değere 0 yazılmaz.

| runId / tarih | Commit / checksum | Protokol / senaryo | VU / süre / ısınma | Tekrar / sıra | Geçerlilik ve kanıt |
|---|---|---|---|---|---|

| runId | p95 ms | p99 ms | Toplam RPS | Başarılı RPS | Hata % | Eksik işlem | Ham sonuç bağlantısı |
|---|---|---|---|---|---|---|---|

| runId | CPU ölçüm tanımı ve değer | RAM MiB | Ağ giriş/çıkış ve kapsam | Yanıt gövdesi baytı | Telemetri aralığı |
|---|---|---|---|---|---|

| Kontrol | Beklenen | Gerçek sonuç | Kanıt | Durum |
|---|---|---|---|---|
| Üçlü veri eşdeğerliği | Ortak alanlar aynı | Çalıştırılmadı | Yok | Servis/ortam bekliyor |
| DB kesintisi/iptal | Kontrollü hata ve toparlanma | Çalıştırılmadı | Yok | İzole ortam bekliyor |
| İzleme ek yükü | Belirlenen yöntemle en fazla %3 | Ölçülmedi | Yok | Ortam/yöntem bekliyor |
| UI ve runner | Başlatma/durum/sonuç tutarlı | Çalıştırılmadı | Yok | UI/BFF/runner bekliyor |

## Tartışmada korunacak ayrımlar

- Birim ve sentetik test başarısı backend benchmark başarısı değildir.
- Açık PR, main'e alınmış veya gerçek ortamda doğrulanmış sayılmaz.
- Başarısız/doymuş koşular yok sayılmaz; başarılı koşu CSV'sinden ayrı incelenir.
- S2b az alan istediği için tam S2 ile eşdeğer iş yükü sonucu değildir.
- Tekrarlar ve değişkenlik sunulmadan protokolün genel üstünlüğü iddia edilmez.
- Docker Desktop tüm ortam ağ metriği servis bazlı tüketim diye sunulmaz.
- Kaynak sınırlaması, k6 yükü, cache, TLS/sıkıştırma ve bağlantı davranışı kaydedilir.

Final raporu öncesinde test sonuçları, ortam kayıtları, grafikler, gerçek sınırlılıklar ve kaynak bağlantıları eklenmelidir. İlerleme raporunun gönderimi ve danışman geri bildirimi ayrıca kayda alınmalıdır.
