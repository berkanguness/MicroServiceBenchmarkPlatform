import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { config } from './lib/config.mjs';
import { request } from './lib/client.mjs';
import { decode, validate, patientId, utf8Bytes } from './lib/contract.mjs';

const c = config(__ENV);
const started = new Counter('qa_started');
const operations = new Counter('qa_operations');
const successes = new Counter('qa_successes');
const failures = new Rate('qa_failure_rate');
const reasons = new Counter('qa_failure_reasons');
const latency = new Trend('qa_operation_ms', true);
const bytes = new Trend('qa_http_response_body_bytes');
const stages = [{ duration: `${c.warmup}s`, target: c.vus }, ...(c.stages
  ? c.stages.map(s => ({ duration: `${s.seconds}s`, target: s.target }))
  : [{ duration: `${c.seconds}s`, target: c.vus }])];
export const options = {
  scenarios: { benchmark: { executor: 'ramping-vus', startVUs: c.vus, stages, gracefulRampDown: '0s', gracefulStop: '15s' } },
  thresholds: c.profile === 'high' || c.profile === 'spike'
    ? { qa_operations: ['count>0'] }
    : { qa_operations: ['count>0'], qa_failure_rate: ['rate==0'] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  noConnectionReuse: false, noVUConnectionReuse: false, discardResponseBodies: false,
  tags: { protocol: c.protocol, workload: c.scenario, profile: c.profile, run_id: __ENV.RUN_ID || 'local-shakedown' },
};

export default function () {
  const sinceStart = (Date.now() - exec.scenario.startTime) / 1000;
  const measured = sinceStart >= c.warmup && sinceStart < c.warmup + c.seconds;
  const start = Date.now();
  if (measured) started.add(1);
  const response = request(c.protocol, c.scenario, patientId(c.scenario), c.baseUrl, c.timeout);
  const elapsed = Date.now() - start; // validation and parsing deliberately outside timing
  if (measured) {
    const result = decode(c.protocol, response.status, response.body, c.scenario);
    const ok = result.ok && validate(result.value, c.scenario);
    operations.add(1); successes.add(ok ? 1 : 0); failures.add(!ok); latency.add(elapsed);
    if (!ok) reasons.add(1, { reason: result.ok ? 'invalid_content' : result.reason });
    if (typeof response.body === 'string') bytes.add(utf8Bytes(response.body));
  }
  if (c.pause) sleep(c.pause);
}

export function handleSummary(data) {
  const count = name => data.metrics[name]?.values?.count ?? null;
  const incompleteOperations = count('qa_started') === null ? null : count('qa_started') - (count('qa_operations') || 0);
  const thresholdsPassed = Object.values(data.metrics).every(m => Object.values(m.thresholds || {}).every(t => t.ok === true));
  const summary = {
    schemaVersion: 1, evidence: __ENV.EVIDENCE || 'backend',
    status: incompleteOperations === 0 && thresholdsPassed ? 'completed' : 'failed-or-incomplete',
    thresholdsPassed, startedOperations: count('qa_started'), incompleteOperations,
    runId: __ENV.RUN_ID || null, gitSha: __ENV.GIT_SHA || null, datasetChecksum: __ENV.DATASET_CHECKSUM || null,
    config: c, measurementBoundary: 'Requests started inside measurement window; completion may extend into graceful stop. Interrupted iterations must be reviewed. Raw built-in HTTP metrics include warmup; use qa_* for measurement.',
    totalOperations: count('qa_operations'), successfulOperations: count('qa_successes'),
    totalRps: count('qa_operations') === null ? null : count('qa_operations') / c.seconds,
    successfulRps: count('qa_successes') === null ? null : count('qa_successes') / c.seconds,
    cpu: null, ramMiB: null, networkBytes: null, serializationMs: null,
    data,
  };
  return { [__ENV.SUMMARY_PATH || 'load-summary.json']: JSON.stringify(summary, null, 2),
    stdout: `QA ${c.protocol}/${c.scenario}/${c.profile}: total=${summary.totalOperations}, success=${summary.successfulOperations}. CPU/RAM Doruk'un telemetrisinden eklenecek.\n` };
}
