import http from 'node:http';
import { fixture } from './fixture.mjs';
import { canonical } from '../lib/contract.mjs';
// Loopback-only QA harness; no database, no backend emulation claim.
const port = Number(process.env.MOCK_PORT || 18761);
const serverStarted = Date.now();
const server = http.createServer(async (req, res) => {
  let scenario, id, gql = req.url.startsWith('/graphql');
  if (gql) {
    let body = ''; for await (const chunk of req) body += chunk;
    const parsed = JSON.parse(body); scenario = parsed.operationName; id = parsed.variables.id;
  } else {
    const match = req.url.match(/^\/api\/v1\/patients\/([^/]+)\/(summary|lab-history)$/);
    if (!match) { res.writeHead(404); res.end('{}'); return; }
    id = decodeURIComponent(match[1]); scenario = match[2] === 'summary' ? 'S1' : 'S2';
  }
  const root = scenario === 'S1' ? 'patientSummary' : 'patientLabHistory';
  const status = id === '1001' ? 404 : (!/^[1-9][0-9]*$/.test(id) || !Number.isSafeInteger(Number(id))) ? 400 : 200;
  const code = status === 404 ? 'PATIENT_NOT_FOUND' : 'INVALID_ARGUMENT';
  let output;
  if (status !== 200) output = gql ? { data: { [root]: null }, errors: [{ extensions: { code } }] } : { status, code };
  else {
    let data = fixture(scenario, id, scenario === 'S2b');
    if (gql) data = canonical(data);
    output = gql ? { data: { [root]: data } } : data;
    if (process.env.MOCK_FAULT === 'graphql' && gql) output = { data: { [root]: null }, errors: [{ message: 'injected error' }] };
  }
  const reply = () => { res.writeHead(gql ? 200 : status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(output)); };
  if (process.env.MOCK_FAULT === 'interrupted' && Date.now() - serverStarted > 1700) setTimeout(reply, 30000);
  else reply();
});
server.listen(port, '127.0.0.1', () => console.log(`QA MOCK ONLY http://127.0.0.1:${port}`));
