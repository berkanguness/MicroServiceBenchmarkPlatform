import React, { useState } from 'react';

export const HastaKayit: React.FC = () => {
  const [formData, setFormData] = useState({
    tcKimlik: '',
    adSoyad: '',
    dogumTarihi: '',
    cinsiyet: '',
    telefon: '',
    eposta: '',
    sikayet: ''
  });

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    console.log('Kaydedilen Hasta Verileri:', formData);
    alert('Hasta kaydı başarıyla oluşturuldu!');
  };

  return (
    <div style={{ flex: 1, padding: '24px', color: '#fff', fontFamily: 'sans-serif' }}>
      <h2 style={{ fontSize: '18px', fontWeight: 'bold', marginBottom: '20px', letterSpacing: '0.5px' }}>
        HASTA KAYIT
      </h2>

      {/* Büyük Gri Form Kartı */}
      <form onSubmit={handleSubmit} style={{
        backgroundColor: '#1E1E1E',
        borderRadius: '16px',
        padding: '28px',
        maxWidth: '750px',
        display: 'flex',
        flexDirection: 'column',
        gap: '20px',
        border: '1px solid #2a2a2a'
      }}>
        {/* İki Sütunlu Grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '24px' }}>
          
          {/* Sol Sütun */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div>
              <label style={labelStyle}>T.C. KİMLİK NO</label>
              <input
                type="text"
                name="tcKimlik"
                maxLength={11}
                placeholder="11 haneli T.C. Kimlik No girin"
                value={formData.tcKimlik}
                onChange={handleChange}
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
                onChange={handleChange}
                style={inputStyle}
              />
            </div>

            <div>
              <label style={labelStyle}>DOĞUM TARİHİ</label>
              <input
                type="date"
                name="dogumTarihi"
                value={formData.dogumTarihi}
                onChange={handleChange}
                style={inputStyle}
              />
            </div>

            <div>
              <label style={labelStyle}>CİNSİYET</label>
              <select
                name="cinsiyet"
                value={formData.cinsiyet}
                onChange={handleChange}
                style={{ ...inputStyle, cursor: 'pointer' }}
              >
                <option value="">Seçiniz...</option>
                <option value="Kadın">Kadın</option>
                <option value="Erkek">Erkek</option>
              </select>
            </div>
          </div>

          {/* Sağ Sütun */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div>
              <label style={labelStyle}>TELEFON NUMARASI</label>
              <input
                type="tel"
                name="telefon"
                placeholder="0 (5XX) XXX XX XX"
                value={formData.telefon}
                onChange={handleChange}
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
                onChange={handleChange}
                style={inputStyle}
              />
            </div>

            <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
              <label style={labelStyle}>ŞİKAYET / ÖN TANI</label>
              <textarea
                name="sikayet"
                placeholder="Hastanın şikayetini veya ön tanısını girin..."
                value={formData.sikayet}
                onChange={handleChange}
                style={{ ...inputStyle, flex: 1, minHeight: '86px', resize: 'none' }}
              />
            </div>
          </div>
        </div>

        {/* Butonlar */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '10px' }}>
          <button
            type="button"
            onClick={() => setFormData({ tcKimlik: '', adSoyad: '', dogumTarihi: '', cinsiyet: '', telefon: '', eposta: '', sikayet: '' })}
            style={{
              padding: '10px 22px',
              backgroundColor: '#2A2A2A',
              color: '#bbb',
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
              padding: '10px 22px',
              backgroundColor: '#1E60FF',
              color: '#fff',
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
  );
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: '11px',
  fontWeight: 600,
  color: '#aaa',
  marginBottom: '6px',
  letterSpacing: '0.3px'
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '10px 14px',
  backgroundColor: '#141414',
  border: '1px solid #333',
  borderRadius: '8px',
  color: '#fff',
  fontSize: '13px',
  outline: 'none',
  boxSizing: 'border-box'
};

