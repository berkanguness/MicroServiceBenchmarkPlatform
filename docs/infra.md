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

`.env` dosyası `.gitignore`'dadır, **asla commit'lenmez**. Eksik değişken varsa `docker compose up` açıklayıcı bir hata verir.

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
- Dashboard dosyadan yüklendiği için arayüzden kaydedilen değişiklikler kalıcı olmaz. Değişiklik için `monitoring/grafana/provisioning/dashboards/konteyner-izleme.json` düzenlenir.

## Sık karşılaşılan durumlar
- **Şifreyi `.env`'de değiştirdim ama giriş çalışmıyor:** Grafana ve PostgreSQL şifreyi ilk açılışta volume'a yazar. Sıfırlamak için `docker compose down -v` (tüm verileri siler) ve tekrar `up -d`.
- **Port zaten kullanımda hatası:** Aynı portu kullanan başka bir program var, onu kapat.
- **CPU/Bellek panelleri "No data" gösteriyor:** cAdvisor, diğer konteynerlerle aynı anda başlayınca onları görmeyebilir. `docker compose restart cadvisor` yazıp bir dakika bekle.

## Bilinen sınırlamalar (Docker Desktop)
- cAdvisor, Docker Desktop'ta konteyner bazlı metrik için `--containerd-namespace=moby` bayrağına ihtiyaç duyar (`docker-compose.yml`'de ekli).
- Docker Desktop'ta ağ trafiği (gelen ve giden) konteyner bazında değil, tüm Docker ortamı için görünür. Linux sunucuda konteyner bazında gelmesi beklenir.
- Konteyner isimleri yerine imaj adları (`image` etiketi) gösterilir.