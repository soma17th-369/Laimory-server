// geo-day — 실측 하루 분포(이동 12·체류 13·알림 41·일정 2 = 68 아이템)를 **실제 좌표 포함**으로 보낸다.
//
// mixed-day와 같은 분포·행 수지만 체류·이동이 진짜 STAY/MOVEMENT다. 요청당 고유 좌표 37개
// (STAY 13 + MOVEMENT 양끝 24) → 정상 시 Kakao 74콜. 실환경 하루치 요청이 지오코딩 경로 전체
// (WebClient pool/pending, timeout/retry/circuit, servlet worker 대기)를 실제 비율로 통과한다.
//
// 실행 전제 두 가지(순서대로):
//   1. dev `.env`에 `APP_GEO_MAX_UNIQUE_COORDINATES=40` 이상 — 현재 공개 상한 30을 실측 하루(37)가
//      넘어서 안 올리면 외부 호출 전에 400/-400으로 거절된다.
//   2. #257 simulator 전환(`APP_GEO_KAKAO_BASE_URL` + dummy key) — 실제 Kakao면 요청 하나가 74콜이다.
//
// 실행 예(repo root 기준):
//   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app VUS=1 \
//     CONFIRM_AI_NOOP=yes CONFIRM_SIMULATOR=yes \
//     k6 run load-tests/timeline-draft/k6/geo-day.js

import { loadConfig } from './lib/config.js';
import { geoDayBody } from './lib/payload.js';
import { buildOptions, runSpike, setupSpike, summarizeSpike } from './lib/spike.js';

if (__ENV.CONFIRM_SIMULATOR !== 'yes') {
  throw new Error(
    'geo-day는 CONFIRM_SIMULATOR=yes가 필요합니다. '
    + '애플리케이션이 #257 simulator(APP_GEO_KAKAO_BASE_URL, dummy KAKAO_REST_API_KEY)로 전환됐는지 '
    + '먼저 확인하세요 — 실제 Kakao로는 요청 하나가 74콜입니다.'
  );
}

const config = loadConfig('geo-day', 'gd', 100);

export const options = buildOptions(config);

export function setup() {
  return setupSpike(config);
}

export default function (data) {
  runSpike(config, geoDayBody, data);
}

export function handleSummary(data) {
  return summarizeSpike(config, data);
}
