# 4 Kasım için QA durumu

## Sorumluluk

Azra: k6 testleri, kalite kontrol, metrik analizi ve raporlama. Doruk: Docker/Compose ve Prometheus/Grafana. Docker teslimi beklenmeden test hazırlığı yapılır; gerçek koşu ortamı bekler. Berkay: veri/REST/GraphQL; Berkan: BFF/gRPC; Aylin: frontend. Bu değişiklik QA hazırlığı kapsamındadır.

## Tamamlanan hazırlık

- API sözleşmesinden S1/S2/S2b sorguları ve kontrol kuralları çıkarıldı.
- REST ve GraphQL için doğru hasta, adet/sıra, boş sonuç, hata kodu, HTTP 200 içindeki GraphQL hatası ve normalize edilmiş eşdeğerlik kontrolleri yazıldı.
- Isınmayı ayrı tutan yük testleri, 200 VU/120 saniye profili, 50/500 ve yapılandırılabilir spike desteği yazıldı.
- Ham çıktı/özet/sürüm kaydı ve CSV dönüştürme hazırlandı; eksik telemetri boş kalır.
- Test kodunun yerel sentetik verilerle doğrulanması ayrı tutuldu. Backend'e karşı başarı veya performans sonucu henüz yok.

## Şimdi yapılacaklar

1. Azra: `tests/k6/README.md` içindeki dosya ve akışı öğren; smoke ile load farkını, başarı/hatayı ve S2b ayrımını anlatabilecek hâle gel.
2. Berkay hazır REST sürümünü bildirdiğinde: hasta 3 özeti ve hasta 1 geçmişiyle REST smoke. GraphQL sonra aynı matrise eklenir.
3. Doruk ortamı hazırladığında: servis/management adresi, kaynak sınırları, image sürümleri, k6'nın çalışacağı makine, izleme ve zaman damgası eşleştirilir. Tam ortamı Azra'nın bilgisayarına kurmak zorunlu değil.
4. Berkan gRPC şemasını düzelttiğinde: istemci ve eşdeğerlik eklenir; üçlü kıyas ondan sonra.
5. Smoke ve eşdeğerlik geçince: düşük yük, S1 200 VU, yüksek yük/spike, tekrarlar, grafik/analiz.

## 4 Kasım'da gösterilebilecek kanıtlar

Kod dosyaları + test matrisi + sentetik sunucuda test altyapısı doğrulama kayıtları. Sunumda "REST/GraphQL test altyapısı hazırlandı; gerçek backend doğrulaması ve ölçümler ortam/servis teslimini bekliyor" durumu korunur. Sahte sunucunun süreleri proje performansı diye gösterilmez. Gerçek servis yetişirse yalnız gerçekten çalıştırılmış sonuç eklenir.

## Açık kararlar

- S1 adı: belgede vital bulgu, modelde hasta özeti/son tahlil. Danışmanla terminoloji uzlaştırılacak.
- Doğrudan LabResultService ölçümü: Azra/Berkan teyidi; BFF yoluyla karıştırılmayacak.
- Warmup/tekrar/spike süreleri, timeout, cache ve kaynaklar: ekip kararı, henüz resmî sabitler değil.
- %3 izleme ek yükü: kapalı kolun kapsamı Azra/Doruk ile belirlenecek; ölçümsüz sağlandı denmeyecek.
- UI tek tık test başlatma: runner API sahibi Aylin/Berkan/Azra arasında netleşecek.

## Test matrisi ve takip

19 maddelik ilk inceleme matrisi `docs/qa/test-matrix.md` dosyasında. Hazırlanan script ve gerçek test sonucu ayrı sütunlarda tutulur. CI, Docker, backend ve frontend bu değişiklikte değiştirilmez.
