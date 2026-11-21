import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fixture } from './fixture.mjs';

const root = fileURLToPath(new URL('../../../', import.meta.url));
// Loopback stand-in only. No backend, database or performance result is produced.
for (const readiness of ['UP', 'DOWN']) {
  test(`PowerShell official GraphQL preflight with readiness ${readiness}`, {
    skip: process.platform !== 'win32', timeout: 30000,
  }, async () => {
    const tmp = mkdtempSync(join(tmpdir(), 'ym491-preflight-'));
    const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
    let queried = false;
    const server = createServer(async (req, res) => {
      res.setHeader('Content-Type', 'application/json');
      if (req.url === '/actuator/info') return res.end(JSON.stringify({ build: { git: { sha } }, lab: { dataset: { checksumSha256: 'a'.repeat(64) } } }));
      if (req.url === '/actuator/health/readiness') return res.end(JSON.stringify({ status: readiness }));
      if (req.url === '/graphql') {
        queried = true;
        let body = ''; for await (const chunk of req) body += chunk;
        const request = JSON.parse(body);
        // Respond according to the requested selection: the former id-only query
        // must not receive the full fixture and accidentally pass this regression.
        const required = ['tcNo', 'fullName', 'birthDate', 'bloodType', 'latestReport', 'doctorName', 'sampleType', 'createdAt', 'items', 'testCode', 'testName', 'resultValue', 'unit', 'isCritical'];
        const full = required.every(field => new RegExp(`\\b${field}\\b`).test(request.query));
        const value = full && request.variables.id === '3' ? fixture('S1') : { patient: { id: '3' } };
        return res.end(JSON.stringify({ data: { patientSummary: value } }));
      }
      res.writeHead(404); res.end('{}');
    });
    let child;
    try {
      server.listen(0, '127.0.0.1'); await once(server, 'listening');
      const url = `http://127.0.0.1:${server.address().port}`;
      const environment = Object.fromEntries(['resourceLimits', 'imageDigests', 'cachePolicy', 'protocolOrder', 'repetition', 'monitoringMode', 'equivalenceEvidence', 'fullVerificationEvidence'].map(k => [k, 'synthetic runner regression only']));
      writeFileSync(join(tmp, 'environment.json'), JSON.stringify(environment));
      // Stop at the k6 boundary. This verifies wrapper preflight, not a load run.
      writeFileSync(join(tmp, 'k6.ps1'), "Write-Output 'PREFLIGHT_REACHED_K6_BOUNDARY'\nexit 73\n");
      child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', resolve(root, 'tests/k6/run.ps1'), '-Mode', 'load', '-Protocol', 'graphql', '-BaseUrl', url, '-ManagementUrl', url, '-K6', join(tmp, 'k6.ps1'), '-EnvironmentRecord', join(tmp, 'environment.json')], {
        cwd: root, env: { ...process.env, PROFILE: 's1-200' }, windowsHide: true,
      });
      let output = '';
      child.stdout.on('data', x => { output += x; }); child.stderr.on('data', x => { output += x; });
      const timeout = setTimeout(() => child.kill(), 25000);
      const [code] = await once(child, 'close'); clearTimeout(timeout);
      assert.notEqual(code, 0, 'stand-in k6 deliberately terminates before measurement');
      assert.equal(queried, readiness === 'UP', output);
      assert.equal(output.includes('QA preflight contract: PASS'), readiness === 'UP', output);
      // The boundary output is captured by the wrapper; its explicit version
      // failure proves the preceding GraphQL request/validation completed.
      assert.equal(output.includes('k6 version komutu'), readiness === 'UP', output);
    } finally {
      child?.kill(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
      rmSync(tmp, { recursive: true, force: true });
    }
  });
}
