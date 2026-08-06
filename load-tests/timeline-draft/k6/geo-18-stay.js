// geo-18-stay — heavy sensitivity. representative profile이 아니라 좌표 수가 늘 때의 민감도만 본다.
//
// 요청당 STAY 18개(고유 좌표 18개) → 요청 하나가 Kakao 36콜을 만든다. 서버의 요청별 병렬 상한은
// `app.geo.lookup-concurrency`(기본 20)이고 공개 입력 상한은 `app.geo.max-unique-coordinates`(기본 30)라
// 18은 상한 안이면서 pool(기본 20)을 한 요청만으로 거의 채우는 값이다.
//
// 이 결과는 core·geo-1과 나란히 놓지 않는다 — 별도 heavy sensitivity로만 기록한다.
//
// ⚠️ geo-1-stay와 같은 simulator 전제. 실제 Kakao로 실행하면 요청 1,000개가 36,000콜이 된다.
//
// 실행 예(repo root 기준):
//   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app VUS=1 \
//     CONFIRM_AI_NOOP=yes CONFIRM_SIMULATOR=yes \
//     k6 run load-tests/timeline-draft/k6/geo-18-stay.js

import { loadConfig } from './lib/config.js';
import { geoStayBody } from './lib/payload.js';
import { buildOptions, runSpike, setupSpike, summarizeSpike } from './lib/spike.js';

const STAY_COUNT = 18;

if (__ENV.CONFIRM_SIMULATOR !== 'yes') {
  throw new Error(
    'geo 시나리오는 CONFIRM_SIMULATOR=yes가 필요합니다. '
    + '애플리케이션이 #257 simulator(APP_GEO_KAKAO_BASE_URL, dummy KAKAO_REST_API_KEY)로 전환됐는지 '
    + '먼저 확인하세요 — 실제 Kakao로 대량 호출이 나가면 안 됩니다.'
  );
}

const config = loadConfig('geo-18-stay', 'g18', 200);

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
