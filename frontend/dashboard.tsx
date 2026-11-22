import * as React from 'react';
import { useState } from 'react';

type NavigationItem = {
  label: string;
  icon: string;
};

const navigationItems: NavigationItem[] = [
  { label: "Ana Sayfa", icon: "🏠" },
  { label: "Hasta Kayıt", icon: "👥" },
  { label: "Randevular", icon: "🗓️" },
  { label: "Laboratuvar Sonuçları", icon: "🧪" },
  { label: "Ayarlar", icon: "⚙️" },
];

export const MacbookAir = () => {
  const [searchTerm, setSearchTerm] = useState("");
  const [activeNavigation, setActiveNavigation] = useState("Ana Sayfa");
  const [isLabDataLoaded, setIsLabDataLoaded] = useState(false);

  // Hasta Kayıt Form State'i
  const [formData, setFormData] = useState({
    tcKimlik: '',
    adSoyad: '',
    dogumTarihi: '',
    cinsiyet: '',
    telefon: '',
    eposta: '',
    sikayet: ''
  });

  const handleFormChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleFormSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    console.log('Kaydedilen Hasta:', formData);
    alert('Hasta başarıyla kaydedildi!');
  };

  return (
    <div style={{
      display: 'flex',
      minHeight: '100vh',
      backgroundColor: '#111215',
      color: '#ffffff',
      fontFamily: 'Inter, system-ui, sans-serif',
      boxSizing: 'border-box'
    }}>
      {/* Sol Sidebar */}
      <aside style={{
        width: '280px',
        padding: '36px 28px',
        borderRight: '1px solid #1e2025',
        display: 'flex',
        flexDirection: 'column',
        gap: '32px'
      }}>
        <h1 style={{
          fontSize: '18px',
          fontWeight: 700,
          lineHeight: '1.4',
          margin: 0,
          letterSpacing: '0.5px'
        }}>
          HASTANE &amp; SAĞLIK<br />YÖNETİM SİSTEMİ
        </h1>

        <nav style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
          {navigationItems.map((item) => {
            const isActive = activeNavigation === item.label;
            return (
              <button
                key={item.label}
                type="button"
                onClick={() => setActiveNavigation(item.label)}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '14px',
                  padding: '10px 16px',
                  borderRadius: '12px',
                  border: 'none',
                  backgroundColor: isActive ? '#242730' : 'transparent',
                  color: isActive ? '#ffffff' : '#9ca3af',
                  fontSize: '15px',
                  fontWeight: isActive ? 600 : 400,
                  cursor: 'pointer',
                  textAlign: 'left',
                  transition: 'background-color 0.2s'
                }}
              >
                <span style={{ fontSize: '18px' }}>{item.icon}</span>
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
      </aside>

      {/* Sağ Ana İçerik */}
      <main style={{ flex: 1, padding: '36px 48px', display: 'flex', flexDirection: 'column', gap: '32px' }}>
        {/* Üst Bar: Arama + Aksiyonlar */}
        <header style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          maxWidth: '860px'
        }}>
          <div style={{
            position: 'relative',
            width: '460px',
            backgroundColor: '#1b1d24',
            borderRadius: '24px',
            display: 'flex',
            alignItems: 'center',
            padding: '8px 18px',
            gap: '10px'
          }}>
            <span style={{ color: '#6b7280', fontSize: '16px' }}>🔍</span>
            <input
              type="search"
              placeholder="Search"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              style={{
                background: 'transparent',
                border: 'none',
                outline: 'none',
                color: '#ffffff',
                fontSize: '15px',
                width: '100%'
              }}
            />
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
            <button
              type="button"
              aria-label="Bildirimler"
              style={{
                background: 'none',
                border: 'none',
                fontSize: '22px',
                cursor: 'pointer'
              }}
            >
              🔔
            </button>
            <button
              type="button"
              aria-label="Profil"
              style={{
                background: 'none',
                border: 'none',
                fontSize: '22px',
                cursor: 'pointer'
              }}
            >
              👤
            </button>
          </div>
        </header>

        {/* KOŞULLU ALAN: Ana Sayfa veya Hasta Kayıt */}
        {activeNavigation === 'Hasta Kayıt' ? (
          /* HASTA KAYIT SAYFASI */
          <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            <h2 style={{ fontSize: '18px', fontWeight: 700, margin: 0, letterSpacing: '0.5px' }}>
              HASTA KAYIT
            </h2>

            <form onSubmit={handleFormSubmit} style={{
              backgroundColor: '#1b1d24',
              borderRadius: '20px',
              border: '1.5px solid #2a2e39',
              padding: '32px',
              maxWidth: '860px',
              display: 'flex',
              flexDirection: 'column',
              gap: '24px',
              boxSizing: 'border-box'
            }}>
              {/* İki Sütunlu Grid */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '24px' }}>
                {/* Sol Sütun */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                  <div>
                    <label style={labelStyle}>T.C. KİMLİK NO</label>
                    <input
                      type="text"
                      name="tcKimlik"
                      maxLength={11}
                      placeholder="11 haneli T.C. Kimlik No girin"
                      value={formData.tcKimlik}
                      onChange={handleFormChange}
                      style={inputStyle}
                    />
                  </div>

                  <div>
                    <label style={labelStyle}>ADI SOYADI</label>
                    <input
                      type="text"
                      name="adSoyad"
                      placeholder="Adı ve Soyadı"
                      value={formData.adSoyad}
                      onChange={handleFormChange}
                      style={inputStyle}
                    />
                  </div>

                  <div>
                    <label style={labelStyle}>DOĞUM TARİHİ</label>
                    <input
                      type="date"
                      name="dogumTarihi"
                      value={formData.dogumTarihi}
                      onChange={handleFormChange}
                      style={inputStyle}
                    />
                  </div>

                  <div>
                    <label style={labelStyle}>CİNSİYET</label>
                    <select
                      name="cinsiyet"
                      value={formData.cinsiyet}
                      onChange={handleFormChange}
                      style={{ ...inputStyle, cursor: 'pointer' }}
                    >
                      <option value="">Seçiniz</option>
                      <option value="Kadın">Kadın</option>
                      <option value="Erkek">Erkek</option>
                    </select>
                  </div>
                </div>

                {/* Sağ Sütun */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                  <div>
                    <label style={labelStyle}>TELEFON NUMARASI</label>
                    <input
                      type="tel"
                      name="telefon"
                      placeholder="0 (5XX) XXX XX XX"
                      value={formData.telefon}
                      onChange={handleFormChange}
                      style={inputStyle}
                    />
                  </div>

                  <div>
                    <label style={labelStyle}>E-POSTA</label>
                    <input
                      type="email"
                      name="eposta"
                      placeholder="ornek@eposta.com"
                      value={formData.eposta}
                      onChange={handleFormChange}
                      style={inputStyle}
                    />
                  </div>

                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
                    <label style={labelStyle}>ŞİKAYET / ÖN TANI</label>
                    <textarea
                      name="sikayet"
                      placeholder="Hastanın şikayetini veya ön tanısını girin..."
                      value={formData.sikayet}
                      onChange={handleFormChange}
                      style={{ ...inputStyle, flex: 1, minHeight: '90px', resize: 'none' }}
                    />
                  </div>
                </div>
              </div>

              {/* Butonlar */}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '14px', marginTop: '10px' }}>
                <button
                  type="button"
                  onClick={() => setFormData({ tcKimlik: '', adSoyad: '', dogumTarihi: '', cinsiyet: '', telefon: '', eposta: '', sikayet: '' })}
                  style={{
                    padding: '10px 22px',
                    backgroundColor: '#242730',
                    color: '#9ca3af',
                    border: 'none',
                    borderRadius: '8px',
                    fontSize: '13px',
                    fontWeight: 600,
                    cursor: 'pointer'
                  }}
                >
                  İPTAL
                </button>
                <button
                  type="submit"
                  style={{
                    padding: '10px 24px',
                    backgroundColor: '#2563eb',
                    color: '#ffffff',
                    border: 'none',
                    borderRadius: '8px',
                    fontSize: '13px',
                    fontWeight: 600,
                    cursor: 'pointer'
                  }}
                >
                  HASTAYI KAYDET
                </button>
              </div>
            </form>
          </div>
        ) : (
          /* ANA SAYFA (MEVCUT KARTLAR) */
          <>
            <div>
              <h2 style={{
                fontSize: '18px',
                fontWeight: 700,
                margin: 0,
                letterSpacing: '0.5px'
              }}>
                HASTANE RANDEVU &amp; TAHLİL DETAYI
              </h2>
            </div>

            <div style={{
              display: 'flex',
              gap: '24px',
              alignItems: 'stretch',
              maxWidth: '860px'
            }}>
              {/* Hasta Bilgileri Kartı */}
              <article style={{
                flex: 1,
                backgroundColor: '#131b2e',
                borderRadius: '20px',
                border: '1.5px solid #1e3a8a',
                padding: '28px',
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'space-between',
                minHeight: '260px'
              }}>
                <div>
                  <h3 style={{ margin: '0 0 16px 0', fontSize: '17px', fontWeight: 600 }}>
                    Hasta Bilgileri
                  </h3>
                  <p style={{ margin: '0 0 12px 0', fontSize: '14px', lineHeight: 1.6, color: '#d1d5db' }}>
                    <span style={{ color: '#9ca3af' }}>Ad Soyad:</span><br />
                    <strong style={{ color: '#ffffff' }}>Ayşe Gül</strong>
                  </p>
                  <p style={{ margin: 0, fontSize: '14px', lineHeight: 1.6, color: '#d1d5db' }}>
                    <span style={{ color: '#9ca3af' }}>Randevu:</span><br />
                    <strong style={{ color: '#ffffff' }}>19.10.2026 - 14.00</strong>
                  </p>
                </div>

                <button
                  type="button"
                  onClick={() => setIsLabDataLoaded(!isLabDataLoaded)}
                  style={{
                    marginTop: '20px',
                    backgroundColor: '#2563eb',
                    color: '#ffffff',
                    border: 'none',
                    borderRadius: '8px',
                    padding: '10px 16px',
                    fontSize: '14px',
                    fontWeight: 500,
                    cursor: 'pointer',
                    textAlign: 'center',
                    transition: 'background-color 0.2s'
                  }}
                >
                  {isLabDataLoaded ? "Veriler Güncellendi ✓" : "Laboratuvardan Veri Çek"}
                </button>
              </article>

              {/* Laboratuvar & Tahlil Sonuçları Kartı */}
              <article style={{
                flex: 1,
                backgroundColor: '#0f2420',
                borderRadius: '20px',
                border: '1.5px solid #065f46',
                padding: '28px',
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'space-between',
                minHeight: '260px'
              }}>
                <div>
                  <h3 style={{ margin: '0 0 16px 0', fontSize: '17px', fontWeight: 600 }}>
                    Laboratuvar &amp; Tahlil Sonuçları
                  </h3>
                  <p style={{ margin: '0 0 16px 0', fontSize: '14px', lineHeight: 1.6, color: '#d1d5db' }}>
                    <span style={{ color: '#9ca3af' }}>Vital Bulgular:</span><br />
                    - Nabız: 72bpm<br />
                    - Tansiyon: 120/80
                  </p>
                  <p style={{ margin: 0, fontSize: '14px', lineHeight: 1.6, color: '#d1d5db' }}>
                    <span style={{ color: '#9ca3af' }}>Son Tahliller:</span><br />
                    Kan Sayımı (Tamamlandı)
                  </p>
                </div>
              </article>
            </div>
          </>
        )}
      </main>
    </div>
  );
};

// Form Stilleri
const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: '11px',
  fontWeight: 600,
  color: '#9ca3af',
  marginBottom: '6px',
  letterSpacing: '0.4px'
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 14px',
  backgroundColor: '#111215',
  border: '1px solid #2e323e',
  borderRadius: '8px',
  color: '#ffffff',
  fontSize: '13px',
  outline: 'none',
  boxSizing: 'border-box'
};

export default MacbookAir;
