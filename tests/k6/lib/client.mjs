import http from 'k6/http';
import { queries } from './contract.mjs';

export function request(protocol, scenario, id, baseUrl, timeout = '10s') {
  const params = { timeout, tags: { protocol, workload: scenario } };
  if (protocol === 'rest') {
    const suffix = scenario === 'S1' ? 'summary' : 'lab-history';
    params.tags.name = `REST ${suffix}`;
    return http.get(`${baseUrl}/api/v1/patients/${encodeURIComponent(id)}/${suffix}`, params);
  }
  params.tags.name = `GraphQL ${scenario}`;
  params.headers = { 'Content-Type': 'application/json', Accept: 'application/json' };
  return http.post(`${baseUrl}/graphql`, JSON.stringify({ query: queries[scenario], operationName: scenario, variables: { id: String(id) } }), params);
}
