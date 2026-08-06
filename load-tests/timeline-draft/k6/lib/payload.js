// `POST /a/api/v1/timeline/drafts` 요청 body 생성기.
//
// 계약 근거: CreateDraftTaskRequest(recordDate/recordAt/recordTimeZone/timelineWindow/sourceItems)와
// SourceItemDto(itemType/rawId/startAt/endAt/payload). 시각 필드는 전부 offset 없는 LocalDateTime이다 —
// 'Z'나 offset을 붙이면 파싱이 실패한다.

import { clockTime } from './config.js';

const RECORD_TIME_ZONE = 'Asia/Seoul';

// 합성 좌표 격자의 원점. 실제 사용자 좌표를 쓰지 않기 위한 결정적 상수이며, #257 simulator는 좌표 값과
// 무관하게 고정 응답을 준다. 요청 하나 안에서 좌표가 서로 달라야 좌표 dedupe에 합쳐지지 않는다.
const LAT_ORIGIN = 37.0;
const LON_ORIGIN = 127.0;
const COORD_STEP = 0.0001;

/**
 * rawId — 클라 원본 데이터 식별자. 서버는 형식을 검증하지 않고 그대로 저장·echo하며 최대 36자다.
 * `(task_id, raw_id)`가 UNIQUE라 요청 하나 안에서만 유일하면 되지만, run·시나리오·단계·VU를 모두 넣어
 * 정리·검증 쿼리가 `LIKE 'k6-<runId>-<code><step>-%'` 하나로 대상을 정확히 집어낼 수 있게 한다.
 * 길이 상한은 config가 init에서 검증한다(여기서 잘라내면 요청 안 rawId가 충돌한다).
 */
function rawId(config, vu, index) {
  return `k6-${config.runId}-${config.scenarioCode}${config.stepIndex}`
    + `-${String(vu).padStart(5, '0')}-${String(index).padStart(2, '0')}`;
}

function envelope(config, sourceItems) {
  return {
    recordDate: config.recordDate,
    // recordAt은 recordDate와 날짜가 달라도 되는 독립 값이다(다음날 아침에 쓴 어제 일기).
    recordAt: `${config.recordDate}T09:12:34`,
    recordTimeZone: RECORD_TIME_ZONE,
    // 서버는 startTime < endTime만 검증하고 하루 길이·달력 경계는 재검증하지 않는다.
    timelineWindow: {
      startTime: `${config.recordDate}T00:00:00`,
      endTime: `${config.recordDate}T23:59:59`,
    },
    sourceItems,
  };
}

/**
 * calendar-core — 좌표가 없는 CALENDAR 1개. STAY/MOVEMENT가 아니면 지오코딩 대상 좌표를 수집하지 않으므로
 * Kakao 경로를 타지 않는다(SourceItemEnrichmentService의 2-pass 수집 규칙).
 */
export function calendarCoreBody(config, vu) {
  return envelope(config, [
    {
      itemType: 'CALENDAR',
      rawId: rawId(config, vu, 0),
      startAt: `${config.recordDate}T${clockTime(9, 0, 0)}`,
      endAt: `${config.recordDate}T${clockTime(10, 0, 0)}`,
      payload: {
        title: 'k6 load test calendar item',
        locationText: 'k6 synthetic location',
        description: 'issue #251 calendar-core scenario',
        allDay: false,
      },
    },
  ]);
}

/**
 * geo — STAY `count`개, 각각 서로 다른 합성 좌표. 좌표 1개당 정상 2콜(coord2address 1 + keyword 1)이다.
 *
 * 서버 상한은 `app.geo.max-unique-coordinates`(기본 30)이며 초과하면 외부 호출 전에 400으로 거절된다 —
 * count는 그 아래여야 한다. startAt은 필수이고 지오코딩 품질 판정이 시간순을 쓰므로 항목마다 한 시간씩 민다.
 */
export function geoStayBody(config, vu, count) {
  const items = [];
  for (let index = 0; index < count; index += 1) {
    // VU마다 좌표 블록을 나눠 요청 사이에도 좌표가 겹치지 않게 한다.
    const offset = (vu - 1) * count + index;
    items.push({
      itemType: 'STAY',
      rawId: rawId(config, vu, index),
      startAt: `${config.recordDate}T${clockTime(index, 0, 0)}`,
      endAt: `${config.recordDate}T${clockTime(index, 30, 0)}`,
      payload: {
        latitude: Number((LAT_ORIGIN + offset * COORD_STEP).toFixed(6)),
        longitude: Number((LON_ORIGIN + offset * COORD_STEP).toFixed(6)),
      },
    });
  }
  return envelope(config, items);
}
