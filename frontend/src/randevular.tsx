import React, { useState } from 'react';
import type { SayfaTipi } from './App';

interface RandevularProps {
  aktifSayfa?: SayfaTipi;
  onSayfaDegistir?: (sayfa: SayfaTipi) => void;
}

interface Appointment {
  id: number;
  day: number;
  hour: string;
  range: string;
  name: string;
  tc: string;
  doctor: string;
  type: 'Muayene' | 'Kontrol';
  status: string;
  notes: string;
  color: string;
}

const HOURS = ['08:00', '09:00', '10:00', '11:00', '12:00', '13:00', '14:00', '15:00', '16:00', '17:00'];
const DAYS = ['Pazartesi', 'Salı', 'Çarşamba', 'Perşembe', 'Cuma'];

const INITIAL_APPOINTMENTS: Appointment[] = [
  { id: 1, day: 0, hour: '10:00', range: '10:00-10:30', name: 'Ayşe Yılmaz', tc: '12605288530', doctor: 'Dr. Demir', type: 'Muayene', status: 'Onaylandı', notes: 'Genel kontrol', color: '#b06c79' },
  { id: 2, day: 1, hour: '10:00', range: '10:00-10:30', name: 'Mehmet Öz', tc: '23498172610', doctor: 'Dr. Demir', type: 'Muayene', status: 'Onaylandı', notes: 'Kan tahlili sonrası', color: '#5c9ead' },
  { id: 3, day: 2, hour: '10:00', range: '10:00-10:30', name: 'Selin Arslan', tc: '34519283740', doctor: 'Dr. Demir', type: 'Muayene', status: 'Beklemede', notes: '-', color: '#b06c79' },
  { id: 4, day: 3, hour: '10:00', range: '10:00-10:30', name: 'Caner Aydın', tc: '45620394850', doctor: 'Dr. Demir', type: 'Muayene', status: 'Onaylandı', notes: '-', color: '#b06c79' },
  { id: 5, day: 4, hour: '10:00', range: '10:00-10:30', name: 'Ayşe Yılmaz', tc: '12605288530', doctor: 'Dr. Demir', type: 'Muayene', status: 'Onaylandı', notes: 'Reçete yenileme', color: '#84a955' },
  { id: 6, day: 0, hour: '14:00', range: '14:00-15:00', name: 'Ahmet Çelik', tc: '56731405960', doctor: 'Dr. Şahin', type: 'Kontrol', status: 'Onaylandı', notes: 'Röntgen incelemesi', color: '#84a955' },
  { id: 7, day: 1, hour: '14:00', range: '14:00-15:00', name: 'Ahmet Çelik', tc: '56731405960', doctor: 'Dr. Şahin', type: 'Kontrol', status: 'Onaylandı', notes: '-', color: '#5c9ead' },
  { id: 8, day: 4, hour: '14:00', range: '14:00-15:00', name: 'Elif Yıldız', tc: '67842516070', doctor: 'Dr. Şahin', type: 'Kontrol', status: 'Onaylandı', notes: '-', color: '#8187b8' },
];

export default function Randevular(_props: RandevularProps) {
  const [appointments, setAppointments] = useState<Appointment[]>(INITIAL_APPOINTMENTS);
  const [selectedApt, setSelectedApt] = useState<Appointment | null>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [weekLabel, setWeekLabel] = useState('3 – 7 Ekim 2026');

  const [name, setName] = useState('');
  const [tc, setTc] = useState('');
  const [day, setDay] = useState(0);
  const [hour, setHour] = useState('10:00');
  const [doctor, setDoctor] = useState('Dr. Demir');
  const [type, setType] = useState<'Muayene' | 'Kontrol'>('Muayene');

  const handleAddAppointment = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    const newApt: Appointment = {
      id: Date.now(),
      day,
      hour,
      range: `${hour}-10:30`,
      name: name.trim(),
      tc: tc.trim() || '11111111111',
      doctor: doctor.trim() || 'Dr. Demir',
      type,
      status: 'Onaylandı',
      notes: 'Yeni kayıt',
      color: type === 'Muayene' ? '#b06c79' : '#5c9ead',
    };

    setAppointments([newApt, ...appointments]);
    setName('');
    setTc('');
    setIsModalOpen(false);
  };

  const filteredAppointments = appointments.filter(
    (apt) =>
      apt.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      apt.doctor.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <main style={{ flex: 1, padding: '24px 32px', display: 'flex', flexDirection: 'column', gap: 20, backgroundColor: '#2a2c2b', color: '#f3f4f6', fontFamily: 'Inter, sans-serif' }}>
      {/* Üst Bar */}
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <h1 style={{ fontSize: 20, fontWeight: 700, margin: 0 }}>Randevular</h1>
          <button
            onClick={() => setIsModalOpen(true)}
            style={{ backgroundColor: '#2538d8', color: '#fff', border: 'none', padding: '8px 16px', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}
          >
            Yeni Randevu Al
          </button>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
          <div style={{ backgroundColor: '#424443', border: '1px solid #4a4d4b', borderRadius: 999, padding: '7px 16px', display: 'flex', alignItems: 'center', gap: 8, width: 260 }}>
            <span>🔍</span>
            <input
              type="text"
              placeholder="Search"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              style={{ background: 'transparent', border: 'none', outline: 'none', color: '#f3f4f6', fontSize: 13, width: '100%' }}
            />
          </div>
          <div style={{ display: 'flex', gap: 14, fontSize: 18 }}>
            <span>🔔</span>
            <span>👤</span>
          </div>
        </div>
      </header>

      {/* Haftalık Takvim */}
      <section style={{ backgroundColor: '#343635', border: '1px solid #4a4d4b', borderRadius: 10, padding: 16, position: 'relative' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14, padding: '0 8px' }}>
          <button onClick={() => setWeekLabel('3 – 7 Ekim 2026')} style={navBtnStyle}>&lt;</button>
          <span style={{ fontSize: 14, fontWeight: 600 }}>{weekLabel}</span>
          <button onClick={() => setWeekLabel('10 – 14 Ekim 2026')} style={navBtnStyle}>&gt;</button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '65px repeat(5, 1fr)', borderTop: '1px solid #4a4d4b', borderLeft: '1px solid #4a4d4b' }}>
          <div style={gridHeaderStyle}>Saat</div>
          {DAYS.map((d) => (
            <div key={d} style={gridHeaderStyle}>{d}</div>
          ))}

          {HOURS.map((h) => (
            <React.Fragment key={h}>
              <div style={{ padding: '12px 8px', fontSize: 11, color: '#9ca3af', textAlign: 'center', borderRight: '1px solid #4a4d4b', borderBottom: '1px solid #4a4d4b', height: 54 }}>
                {h}
              </div>
              {DAYS.map((_, dIdx) => {
                const apt = appointments.find((a) => a.day === dIdx && a.hour === h);
                return (
                  <div key={dIdx} style={{ borderRight: '1px solid #4a4d4b', borderBottom: '1px solid #4a4d4b', height: 54, padding: 3, position: 'relative' }}>
                    {apt && (
                      <div
                        onClick={() => setSelectedApt(apt)}
                        style={{
                          backgroundColor: apt.color,
                          borderRadius: 6,
                          padding: '6px 8px',
                          fontSize: 11,
                          lineHeight: 1.3,
                          color: '#fff',
                          cursor: 'pointer',
                          height: 98,
                          position: 'relative',
                          zIndex: 2,
                          boxShadow: '0 2px 6px rgba(0,0,0,0.25)',
                        }}
                      >
                        <div style={{ fontWeight: 700, fontSize: 10 }}>{apt.range}</div>
                        <div style={{ fontWeight: 600, marginTop: 2 }}>{apt.name}</div>
                        <div style={{ fontSize: 10, opacity: 0.9 }}>{apt.doctor} • {apt.type}</div>
                      </div>
                    )}
                  </div>
                );
              })}
            </React.Fragment>
          ))}
        </div>

        {/* Randevu Detayları Açılır Kartı */}
        {selectedApt && (
          <div style={{ position: 'absolute', top: 125, left: '52%', transform: 'translateX(-50%)', width: 280, backgroundColor: '#3a3d3b', border: '1px solid #6b7280', borderRadius: 8, padding: 16, zIndex: 20, boxShadow: '0 12px 28px rgba(0,0,0,0.5)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12, borderBottom: '1px solid #4a4d4b', paddingBottom: 8 }}>
              <h4 style={{ margin: 0, fontSize: 13, fontWeight: 700 }}>Randevu Detayları</h4>
              <button onClick={() => setSelectedApt(null)} style={{ background: 'none', border: 'none', color: '#9ca3af', fontSize: 16, cursor: 'pointer' }}>×</button>
            </div>
            <DetailRow label="Patient Name" value={selectedApt.name} />
            <DetailRow label="T.C. Kimlik No" value={selectedApt.tc} />
            <DetailRow label="Doctor" value={selectedApt.doctor} />
            <DetailRow label="Reason" value={selectedApt.type} />
            <DetailRow label="Status" value={selectedApt.status} />
            <DetailRow label="Notes" value={selectedApt.notes} />
          </div>
        )}
      </section>

      {/* Yaklaşan Randevular Listesi */}
      <section style={{ backgroundColor: '#252726', border: '1px solid #4a4d4b', borderRadius: 10, padding: '16px 20px' }}>
        <h2 style={{ fontSize: 14, fontWeight: 700, marginBottom: 12 }}>Yaklaşan Randevular</h2>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
          <thead>
            <tr>
              <th style={thStyle}>Saat</th>
              <th style={thStyle}>Hasta Adı</th>
              <th style={thStyle}>Doktor</th>
              <th style={thStyle}>Randevu Türü</th>
              <th style={thStyle}>Durum</th>
            </tr>
          </thead>
          <tbody>
            {filteredAppointments.slice(0, 5).map((apt) => (
              <tr key={apt.id}>
                <td style={tdStyle}>{apt.hour}</td>
                <td style={tdStyle}>{apt.name}</td>
                <td style={tdStyle}>{apt.doctor}</td>
                <td style={tdStyle}>{apt.type}</td>
                <td style={tdStyle}>{apt.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* Yeni Randevu Modalı */}
      {isModalOpen && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 50 }}>
          <form onSubmit={handleAddAppointment} style={{ backgroundColor: '#343635', border: '1px solid #4a4d4b', borderRadius: 10, padding: 20, width: 360, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <h3 style={{ margin: 0, fontSize: 15 }}>Yeni Randevu Al</h3>
            <input placeholder="Hasta Adı Soyadı" value={name} onChange={(e) => setName(e.target.value)} style={inputStyle} required />
            <input placeholder="T.C. Kimlik No" value={tc} onChange={(e) => setTc(e.target.value)} maxLength={11} style={inputStyle} />
            <select value={day} onChange={(e) => setDay(Number(e.target.value))} style={inputStyle}>
              {DAYS.map((d, i) => <option key={d} value={i}>{d}</option>)}
            </select>
            <select value={hour} onChange={(e) => setHour(e.target.value)} style={inputStyle}>
              <option value="10:00">10:00</option>
              <option value="14:00">14:00</option>
            </select>
            <input placeholder="Doktor" value={doctor} onChange={(e) => setDoctor(e.target.value)} style={inputStyle} />
            <select value={type} onChange={(e) => setType(e.target.value as 'Muayene' | 'Kontrol')} style={inputStyle}>
              <option value="Muayene">Muayene</option>
              <option value="Kontrol">Kontrol</option>
            </select>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 }}>
              <button type="button" onClick={() => setIsModalOpen(false)} style={{ backgroundColor: '#e5e7eb', color: '#111827', border: 'none', padding: '8px 14px', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
                İptal
              </button>
              <button type="submit" style={{ backgroundColor: '#2538d8', color: '#fff', border: 'none', padding: '8px 14px', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
                Kaydet
              </button>
            </div>
          </form>
        </div>
      )}
    </main>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '110px 1fr', fontSize: 11, marginBottom: 7, gap: 6 }}>
      <span style={{ color: '#9ca3af' }}>{label}</span>
      <span style={{ color: '#f3f4f6', fontWeight: 500 }}>{value}</span>
    </div>
  );
}

const navBtnStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: '#f3f4f6',
  fontSize: 16,
  cursor: 'pointer',
  padding: '4px 10px',
};

const gridHeaderStyle: React.CSSProperties = {
  padding: 10,
  textAlign: 'center',
  fontSize: 12,
  fontWeight: 600,
  borderRight: '1px solid #4a4d4b',
  borderBottom: '1px solid #4a4d4b',
  backgroundColor: '#2f3130',
};

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  color: '#9ca3af',
  fontWeight: 600,
  padding: '8px 10px',
  borderBottom: '1px solid #4a4d4b',
};

const tdStyle: React.CSSProperties = {
  padding: 10,
  borderBottom: '1px solid #353836',
};

const inputStyle: React.CSSProperties = {
  backgroundColor: '#232524',
  border: '1px solid #4a4d4b',
  borderRadius: 6,
  padding: '8px 10px',
  color: 'white',
  fontSize: 12,
};
