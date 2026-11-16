# QA hazırlığının doğrulaması — 2 Kasım 2026

Dal: `azra/qa-http-preparation`, temel commit: `30d3cd5`. Bu kayıt QA kodunun doğrulamasıdır; backend kabul veya benchmark sonucu değildir.

## Çalıştırılan kontroller

| Kontrol | Sonuç | Kanıtın sınırı |
|---|---|---|
| Node yardımcı kod testleri | 9/9 başarılı | İçerik, hatalar, ID normalizasyonu, UTF-8, yapılandırma ve CSV kabul/red kontrolleri |
| k6 smoke, doğru sentetik cevaplar | 34/34 check; exit 0 | Proje backend'i değil, yerel mock |
| k6 kısa yük denemeleri | REST S1/S2 ve GraphQL S1/S2/S2b: sıfır hata, exit 0 | Yalnız yük betiğinin çalışma kontrolü |
| k6 smoke, HTTP 200 içinde kasıtlı GraphQL hatası | Beklenen exit 99 | Hatalı cevabın başarı sayılmadığı doğrulandı |
| k6 load, aynı GraphQL hatası | Beklenen exit 99, başarısızlık oranı 1 | İşlem başına hata sayımı doğrulandı |
| PowerShell wrapper + mock | 34 check; ham/özet/konsol/run kayıtları oluştu | Evidence mock-only, eksik actuator kaydı açık |
| Mock sonucu CSV'ye aktarma | Beklendiği gibi reddedildi | Sahte sonuç gerçek benchmark tablosuna girmiyor |
| Ölçüm sırasında yanıtı geciktirilip kesilen istek | Başlatılan/tamamlanan sayaç farkı kaydedilir; özet failed-or-incomplete olur | k6 exit 0 bile olsa wrapper ve CSV kontrolü eksik koşuyu reddeder |

Araç: Grafana k6 v2.3.0, resmî GitHub sürümünden taşınabilir Windows binary; yayımlanan SHA256 listesiyle doğrulandı. Global kurulum veya Docker değişikliği yapılmadı. Yerel mock işlem sonunda kapatıldı.

Ham öz kontrol kayıtları (gitignore kapsamında): `tests/k6/results/runtime-check/`. Tekrar komutu: `node tests/k6/unit/runtime-check.mjs <k6-exe-yolu>`.

## Henüz doğrulanmayanlar

Gerçek REST/GraphQL uçları, seed/checksum, gRPC, Docker ortamı, gerçek protokol eşdeğerliği, 50/200/500 kullanıcı kapasitesi, CPU/RAM/ağ ve %3 izleme ek yükü, UI ve runner API. Hazırlanan koda sahip olmak bu gereksinimlerin sağlandığı anlamına gelmez.

## İlk gerçek koşu

Berkay hazır REST sürümünü, Doruk erişilebilir ortam adresini bildirdikten sonra `tests/k6/README.md` içindeki REST smoke komutu çalıştırılır. Çıkan bulgular kaydedilir; hatalar giderilmeden karşılaştırmalı performans sonucuna geçilmez. Ortam salıdan sonra hazırlanacağından 4 Kasım için canlı demo taahhüdü yoktur.
