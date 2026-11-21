# Altyapı (Docker Compose) Kullanım Kılavuzu

Bu doküman, projenin Docker ortamını (PostgreSQL + Prometheus + cAdvisor + Grafana) nasıl çalıştıracağını anlatır.

## Gereksinimler
- Docker Desktop (Windows / Mac) veya Docker Engine + Compose (Linux)

## İlk kurulum
1. `.env.example` dosyasını kopyalayıp adını `.env` yap:
   ```
   cp .env.example .env
   ```
2. `.env` içindeki değerleri doldur:

| Değişken | Ne işe yarar |
|---|---|
| `LAB_DB_PASSWORD` | PostgreSQL (`labdb`) kullanıcısı `lab`'ın şifresi |
| `GRAFANA_ADMIN_PASSWORD` | Grafana `admin` kullanıcısının şifresi |

`.env` dosyası `.gitignore`'dadır, **asla commit'lenmez**. `GRAFANA_ADMIN_PASSWORD` eksikse `docker compose up` açıklayıcı bir hata verir. `LAB_DB_PASSWORD` eksikse Compose yalnızca uyarı verir ve PostgreSQL ilk açılışta hata verir, bu yüzden ikisini de doldur.

## Çalıştırma
```
docker compose up -d      # hepsini arka planda başlat
docker compose ps         # durumlarına bak
docker compose logs grafana --tail 30
docker compose down       # durdur (veri korunur)
```

## Servisler ve portlar

| Servis | Adres | Not |
|---|---|---|
| Prometheus | http://localhost:9090 | Metrikleri toplar (15 sn'de bir). Targets: Status → Targets |
| cAdvisor | http://localhost:8082 | Konteyner CPU / RAM / ağ metriklerini üretir |
| Grafana | http://localhost:3000 | Sadece bu bilgisayardan erişilir (`127.0.0.1`) |

## Grafana'ya giriş
- Kullanıcı: `admin`
- Şifre: `.env` içindeki `GRAFANA_ADMIN_PASSWORD`
- Prometheus veri kaynağı ve **Konteyner İzleme** dashboard'u otomatik yüklenir (provisioning), elle kurulum gerekmez.
- Dashboard dosyadan yüklendiği için arayüzden kaydedilemez (Grafana "Cannot save provisioned dashboard" der). Değişiklik için JSON'u dışa aktarıp `monitoring/grafana/provisioning/dashboards/konteyner-izleme.json` dosyasına yazın.

## Sık karşılaşılan durumlar
- **Şifreyi `.env`'de değiştirdim ama giriş çalışmıyor:** Grafana şifreyi ilk açılışta kendi volume'una yazar. Verileri silmeden sıfırlamak için: `docker compose exec grafana grafana cli admin reset-admin-password '<yeni-şifre>'`. PostgreSQL şifresi de ilk açılışta yazılır, onu değiştirmek veri kaybı gerektirir (aşağıdaki "Ortamı tamamen sıfırlama" bölümüne bak).
- **Port zaten kullanımda hatası:** Aynı portu kullanan başka bir program var, onu kapat.
- **CPU/Bellek panelleri "No data" gösteriyor:** cAdvisor'un Docker/containerd'e bağlanıp bağlanmadığına bak: `docker compose logs cadvisor | grep "container factory"` (Windows PowerShell'de `grep` yerine `Select-String "container factory"`). docker veya containerd satırında "failed" yazıyorsa konteyner bazlı metrik gelmez.

## cAdvisor notları
- cAdvisor v0.61.1 kullanılır (Docker 29 ile uyumlu). Docker ve containerd soketlerine `--docker` ve `--containerd` bayraklarıyla bağlanır (`docker-compose.yml`'de ekli).
- Dashboard'da konteynerler `lab-grafana` gibi adlarıyla görünür. Ağ trafiği konteyner başına (`eth0`, gelen ve giden) ölçülür.

## Ortamı tamamen sıfırlama
`docker compose down -v` tüm volume'ları siler (PostgreSQL ve Grafana verisi dahil). Sadece gerçekten sıfırdan başlamak istediğinde kullan, sonra `docker compose up -d` ile yeniden kur.