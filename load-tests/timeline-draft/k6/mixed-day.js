// mixed-day — 실측 하루 분포(이동 12·체류 13·알림 41·일정 2 = 68 아이템)의 접수 경로 부하.
//
// calendar-core가 최소 사례(행 1개)라 커넥션 풀 사이징 근거로 부족하다는 검토에 따라, 요청 하나가
// 실제 하루치만큼(68행) INSERT하도록 만든 시나리오다. IDENTITY 전략이라 batch가 불가능해 행 수만큼
// 왕복이 나간다 — 커넥션 점유 시간의 아이템 수 스케일링이 관측 대상이다.
//
// 좌표는 없다(이동·체류는 크기 맞춘 대역으로 대체 — payload.js 주석 참고). 따라서 Kakao 경로를 타지
// 않고, simulator 없이 실행해도 안전하다.
//
// 실행 예(repo root 기준):
//   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app VUS=1 CONFIRM_AI_NOOP=yes \
//     k6 run load-tests/timeline-draft/k6/mixed-day.js

import { loadConfig } from './lib/config.js';
import { mixedDayBody } from './lib/payload.js';
import { buildOptions, runSpike, setupSpike, summarizeSpike } from './lib/spike.js';

const config = loadConfig('mixed-day', 'm', 300);

export const options = buildOptions(config);

export function setup() {
  return setupSpike(config);
}

export default function (data) {
  runSpike(config, mixedDayBody, data);
}

export function handleSummary(data) {
  return summarizeSpike(config, data);
}
