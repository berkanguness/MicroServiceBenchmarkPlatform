import { useState, type ComponentType, type CSSProperties } from 'react';
import MacbookAir from '../dashboard';
import * as HastaKayitModulu from './hastakayıt';
import Randevular from './randevular';

export type SayfaTipi = 'ana-sayfa' | 'hasta-kayit' | 'randevular';

const HastaKayitBileseni =
  (HastaKayitModulu as { default?: ComponentType; HastaKayit?: ComponentType }).default ||
  (HastaKayitModulu as { HastaKayit?: ComponentType }).HastaKayit ||
  (() => <div style={{ padding: 24 }}>Hasta Kayıt bileşeni bulunamadı.</div>);

export default function App() {
  const [aktifSayfa, setAktifSayfa] = useState<SayfaTipi>('ana-sayfa');

  return (
    <div
      style={{
        display: 'flex',
        minHeight: '100vh',
        backgroundColor: '#2a2c2b',
        color: '#f3f4f6',
        fontFamily: 'Inter, sans-serif',
      }}
    >
      {/* Sağ alana gelen sayfa bileşenlerinin içindeki ikinci menüleri gizler */}
      <style>{`
        .sag-icerik > div > aside,
        .sag-icerik > aside,
        .sag-icerik aside {
          display: none !important;
        }
      `}</style>

      {/* Tek ve Sabit Sol Menü */}
      <aside
        style={{
          width: 240,
          minWidth: 240,
          backgroundColor: '#232524',
          padding: '28px 18px',
          borderRight: '1px solid #4a4d4b',
          display: 'flex',
          flexDirection: 'column',
          gap: 28,
        }}
      >
        <div style={{ fontSize: 14, fontWeight: 700, lineHeight: 1.4, paddingLeft: 8 }}>
          HASTANE &amp; SAĞLIK
          <br />
          YÖNETİM SİSTEMİ
        </div>

        <ul
          style={{
            listStyle: 'none',
            padding: 0,
            margin: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: 8,
          }}
        >
          <li
            style={menuItemStyle(aktifSayfa === 'ana-sayfa')}
            onClick={() => setAktifSayfa('ana-sayfa')}
          >
            🏠 Ana Sayfa
          </li>
          <li
            style={menuItemStyle(aktifSayfa === 'hasta-kayit')}
            onClick={() => setAktifSayfa('hasta-kayit')}
          >
            👥 Hasta Kayıt
          </li>
          <li
            style={menuItemStyle(aktifSayfa === 'randevular')}
            onClick={() => setAktifSayfa('randevular')}
          >
            📅 Randevular
          </li>
          <li style={menuItemStyle(false)}>🧪 Laboratuvar Sonuçları</li>
          <li style={menuItemStyle(false)}>⚙️ Ayarlar</li>
        </ul>
      </aside>

      {/* Seçime Göre Değişen Sağ Ekran */}
      <section className="sag-icerik" style={{ flex: 1, minWidth: 0, overflowX: 'auto' }}>
        {aktifSayfa === 'ana-sayfa' && <MacbookAir />}
        {aktifSayfa === 'hasta-kayit' && <HastaKayitBileseni />}
        {aktifSayfa === 'randevular' && (
          <Randevular aktifSayfa={aktifSayfa} onSayfaDegistir={setAktifSayfa} />
        )}
      </section>
    </div>
  );
}

const menuItemStyle = (aktif: boolean): CSSProperties => ({
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '10px 14px',
  borderRadius: 999,
  backgroundColor: aktif ? '#474a48' : 'transparent',
  color: aktif ? '#ffffff' : '#9ca3af',
  fontSize: 13,
  fontWeight: aktif ? 600 : 500,
  cursor: 'pointer',
});
