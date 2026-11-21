# Ekip görüşmesi ve kod inceleme notu

21 Kasım 2026. Pazartesi 23 Kasım'da danışmana sorulması, mevcut Çarşamba 25 Kasım görüşmesinin kod incelemesi olarak kullanılması konuşuluyor. Kod incelemesi niteliği/saat henüz onaylı değil. Takvimde onaylanmış randevu varsayılmadı.

## Kısa QA açıklaması

REST/GraphQL test hazırlığı PR #8 ile main'de. PR #36 ön kontrolleri ve sonuç tutarlılığını geliştiriyor; insan incelemesi/merge bekliyor. Tam S1 sorgusunu kullanmayan GraphQL preflight düzeltildi. Yerel sentetik doğrulama gerçek backend veya performans sonucu değildir. gRPC, gerçek smoke, yük ölçümleri, kaynak analizi ve izleme ek yükü henüz tamamlanmadı.

## Her kişiden alınacak somut çıktı

| Kişi | Soru | Görüşme sonunda kaydedilecek |
|---|---|---|
| Berkay | #15→#16→#17 hangi sırada/onayla birleşecek; ilk çalışan REST ve sonra GraphQL ne zaman? | Commit/branch, REST adresi ve teslim günü; seed full doğrulama yolu |
| Berkan | GetPatientSummary ve nested hasta/rapor modeli RFC ile ne zaman uyumlanacak? BFF ve runner API kimin? | Proto commit, gRPC adresi, sahibi ve test edilebilir gün |
| Doruk | LabResultService Compose ve uygulama scrape hedefi ne zaman hazır? Testler hangi makinede? | Base/management URL, çalışma komutu, CPU/RAM/heap, image sürümleri, ağ ölçüm sınırı |
| Aylin | #37'de import edilen src/hastakayıt dosyası neden depoda yok? İG-5 protokol seçimi, ms/KB, test başlatma ekranı hangi aşamada? | Eksik dosya, çalışan build, ekran/branch, API sözleşmesi ve teslim günü |
| Ekip | PR #36'yı kim inceleyecek? Deney planındaki öneriler uygun mu? | İnceleyici, kararlar ve tarihler |
| Danışman | Çarşamba görüşmesi notlandırmadaki kod incelemesi mi? İlerleme raporu teslimi alındı mı; final takvimi nedir? | Onaylı toplantı kapsamı ve teslim bilgisi |

Aylin 21 Kasım'da #37 ile frontend yükledi. Önceki rapordaki frontend kodu yok gözlemi 20 Kasım durumudur. Yeni kodda hasta/randevu ekranları var; laboratuvar menüsü henüz tıklama akışına bağlı görünmüyor. API çağrısı/protokol seçimi/yük testi tetikleyicisi bulunamadı. Bunlar statik inceleme bulguları; frontend build bu QA çalışmasında çalıştırılmadı. Frontend geliştirmesini QA üstlenmez.

## Kod incelemesinde gösterilecek dosyalar

- tests/k6/lib/contract.mjs: S1/S2 doğruluğu, GraphQL errors, sıralama ve ID normalizasyonu.
- tests/k6/preflight.mjs ve run.ps1: ortak GraphQL sorgusu, readiness, sürüm/veri ve ortam kapıları.
- tests/k6/load.js: ölçüm penceresi, ısınma ayrımı, kesilen işlemler.
- tests/k6/summarize.mjs: sayaç/hata oranı/RPS tutarlılığı, hatalı koşunun CSV'ye alınmaması.
- tests/k6/unit/preflight-runner.test.mjs: gerçek Windows PowerShell çalıştırıcısı + yerel stand-in; UP ilerler, DOWN durur. k6 yerine durdurucu kullanılır, gerçek yük uygulanmaz.
- docs/qa/DENEY-PLANI.md ve test-matrix.md: ne hazır, ne çalıştırılmadı ve ne bekleniyor.

## Hafta sonu ve sonrası

Cumartesi: preflight düzeltmesi/regresyon, sözleşme farkları, güncel PR ve pano kayıtları.
Pazar: deney planını oku; test komutunu ve smoke/load farkını anlatabil; toplantı sorularını kontrol et; ilerleme raporunun gönderim kaydını teyit et. Yöntem/sonuç şablonunu incele.
Pazartesi: yukarıdaki teslim günleri ve ortam kararlarını ekipten al; danışmana toplantı kapsamını sor.
İlk çalışan REST tesliminde: gRPC veya UI beklemeden gerçek smoke. Başarısızlığı kanıtıyla ilgili sahibine bildir.

GitHub'da görülmeyen yerel çalışmalar var olabilir. Görüşmede alınan cevaplar kişi + karar + tarih + bağlantı şeklinde kaydedilmeli. Yazılı teslim günü gelmezse ilgili QA kartında bağımlılık bekliyor olarak tutulmalı; yapılmamış test Done olmamalı.
