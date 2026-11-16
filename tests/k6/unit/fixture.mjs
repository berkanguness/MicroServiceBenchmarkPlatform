// Deliberately invented data for testing QA code. NOT the backend seed or benchmark data.
export function fixture(scenario, id = scenario === 'S1' ? '3' : '1', narrow = false) {
  const patient = { id: Number(id), tcNo: '00000000000', fullName: 'Sentetik QA', birthDate: '2000-01-01', bloodType: 'A+' };
  const report = (reportId, n) => ({ id: reportId, doctorName: 'Sentetik', sampleType: 'SERUM',
    createdAt: new Date(Date.UTC(2025, 11, 31) - n * 86400000).toISOString(),
    items: Array.from({ length: 10 }, (_, i) => ({ id: reportId * 10 + i + 1, testCode: `T${i}`, testName: `Örnek ${i}`, resultValue: i + 0.5, unit: 'u', isCritical: false })) });
  if (scenario === 'S1') return { patient, latestReport: id === '2' ? null : report(101, 0) };
  const reports = id === '2' ? [] : Array.from({ length: 100 }, (_, i) => report(100 - i, i));
  if (narrow) return { reports: reports.map(r => ({ items: r.items.map(i => ({ testName: i.testName, resultValue: i.resultValue })) })) };
  return { patient, reports };
}
