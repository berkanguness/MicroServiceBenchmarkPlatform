import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { decode, validate, validateContentType, patientId } from './lib/contract.mjs';

export function validateProbe(protocol, status, contentType, body) {
  const headers = { 'Content-Type': contentType || '' };
  if (!validateContentType(headers, protocol)) return { ok: false, reason: 'incorrect_content_type' };
  const scenario = 'S1';
  const id = patientId(scenario);
  const result = decode(protocol, Number(status), body, scenario);
  if (!result.ok) return result;
  return validate(result.value, scenario, id)
    ? { ok: true, value: result.value }
    : { ok: false, reason: 'invalid_contract_shape' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [, , protocol, status, contentType, bodyPath] = process.argv;
  if (!['rest', 'graphql'].includes(protocol) || !status || !bodyPath) {
    throw new Error('usage: preflight.mjs <rest|graphql> <status> <content-type> <body-file>');
  }
  const body = readFileSync(bodyPath, 'utf8');
  const result = validateProbe(protocol, status, contentType, body);
  if (!result.ok) throw new Error(`preflight failed: ${result.reason}`);
  console.log('QA preflight contract: PASS');
}
