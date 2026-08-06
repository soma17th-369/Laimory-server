// calendar-core — JWT, WAS, MySQL, Redis와 AI noop 접수 경로의 용량만 측정한다.
//
// CALENDAR item에는 좌표가 없어 지오코딩 대상 좌표 수집이 0이고, 서버는 lookupAll 자체를 생략한다 —
// 그래서 이 시나리오는 Kakao(실제든 simulator든)를 전혀 호출하지 않는다. geo 시나리오의 기준선이다.
//
// 실행 예(repo root 기준):
//   RUN_ID=20260806-01 BASE_URL=https://dev.laimory.app VUS=1 CONFIRM_AI_NOOP=yes \
//     k6 run load-tests/timeline-draft/k6/calendar-core.js

import { loadConfig } from './lib/config.js';
import { calendarCoreBody } from './lib/payload.js';
import { buildOptions, runSpike, setupSpike, summarizeSpike } from './lib/spike.js';

const config = loadConfig('calendar-core', 'c', 0);

export const options = buildOptions(config);

export function setup() {
  return setupSpike(config);
}

export default function (data) {
  runSpike(config, calendarCoreBody, data);
}

export function handleSummary(data) {
  return summarizeSpike(config, data);
}
