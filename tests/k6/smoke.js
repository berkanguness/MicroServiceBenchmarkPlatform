import { check } from 'k6';
import { request } from './lib/client.mjs';
import { decode, validate, expectedError, canonical } from './lib/contract.mjs';

const base = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const mode = __ENV.PROTOCOL || 'rest';
if (!['rest', 'graphql', 'both'].includes(mode)) throw new Error('PROTOCOL: rest, graphql veya both');
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] }, discardResponseBodies: false };

export default function () {
  const protocols = mode === 'both' ? ['rest', 'graphql'] : [mode];
  const responses = {};
  for (const protocol of protocols) {
    responses[protocol] = {};
    for (const [scenario, id] of [['S1', '3'], ['S2', '1'], ['S1', '2'], ['S2', '2']]) {
      const res = request(protocol, scenario, id, base);
      const data = decode(protocol, res.status, res.body, scenario);
      const valid = data.ok && validate(data.value, scenario, id);
      check(valid, { [`${protocol} ${scenario} patient=${id} shape/count/order`]: v => v });
      if (valid) responses[protocol][`${scenario}-${id}`] = data.value;
    }
    for (const scenario of ['S1', 'S2']) {
      for (const [id, code, status] of [['1001', 'PATIENT_NOT_FOUND', 404], ['0', 'INVALID_ARGUMENT', 400], ['-1', 'INVALID_ARGUMENT', 400], ['abc', 'INVALID_ARGUMENT', 400], ['9223372036854775808', 'INVALID_ARGUMENT', 400]]) {
        const res = request(protocol, scenario, id, base);
        check(res, { [`${protocol} ${scenario} invalid=${id}`]: r => expectedError(protocol, r.status, r.body, scenario, code, status) });
      }
    }
    if (protocol === 'graphql') {
      const res = request(protocol, 'S2b', '1', base);
      const data = decode(protocol, res.status, res.body, 'S2b');
      const valid = data.ok && validate(data.value, 'S2b');
      check(valid, { 'GraphQL S2b 1000 selected items': v => v });
      const full = responses.graphql['S2-1'];
      const projection = full?.reports.map(r => ({ items: r.items.map(i => ({ testName: i.testName, resultValue: i.resultValue })) }));
      check(valid && !!full && JSON.stringify(canonical(data.value)) === JSON.stringify(canonical({ reports: projection })), {
        'GraphQL S2b equals S2 projection': v => v,
      });
    }
  }
  if (mode === 'both') {
    for (const key of ['S1-3', 'S2-1', 'S1-2', 'S2-2']) {
      const a = responses.rest[key], b = responses.graphql[key];
      check(!!a && !!b && JSON.stringify(canonical(a)) === JSON.stringify(canonical(b)), { [`REST/GraphQL equivalent ${key}`]: v => v });
    }
  }
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH || 'smoke-summary.json']: JSON.stringify({ kind: 'http-smoke', evidence: __ENV.EVIDENCE || 'backend', baseUrl: base, protocol: mode, data }, null, 2),
    stdout: `Smoke tamamlandı. checks=${JSON.stringify(data.metrics.checks?.values)}. Bu test performans sonucu değildir.\n` };
}
