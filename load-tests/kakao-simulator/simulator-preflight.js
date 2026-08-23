import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const REQUEST_COUNT = 40;
const BASE_URL = (__ENV.SIMULATOR_BASE_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const AUTHORIZATION = 'KakaoAK k6-257-dummy';
const EXPECTED_DELAY_MS = Number.parseInt(__ENV.EXPECTED_DELAY_MS || '50', 10);
const MAX_P95_OVERHEAD_MS = Number.parseInt(__ENV.MAX_P95_OVERHEAD_MS || '100', 10);

if (!Number.isInteger(EXPECTED_DELAY_MS) || EXPECTED_DELAY_MS < 0) {
  throw new Error('EXPECTED_DELAY_MS must be a non-negative integer');
}
if (!Number.isInteger(MAX_P95_OVERHEAD_MS) || MAX_P95_OVERHEAD_MS <= 0) {
  throw new Error('MAX_P95_OVERHEAD_MS must be a positive integer');
}

const simulatorDelayOverhead = new Trend('simulator_delay_overhead_ms', true);

export const options = {
  scenarios: {
    direct_coord_preflight: {
      executor: 'per-vu-iterations',
      vus: REQUEST_COUNT,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    simulator_delay_overhead_ms: [`p(95)<${MAX_P95_OVERHEAD_MS}`],
  },
};

function parseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

export function setup() {
  const health = http.get(`${BASE_URL}/__admin/health`);
  const healthBody = parseJson(health);
  const healthy = check(health, {
    'WireMock health is 200': (response) => response.status === 200,
    'WireMock version is 3.13.2': () =>
      healthBody !== null && healthBody.status === 'healthy' && healthBody.version === '3.13.2',
  });
  if (!healthy) {
    throw new Error(`WireMock health check failed at ${BASE_URL}`);
  }

  const reset = http.del(`${BASE_URL}/__admin/requests`);
  if (!check(reset, { 'request journal reset is 200': (response) => response.status === 200 })) {
    throw new Error('WireMock request journal reset failed');
  }
}

export default function () {
  const longitude = (126.9 + __VU * 0.0001).toFixed(6);
  const latitude = (37.5 + __VU * 0.0001).toFixed(6);
  const response = http.get(
    `${BASE_URL}/v2/local/geo/coord2address.json?x=${longitude}&y=${latitude}`,
    {
      headers: { Authorization: AUTHORIZATION },
      tags: { endpoint: 'coord2address' },
    },
  );
  const body = parseJson(response);

  check(response, {
    'coord2address status is 200': (candidate) => candidate.status === 200,
    'coord2address response shape is valid': () =>
      body !== null &&
      Array.isArray(body.documents) &&
      body.documents.length === 1 &&
      body.documents[0].road_address.address_name === '서울 테스트구 시뮬레이터로 251',
    'configured delay is applied': (candidate) => candidate.timings.duration >= EXPECTED_DELAY_MS - 5,
  });

  simulatorDelayOverhead.add(Math.max(0, response.timings.duration - EXPECTED_DELAY_MS));
}

export function teardown() {
  const count = http.post(
    `${BASE_URL}/__admin/requests/count`,
    JSON.stringify({ method: 'GET', urlPath: '/v2/local/geo/coord2address.json' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const countBody = parseJson(count);
  check(count, {
    'coord2address journal count is 40': (response) =>
      response.status === 200 && countBody !== null && countBody.count === REQUEST_COUNT,
  });

  const unmatched = http.get(`${BASE_URL}/__admin/requests/unmatched`);
  const unmatchedBody = parseJson(unmatched);
  check(unmatched, {
    'unmatched request count is zero': (response) =>
      response.status === 200 &&
      unmatchedBody !== null &&
      Array.isArray(unmatchedBody.requests) &&
      unmatchedBody.requests.length === 0,
  });
}
