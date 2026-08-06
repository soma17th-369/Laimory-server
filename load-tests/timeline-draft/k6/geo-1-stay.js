// geo-1-stay — core 경로에 동기 지오코딩 1좌표를 더한 representative 시나리오.
//
// 요청당 STAY 1개(고유 좌표 1개) → Kakao 2콜(coord2address 1 + keyword 1). 전용 WebClient pool(기본 20),
// pending acquire queue(기본 20), response timeout, 제한적 retry, circuit breaker와 그 동안 붙잡히는
// servlet worker까지 함께 측정한다.
//
// ⚠️ 반드시 #257 simulator로 전환된 상태에서만 실행한다. k6는 애플리케이션이 어느 base URL을 보는지 알 수
// 없으므로 이 스크립트는 의도를 명시하는 CONFIRM_SIMULATOR 게이트만 강제한다. 실제 증명은 simulator host의
// request journal count다(README의 geo run 절차 참고).
//
// 실행 예(repo root 기준):
//   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app VUS=1 \
//     CONFIRM_AI_NOOP=yes CONFIRM_SIMULATOR=yes \
//     k6 run load-tests/timeline-draft/k6/geo-1-stay.js

import { loadConfig } from './lib/config.js';
import { geoStayBody } from './lib/payload.js';
import { buildOptions, runSpike, setupSpike, summarizeSpike } from './lib/spike.js';

const STAY_COUNT = 1;

if (__ENV.CONFIRM_SIMULATOR !== 'yes') {
  throw new Error(
    'geo 시나리오는 CONFIRM_SIMULATOR=yes가 필요합니다. '
    + '애플리케이션이 #257 simulator(APP_GEO_KAKAO_BASE_URL, dummy KAKAO_REST_API_KEY)로 전환됐는지 '
    + '먼저 확인하세요 — 실제 Kakao로 대량 호출이 나가면 안 됩니다.'
  );
}

const config = loadConfig('geo-1-stay', 'g1', 100);

export const options = buildOptions(config);

export function setup() {
  return setupSpike(config);
}

export default function (data) {
  runSpike(config, function (cfg, vu) {
    return geoStayBody(cfg, vu, STAY_COUNT);
  }, data);
}

export function handleSummary(data) {
  return summarizeSpike(config, data);
}
