import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync, rmdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('CSV accepts complete runs and rejects incomplete/failed/mock runs', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ym491-export-'));
  const file = join(dir, 'summary.json');
  const script = fileURLToPath(new URL('../summarize.mjs', import.meta.url));
  const sha = 'a'.repeat(40), checksum = 'b'.repeat(64);
  const valid = { schemaVersion: 1, evidence: 'backend', config: { official: true, protocol: 'rest', scenario: 'S1', profile: 's1-200', seconds: 10 },
    runId: '20261120-120000', gitSha: sha, datasetChecksum: checksum, startedOperations: 10,
    status: 'completed', thresholdsPassed: true, incompleteOperations: 0, totalOperations: 10, successfulOperations: 10, totalRps: 1, successfulRps: 1,
    cpu: null, ramMiB: null, networkBytes: null, data: { metrics: {
      qa_operations: { values: { count: 10 } }, qa_successes: { values: { count: 10 } },
      qa_failure_rate: { values: { rate: 0 } }, qa_operation_ms: { values: { 'p(95)': 12, 'p(99)': 14 } } } } };
  writeFileSync(file, JSON.stringify(valid));
  const validateResult = spawnSync(process.execPath, [script, '--validate', file], { encoding: 'utf8' });
  assert.equal(validateResult.status, 0);
  try {
    for (const change of [{},
      { evidence: 'mock-only' },
      { kind: 'http-smoke' },
      { status: 'failed-or-incomplete' },
      { incompleteOperations: 1 },
      { thresholdsPassed: false },
      { totalOperations: 0 },
      { successfulOperations: 9 },
      { startedOperations: 9 },
      { totalRps: 99 },
      { successfulRps: 99 },
      { data: { metrics: {
        qa_operations: { values: { count: 10 } }, qa_successes: { values: { count: 10 } },
        qa_failure_rate: { values: { rate: 0.5 } }, qa_operation_ms: { values: { 'p(95)': 12, 'p(99)': 14 } } } } },
      { schemaVersion: 99 },
      { data: { metrics: { qa_failure_rate: { values: { rate: 'bad' } }, qa_operation_ms: { values: {} } } } },
      { gitSha: 'not-a-sha' },
      { datasetChecksum: 'short' }]) {
      writeFileSync(file, JSON.stringify({ ...valid, ...change }));
      const result = spawnSync(process.execPath, [script, file], { encoding: 'utf8' });
      assert.equal(result.status, Object.keys(change).length ? 1 : 0);
      if (!Object.keys(change).length) assert.match(result.stdout, /,,,\s*$/); // telemetry stays empty
    }
  } finally { rmSync(file); rmdirSync(dir); }
});
