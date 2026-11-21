// Run: node tests/k6/summarize.mjs path/to/summary.json [more summaries]
// Missing telemetry stays empty; mock/results cannot enter a project comparison.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
export function validateSummary(r, path = 'summary') {
  if (!isObject(r)) throw new Error(`${path}: summary JSON object required`);
  if (r.schemaVersion !== 1) throw new Error(`${path}: unsupported schemaVersion`);
  if (r.kind === 'http-smoke') throw new Error(`${path}: smoke runs cannot enter benchmark CSV`);
  if (r.evidence !== 'backend') throw new Error(`${path}: mock results cannot enter benchmark CSV`);
  if (!isObject(r.config) || r.config.official !== true) throw new Error(`${path}: only official measurement summaries allowed`);
  if (['smoke', 'shakedown'].includes(r.config.profile)) throw new Error(`${path}: smoke/shakedown runs cannot enter benchmark CSV`);
  if (!['rest', 'graphql'].includes(r.config.protocol) || !['S1', 'S2', 'S2b'].includes(r.config.scenario)) throw new Error(`${path}: malformed protocol/scenario`);
  if (!r.runId || !r.gitSha || !r.datasetChecksum) throw new Error(`${path}: runId/gitSha/datasetChecksum required`);
  if (!/^[0-9a-f]{7,40}$/i.test(r.gitSha)) throw new Error(`${path}: malformed gitSha`);
  if (!/^[0-9a-f]{64}$/i.test(r.datasetChecksum)) throw new Error(`${path}: malformed datasetChecksum`);
  if (r.status !== 'completed' || r.thresholdsPassed !== true || r.incompleteOperations !== 0 || !(r.totalOperations > 0)) {
    throw new Error(`${path}: failed/incomplete run cannot enter comparison CSV; review raw output separately`);
  }
  for (const key of ['startedOperations', 'successfulOperations', 'totalOperations']) {
    if (!Number.isInteger(r[key]) || r[key] < 0) throw new Error(`${path}: malformed ${key}`);
  }
  if (r.successfulOperations > r.totalOperations || r.startedOperations !== r.totalOperations || r.incompleteOperations !== 0) throw new Error(`${path}: inconsistent operation counts`);
  if (!isObject(r.data) || !isObject(r.data.metrics)) throw new Error(`${path}: malformed metrics`);
  const metrics = r.data.metrics;
  const totalMetric = metrics.qa_operations?.values?.count;
  const successfulMetric = metrics.qa_successes?.values?.count;
  const failure = metrics.qa_failure_rate?.values?.rate;
  const operation = r.data.metrics.qa_operation_ms?.values;
  if (typeof failure !== 'number' || !Number.isFinite(failure) || failure < 0 || failure > 1) throw new Error(`${path}: malformed qa_failure_rate`);
  if (typeof totalMetric !== 'number' || totalMetric !== r.totalOperations) throw new Error(`${path}: qa_operations count inconsistent with totalOperations`);
  if (typeof successfulMetric !== 'number' || successfulMetric !== r.successfulOperations) throw new Error(`${path}: qa_successes count inconsistent with successfulOperations`);
  if (!isObject(operation) || typeof operation['p(95)'] !== 'number' || typeof operation['p(99)'] !== 'number'
      || !Number.isFinite(operation['p(95)']) || !Number.isFinite(operation['p(99)'])
      || operation['p(95)'] < 0 || operation['p(99)'] < operation['p(95)']) throw new Error(`${path}: malformed qa_operation_ms`);
  for (const key of ['totalRps', 'successfulRps']) {
    if (typeof r[key] !== 'number' || !Number.isFinite(r[key]) || r[key] < 0) throw new Error(`${path}: malformed ${key}`);
  }
  const close = (actual, expected) => Math.abs(actual - expected) <= 1e-6 * Math.max(1, Math.abs(expected));
  const expectedFailure = (r.totalOperations - r.successfulOperations) / r.totalOperations;
  if (!close(failure, expectedFailure)) throw new Error(`${path}: qa_failure_rate inconsistent with successfulOperations`);
  const duration = r.config.seconds;
  if (!Number.isInteger(duration) || duration <= 0) throw new Error(`${path}: malformed measurement duration`);
  if (!close(r.totalRps, r.totalOperations / duration)) throw new Error(`${path}: totalRps inconsistent with measurement duration`);
  if (!close(r.successfulRps, r.successfulOperations / duration)) throw new Error(`${path}: successfulRps inconsistent with measurement duration`);
  return r;
}

const paths = process.argv.slice(2);
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const validateOnly = paths[0] === '--validate';
  const summaryPaths = validateOnly ? paths.slice(1) : paths;
  if (!summaryPaths.length) throw new Error(validateOnly ? 'summary JSON path required' : 'load summary JSON paths required');
  const columns = ['runId', 'protocol', 'workload', 'profile', 'gitSha', 'datasetChecksum', 'totalOperations', 'successfulOperations', 'totalRps', 'successfulRps', 'errorPercent', 'p95ms', 'p99ms', 'cpu', 'ramMiB', 'networkBytes'];
  const rows = summaryPaths.map(path => {
    let r;
    try { r = validateSummary(JSON.parse(readFileSync(path, 'utf8')), path); }
    catch (error) { if (error instanceof SyntaxError) throw new Error(`${path}: malformed summary JSON`); throw error; }
    if (validateOnly) return null;
    const m = r.data.metrics;
    const row = { ...r, protocol: r.config.protocol, workload: r.config.scenario, profile: r.config.profile,
      errorPercent: m.qa_failure_rate.values.rate * 100,
      p95ms: m.qa_operation_ms.values['p(95)'], p99ms: m.qa_operation_ms.values['p(99)'] };
    return columns.map(k => row[k] == null ? '' : `"${String(row[k]).replaceAll('"', '""')}"`).join(',');
  });
  if (validateOnly) process.exit(0);
  console.log([columns.join(','), ...rows].join('\n'));
}
