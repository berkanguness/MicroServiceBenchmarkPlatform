import test from 'node:test';
import assert from 'node:assert/strict';
import { decode, validate, expectedError, canonical, utf8Bytes, validateContentType } from '../lib/contract.mjs';
import { config } from '../lib/config.mjs';
import { fixture } from './fixture.mjs';
import { validateProbe } from '../preflight.mjs';

test('valid S1/S2, empty patient and separate S2b are accepted', () => {
  assert.ok(validate(fixture('S1'), 'S1'));
  assert.ok(validate(fixture('S2'), 'S2'));
  assert.ok(validate(fixture('S1', '2'), 'S1', '2'));
  assert.ok(validate(fixture('S2', '2'), 'S2', '2'));
  assert.ok(validate(fixture('S2', '1', true), 'S2b'));
});
test('partial, duplicate, unsorted and wrong-patient responses are rejected', () => {
  let data = fixture('S2'); data.reports[0].items.pop(); assert.equal(validate(data, 'S2'), false);
  data = fixture('S2'); data.reports[1].items[0].id = data.reports[0].items[0].id; assert.equal(validate(data, 'S2'), false);
  data = fixture('S2'); data.reports.reverse(); assert.equal(validate(data, 'S2'), false);
  data = fixture('S1'); data.patient.id = 5; assert.equal(validate(data, 'S1'), false);
});
test('HTTP 200 with GraphQL errors, null root or malformed JSON is failure', () => {
  for (const body of ['{"errors":[{"message":"oops"}]}', '{"data":{"patientSummary":null}}', '<html>']) {
    assert.equal(decode('graphql', 200, body, 'S1').ok, false);
  }
  assert.equal(decode('rest', 503, '{}', 'S1').ok, false);
});
test('timeout, connection, redirects and non-200 2xx/3xx responses are anomalous', () => {
  for (const status of [0, 201, 204, 206, 301, 302, 307]) {
    assert.equal(decode('rest', status, '{}', 'S1').ok, false);
  }
  assert.equal(decode('rest', 0, '', 'S1').reason, 'timeout_or_connection_failure');
  assert.equal(validateContentType({ 'Content-Type': 'text/html' }, 'rest'), false);
  assert.equal(validateContentType({ 'content-type': 'application/json; charset=utf-8' }, 'rest'), true);
  assert.equal(validateContentType({ 'Content-Type': 'application/graphql-response+json' }, 'graphql'), true);
});
test('preflight rejects malformed REST JSON, GraphQL errors, null roots and invalid contract shapes', () => {
  const restValid = JSON.stringify(fixture('S1'));
  assert.equal(validateProbe('rest', 200, 'application/json', restValid).ok, true);
  assert.equal(validateProbe('graphql', 200, 'application/graphql-response+json',
    JSON.stringify({ data: { patientSummary: fixture('S1') } })).ok, true);
  assert.equal(validateProbe('rest', 200, 'application/json', '<html>').ok, false);
  assert.equal(validateProbe('rest', 200, 'text/html', restValid).ok, false);
  assert.equal(validateProbe('graphql', 200, 'application/json', JSON.stringify({
    data: { patientSummary: fixture('S1') }, errors: [{ message: 'injected' }]
  })).ok, false);
  assert.equal(validateProbe('graphql', 200, 'application/json', JSON.stringify({
    data: { patientSummary: null }
  })).ok, false);
  assert.equal(validateProbe('graphql', 200, 'application/json', JSON.stringify({
    data: { patientSummary: { patient: { id: 3 } } }
  })).ok, false);
});
test('expected negative tests require correct status, root and error code', () => {
  assert.ok(expectedError('rest', 404, '{"status":404,"code":"PATIENT_NOT_FOUND"}', 'S1', 'PATIENT_NOT_FOUND', 404));
  const body = JSON.stringify({ data: { patientSummary: null }, errors: [{ extensions: { code: 'INVALID_ARGUMENT' } }] });
  assert.ok(expectedError('graphql', 200, body, 'S1', 'INVALID_ARGUMENT', 400));
  assert.equal(expectedError('graphql', 503, body, 'S1', 'INVALID_ARGUMENT', 400), false);
});
test('canonical normalization changes IDs but does not hide changed numeric results', () => {
  assert.deepEqual(canonical({ id: 3, resultValue: 0.5 }), canonical({ resultValue: 0.5, id: '3' }));
  assert.notDeepEqual(canonical({ id: 3, resultValue: 0.5 }), canonical({ id: '3', resultValue: 1 }));
});
test('UTF-8 counts Turkish and supplementary characters as bytes', () => {
  for (const text of ['Ölçüm', 'ABC', '🧪']) assert.equal(utf8Bytes(text), Buffer.byteLength(text));
});
test('measurement cannot silently invent metadata or warmup; gRPC and invalid S2b blocked', () => {
  assert.throws(() => config({}));
  assert.throws(() => config({ PROFILE: 's1-200', WARMUP_SECONDS: '10' }));
  assert.throws(() => config({ PROTOCOL: 'grpc', WARMUP_SECONDS: '10' }));
  assert.throws(() => config({ WORKLOAD: 'S2b', PROTOCOL: 'rest', WARMUP_SECONDS: '10' }));
});
test('direct k6 permits safe shakedown without evidence but rejects official evidence omission', () => {
  assert.doesNotThrow(() => config({ PROFILE: 'shakedown', WARMUP_SECONDS: '5', DURATION_SECONDS: '10' }));
  assert.throws(() => config({
    PROFILE: 'low', WARMUP_SECONDS: '5', DURATION_SECONDS: '10',
    RUN_ID: 'test', GIT_SHA: 'a'.repeat(40), DATASET_CHECKSUM: 'b'.repeat(64),
    READY_FOR_MEASUREMENT: 'yes'
  }));
});
test('S1 fixes 200 VU / 120 sec; spike duration validated', () => {
  const env = { PROFILE: 's1-200', WARMUP_SECONDS: '10', RUN_ID: 'test', GIT_SHA: 'a'.repeat(40), DATASET_CHECKSUM: 'b'.repeat(64), READY_FOR_MEASUREMENT: 'yes', EVIDENCE: 'backend' };
  const c = config(env); assert.equal(c.vus, 200); assert.equal(c.seconds, 120);
  assert.throws(() => config({ ...env, PROFILE: 'spike', DURATION_SECONDS: '10', SPIKE_STAGES: '[{"seconds":5,"target":500}]' }));
});
