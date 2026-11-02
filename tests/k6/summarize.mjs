// Run: node tests/k6/summarize.mjs path/to/summary.json [more summaries]
// Missing telemetry stays empty; mock results cannot enter a project comparison.
import { readFileSync } from 'node:fs';
const paths = process.argv.slice(2);
if (!paths.length) throw new Error('load summary JSON paths required');
const columns = ['runId', 'protocol', 'workload', 'profile', 'gitSha', 'datasetChecksum', 'totalOperations', 'successfulOperations', 'totalRps', 'successfulRps', 'errorPercent', 'p95ms', 'p99ms', 'cpu', 'ramMiB', 'networkBytes'];
const rows = paths.map(path => {
  const r = JSON.parse(readFileSync(path, 'utf8'));
  if (r.evidence !== 'backend' || !r.config?.official) throw new Error(`${path}: mock/shakedown/smoke cannot enter benchmark CSV`);
  if (r.status !== 'completed' || r.thresholdsPassed !== true || r.incompleteOperations !== 0 || !(r.totalOperations > 0)) {
    throw new Error(`${path}: failed/incomplete run cannot enter comparison CSV; review raw output separately`);
  }
  const m = r.data.metrics;
  const row = { ...r, protocol: r.config.protocol, workload: r.config.scenario, profile: r.config.profile,
    errorPercent: m.qa_failure_rate?.values?.rate == null ? null : m.qa_failure_rate.values.rate * 100,
    p95ms: m.qa_operation_ms?.values?.['p(95)'], p99ms: m.qa_operation_ms?.values?.['p(99)'] };
  return columns.map(k => row[k] == null ? '' : `"${String(row[k]).replaceAll('"', '""')}"`).join(',');
});
console.log([columns.join(','), ...rows].join('\n'));
