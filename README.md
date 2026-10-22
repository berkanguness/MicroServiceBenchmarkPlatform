# 🏥 Hospital Microservices Benchmark Platform
> **YM491 Araştırma Projesi**  
> Mikroservis Mimarilerinde İletişim Protokollerinin Başarım Analizi (REST vs. gRPC vs. GraphQL)

## 📌 Proje Hakkında
Bu proje; hasta ve laboratuvar tahlil sistemleri senaryosu üzerinden REST, gRPC ve GraphQL protokollerinin gecikme (latency), saniye başına istek (RPS), ağ yükü (payload boyutu) ve donanım kaynak tüketimini (CPU/RAM) deneysel olarak karşılaştırmaktadır.

## 🛠️ Mimari ve Teknoloji Yığını
- **Backend:** Java 21, Spring Boot 3, Maven
- **Frontend:** React, TypeScript, Tailwind CSS, Vite
- **Protokoller:** REST (JSON), gRPC (HTTP/2 + Protobuf), GraphQL
- **Veritabanı:** PostgreSQL
- **Yük Testi:** k6
- **Gözlemlenebilirlik:** Prometheus, cAdvisor, Grafana
- **Altyapı:** Docker & Docker Compose

## 🚀 Hızlı Başlangıç (Quick Start)
Tüm ortamı tek komutla ayağa kaldırmak için:
```bash
docker-compose up --build
ilk derlemede -DskipTests kullanın
