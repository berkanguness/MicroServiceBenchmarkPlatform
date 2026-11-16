export function config(env) {
  const scenario = env.WORKLOAD || 'S1';
  const protocol = env.PROTOCOL || 'rest';
  const profile = env.PROFILE || 'shakedown';
  if (!['S1', 'S2', 'S2b'].includes(scenario)) throw new Error('WORKLOAD: S1/S2/S2b');
  if (!['rest', 'graphql'].includes(protocol)) throw new Error('gRPC sözleşmesi bekleniyor. PROTOCOL: rest/graphql');
  if (scenario === 'S2b' && protocol !== 'graphql') throw new Error('S2b yalnız GraphQL');
  if (!['shakedown', 's1-200', 'low', 'high', 'spike'].includes(profile)) throw new Error('Bilinmeyen PROFILE');
  const positive = (v, label) => { const n = Number(v); if (!Number.isInteger(n) || n <= 0) throw new Error(`${label} pozitif tam sayı olmalı`); return n; };
  const warmup = positive(env.WARMUP_SECONDS, 'WARMUP_SECONDS');
  const official = profile !== 'shakedown';
  if (official && (!env.RUN_ID || !env.GIT_SHA || !env.DATASET_CHECKSUM || env.READY_FOR_MEASUREMENT !== 'yes')) {
    throw new Error('Ölçüm için RUN_ID/GIT_SHA/DATASET_CHECKSUM ve READY_FOR_MEASUREMENT=yes gerekli');
  }
  let vus, seconds, stages;
  if (profile === 's1-200') {
    if (scenario !== 'S1') throw new Error('s1-200 yalnız S1');
    vus = 200; seconds = 120;
  } else {
    vus = profile === 'low' ? 50 : profile === 'high' ? 500 : positive(env.VUS || 1, 'VUS');
    seconds = positive(env.DURATION_SECONDS, 'DURATION_SECONDS');
    if (profile === 'spike') {
      if (!env.SPIKE_STAGES) throw new Error('SPIKE_STAGES JSON açıkça belirlenmeli');
      stages = JSON.parse(env.SPIKE_STAGES);
      if (!Array.isArray(stages) || !stages.length || stages.some(s => !Number.isInteger(s.seconds) || s.seconds <= 0 || !Number.isInteger(s.target) || s.target < 0)) throw new Error('Spike: [{seconds:pozitif,target:>=0}]');
      if (stages.reduce((a, s) => a + s.seconds, 0) !== seconds) throw new Error('DURATION_SECONDS stage toplamı olmalı');
    }
  }
  const pause = Number(env.THINK_TIME_SECONDS || 0);
  if (!Number.isFinite(pause) || pause < 0) throw new Error('THINK_TIME_SECONDS >=0');
  return { scenario, protocol, profile, warmup, vus, seconds, stages, pause, official,
    baseUrl: (env.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, ''), timeout: env.REQUEST_TIMEOUT || '10s' };
}
