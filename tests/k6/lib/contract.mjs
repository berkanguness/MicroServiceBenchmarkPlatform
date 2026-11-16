// Source: docs/backend-a/implementation-contract.md sections 7.1 and 7.2.
const patient = 'id tcNo fullName birthDate bloodType';
const item = 'id testCode testName resultValue unit isCritical';
const report = `id doctorName sampleType createdAt items { ${item} }`;
export const queries = {
  S1: `query S1($id: ID!) { patientSummary(patientId: $id) { patient { ${patient} } latestReport { ${report} } } }`,
  S2: `query S2($id: ID!) { patientLabHistory(patientId: $id) { patient { ${patient} } reports { ${report} } } }`,
  S2b: 'query S2b($id: ID!) { patientLabHistory(patientId: $id) { reports { items { testName resultValue } } } }',
};
export const rootField = scenario => scenario === 'S1' ? 'patientSummary' : 'patientLabHistory';
export const patientId = scenario => scenario === 'S1' ? '3' : '1';
export function decode(protocol, status, body, scenario) {
  if (status !== 200) return { ok: false, reason: `http_${status}` };
  let parsed;
  try { parsed = JSON.parse(body); } catch (_) { return { ok: false, reason: 'invalid_json' }; }
  if (protocol === 'graphql') {
    if (parsed?.errors?.length) return { ok: false, reason: 'graphql_errors' };
    parsed = parsed?.data?.[rootField(scenario)];
  }
  if (!parsed || typeof parsed !== 'object') return { ok: false, reason: 'null_root' };
  return { ok: true, value: parsed };
}
const idOK = value => /^[1-9][0-9]*$/.test(String(value)) && Number.isSafeInteger(Number(value));
const strings = (obj, fields) => fields.every(key => typeof obj?.[key] === 'string');
export function validate(value, scenario, id = patientId(scenario)) {
  if (!value || typeof value !== 'object') return false;
  if (scenario !== 'S2b' && (!idOK(value.patient?.id) || String(value.patient.id) !== String(id)
      || !strings(value.patient, ['tcNo', 'fullName', 'birthDate', 'bloodType']))) return false;
  if (String(id) === '2') return scenario === 'S1'
    ? value.latestReport === null : Array.isArray(value.reports) && value.reports.length === 0;
  const reports = scenario === 'S1' ? [value.latestReport] : value.reports;
  if (!Array.isArray(reports) || reports.length !== (scenario === 'S1' ? 1 : 100)) return false;
  if (scenario === 'S1' && String(reports[0]?.id) !== '101') return false;
  const reportIds = new Set();
  const itemIds = new Set();
  let previous = null;
  for (const r of reports) {
    if (!Array.isArray(r?.items) || r.items.length !== 10) return false;
    if (scenario !== 'S2b') {
      if (!idOK(r.id) || reportIds.has(String(r.id)) || !strings(r, ['doctorName', 'sampleType', 'createdAt'])
          || !Number.isFinite(Date.parse(r.createdAt))) return false;
      reportIds.add(String(r.id));
      if (previous && (Date.parse(previous.createdAt) < Date.parse(r.createdAt)
          || (Date.parse(previous.createdAt) === Date.parse(r.createdAt) && Number(previous.id) <= Number(r.id)))) return false;
      previous = r;
    }
    let lastId = 0;
    for (const i of r.items) {
      if (!strings(i, ['testName']) || typeof i.resultValue !== 'number' || !Number.isFinite(i.resultValue)) return false;
      if (scenario !== 'S2b') {
        if (!idOK(i.id) || itemIds.has(String(i.id)) || Number(i.id) <= lastId
            || !strings(i, ['testCode', 'unit']) || typeof i.isCritical !== 'boolean') return false;
        itemIds.add(String(i.id)); lastId = Number(i.id);
      }
    }
  }
  return true;
}
export function expectedError(protocol, status, body, scenario, code, expectedStatus) {
  let parsed;
  try { parsed = JSON.parse(body); } catch (_) { return false; }
  if (protocol === 'rest') return status === expectedStatus && parsed?.code === code && parsed?.status === expectedStatus;
  return status === 200 && parsed?.data?.[rootField(scenario)] === null
    && Array.isArray(parsed.errors) && parsed.errors.length > 0
    && parsed.errors.every(e => e?.extensions?.code === code);
}
// IDs differ as REST number vs GraphQL string. Preserve arrays and all other values.
export function canonical(value, key = '') {
  if (key === 'id') return String(value);
  if (Array.isArray(value)) return value.map(v => canonical(v));
  if (value !== null && typeof value === 'object') return Object.keys(value).sort().reduce((out, k) => {
    out[k] = canonical(value[k], k); return out;
  }, {});
  return value;
}
export function utf8Bytes(text) {
  let bytes = 0;
  for (const char of text) {
    const code = char.codePointAt(0);
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
  }
  return bytes;
}
