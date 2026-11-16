// Run: node tests/k6/unit/runtime-check.mjs <path-to-k6>
// Tests the test harness; never connects to the project backend.
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const k6 = process.argv[2];
if (!k6) throw new Error('k6 executable path required');
const out = 'tests/k6/results/runtime-check';
mkdirSync(out, { recursive: true });
const records = [];
for (const fault of ['', 'graphql', 'interrupted']) {
  const server = spawn(process.execPath, ['tests/k6/unit/mock-server.mjs'], { env: { ...process.env, MOCK_PORT: '18762', MOCK_FAULT: fault }, stdio: ['ignore', 'pipe', 'inherit'] });
  try {
    await Promise.race([once(server.stdout, 'data'), once(server, 'exit').then(() => { throw new Error('Mock exited before startup'); }), new Promise((_, reject) => { const timer = setTimeout(() => reject(new Error('Mock startup timeout')), 5000); timer.unref(); })]);
    const cases = fault === 'interrupted' ? [['load', 'rest', 'S1']] : fault ? [['smoke', 'both', 'S2'], ['load', 'graphql', 'S2']]
      : [['smoke', 'both', 'S2'], ['load', 'rest', 'S1'], ['load', 'rest', 'S2'], ['load', 'graphql', 'S1'], ['load', 'graphql', 'S2'], ['load', 'graphql', 'S2b']];
    for (const [mode, protocol, workload] of cases) {
      const name = `${mode}-${protocol}-${workload}-${fault || 'valid'}`;
      const path = `${out}/${name}.json`;
      const env = { ...process.env, EVIDENCE: 'mock-only', BASE_URL: 'http://127.0.0.1:18762', PROTOCOL: protocol, SUMMARY_PATH: path,
        PROFILE: 'shakedown', WORKLOAD: workload, WARMUP_SECONDS: '1', DURATION_SECONDS: '2', VUS: '1', THINK_TIME_SECONDS: '0.05', REQUEST_TIMEOUT: fault === 'interrupted' ? '30s' : '10s' };
      const run = spawnSync(k6, ['run', `tests/k6/${mode}.js`], { env, encoding: 'utf8', timeout: 30000 });
      writeFileSync(`${out}/${name}.log`, (run.stdout || '') + (run.stderr || '') + (run.error?.message || ''));
      assert.equal(run.error, undefined);
      assert.equal(run.status, fault === 'graphql' ? 99 : 0, name);
      const result = JSON.parse(readFileSync(path, 'utf8'));
      const metrics = result.data.metrics;
      if (mode === 'smoke') assert.equal(metrics.checks.values.rate === 1, !fault);
      else {
        assert.ok(result.totalOperations > 0);
        assert.equal(metrics.qa_failure_rate.values.rate, fault === 'graphql' ? 1 : 0);
        assert.equal(result.successfulOperations, fault === 'graphql' ? 0 : result.totalOperations);
        assert.equal(result.cpu, null);
        if (fault === 'interrupted') assert.ok(result.incompleteOperations > 0);
        else assert.equal(result.incompleteOperations, 0);
        assert.equal(result.status, fault ? 'failed-or-incomplete' : 'completed');
      }
      records.push({ name, expectedExit: fault === 'graphql' ? 99 : 0, actualExit: run.status, evidence: 'mock-only', passed: true });
      console.log(`${name}: PASS (mock-only, exit=${run.status})`);
    }
  } finally {
    server.kill();
    if (server.exitCode === null) await once(server, 'exit');
  }
}
writeFileSync(`${out}/verification.json`, JSON.stringify({ checkedAt: new Date().toISOString(), records }, null, 2));
