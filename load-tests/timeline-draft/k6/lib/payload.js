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
 * rawId — 클라 원본 데이터 식별자. **canonical lowercase UUID여야 한다** — 서버가
 * `RawIds.isCanonicalUuid`가 아닌 값을 저장·dispatch 전에 400으로 거절한다(#287, 임의 문자열에
 * 개인정보가 실리는 것을 막는 경계). 그래서 식별 정보를 UUID 자릿수 안에 hex로 인코딩한다:
 *
 *   <YYYYMMDD>-<seq4>-<scenario+step>-<vu4>-<index12>
 *
 * 앞 세 그룹(= `config.rawIdPrefix`)이 run·시나리오·단계를 고정하므로 정리·검증 쿼리는 여전히
 * `LIKE '<prefix>%'` 하나로 대상을 집는다. `(task_id, raw_id)` UNIQUE는 마지막 두 그룹이 보장한다.
 */
function rawId(config, vu, index) {
  return `${config.rawIdPrefix}`
    + `-${vu.toString(16).padStart(4, '0')}`
    + `-${index.toString(16).padStart(12, '0')}`;
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
 * 서버 상한은 `app.geo.max-unique-coordinates`(기본 100)이며 초과하면 외부 호출 전에 400으로 거절된다 —
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

/**
 * mixed-day — 실측 하루 분포(2026-07-31 실기록: 이동 12·체류 13·알림 41·일정 2 = 68개)의 DB 쓰기 재현.
 *
 * 이동·체류는 좌표가 필수라 그대로 보내면 지오코딩(실 Kakao) 경로를 탄다. 이 시나리오의 목적은
 * 커넥션 점유 시간의 아이템 수 스케일링이므로, 두 타입은 **enrich 후 저장될 payload와 비슷한 JSON
 * 크기의 NOTIFICATION 대역**으로 넣는다(체류 ~200B, 이동 ~400B). DB에 닿는 것은 행 수와 payload
 * 크기다 — item_type 문자열 차이는 쓰기 비용에 영향이 없다. 지오코딩 포함 실측은 simulator 단계 몫.
 */
const MIXED_DAY = { calendar: 2, notification: 41, stayStandin: 13, movementStandin: 12 };

const STAY_SIZED_TEXT = 'stay-standin '
  + '서울특별시 테스트구 시뮬레이터로 251 Laimory 테스트빌딩 — 체류 enrich payload(주소+장소 3개+구간)와 '
  + '유사한 크기를 맞추기 위한 채움 텍스트다. address places durationText 상당분.';
const MOVEMENT_SIZED_TEXT = 'movement-standin '
  + '출발 서울특별시 테스트구 시뮬레이터로 251 Laimory 테스트빌딩 인근 — 도착 서울특별시 테스트구 '
  + '가상이동로 17 Laimory 테스트카페 인근 — 이동 enrich payload(양끝 주소+장소 목록+수단+거리)와 유사한 '
  + '크기를 맞추기 위한 채움 텍스트다. start end address places transports distanceMeters 상당분을 담아 '
  + '대략 사백 바이트 수준의 JSON이 되도록 길이를 조정했다. 실제 알림 본문이 아니라 부하 재현용 합성값이다.';

export function mixedDayBody(config, vu) {
  const items = [];
  let index = 0;
  const push = (payload) => {
    const hour = index % 24;
    const minute = (index * 7) % 60;
    items.push({
      itemType: 'CALENDAR' === payload.__t ? 'CALENDAR' : 'NOTIFICATION',
      rawId: rawId(config, vu, index),
      startAt: `${config.recordDate}T${clockTime(hour, minute, 0)}`,
      endAt: null,
      payload: payload.__t === 'CALENDAR'
        ? { title: payload.title, description: payload.description, allDay: false }
        : { appName: payload.appName, title: payload.title, text: payload.text },
    });
    index += 1;
  };

  for (let i = 0; i < MIXED_DAY.calendar; i += 1) {
    push({ __t: 'CALENDAR', title: `k6 mixed 일정 ${i + 1}`, description: 'issue #251 mixed-day scenario' });
  }
  for (let i = 0; i < MIXED_DAY.notification; i += 1) {
    push({ __t: 'NOTIFICATION', appName: '카카오톡', title: `k6 mixed 알림 ${i + 1}`,
      text: '부하 테스트용 합성 알림 본문 — 실제 알림 평균 크기 근사치의 텍스트.' });
  }
  for (let i = 0; i < MIXED_DAY.stayStandin; i += 1) {
    push({ __t: 'NOTIFICATION', appName: 'stay-standin', title: `k6 체류 대역 ${i + 1}`, text: STAY_SIZED_TEXT });
  }
  for (let i = 0; i < MIXED_DAY.movementStandin; i += 1) {
    push({ __t: 'NOTIFICATION', appName: 'movement-standin', title: `k6 이동 대역 ${i + 1}`, text: MOVEMENT_SIZED_TEXT });
  }
  return envelope(config, items);
}

/**
 * geo-day — mixed-day와 같은 실측 하루 분포에서 체류·이동을 **실제 STAY/MOVEMENT(좌표 포함)**로 보낸다.
 *
 * 요청당 고유 좌표 37개(STAY 13 + MOVEMENT 양끝 24) → 정상 시 Kakao 74콜. 공개 상한
 * `app.geo.max-unique-coordinates`(기본 100) 아래라 override 없이 실행된다.
 *
 * ⚠️ 반드시 #257 simulator로 전환된 상태에서만 실행한다 — 실제 Kakao면 요청 하나가 74콜이다.
 */
// geo-day 분포는 env로 바꿀 수 있다(기본 = 실측 2026-07-31). 예: GEO_STAY_COUNT=7 GEO_MOVEMENT_COUNT=6
const GEO_DAY = {
  calendar: Number(__ENV.GEO_CALENDAR_COUNT || MIXED_DAY.calendar),
  notification: Number(__ENV.GEO_NOTIFICATION_COUNT || MIXED_DAY.notification),
  stay: Number(__ENV.GEO_STAY_COUNT || MIXED_DAY.stayStandin),
  movement: Number(__ENV.GEO_MOVEMENT_COUNT || MIXED_DAY.movementStandin),
};

export function geoDayBody(config, vu) {
  const COORDS_PER_REQUEST = GEO_DAY.stay + GEO_DAY.movement * 2;
  const base = (vu - 1) * COORDS_PER_REQUEST;
  const coord = (i) => ({
    latitude: Number((LAT_ORIGIN + (base + i) * COORD_STEP).toFixed(6)),
    longitude: Number((LON_ORIGIN + (base + i) * COORD_STEP).toFixed(6)),
  });

  const items = [];
  let index = 0;
  const startAt = () => `${config.recordDate}T${clockTime(index % 24, (index * 7) % 60, 0)}`;

  for (let i = 0; i < GEO_DAY.calendar; i += 1) {
    items.push({ itemType: 'CALENDAR', rawId: rawId(config, vu, index), startAt: startAt(), endAt: null,
      payload: { title: `k6 geo-day 일정 ${i + 1}`, description: 'issue #251 geo-day scenario', allDay: false } });
    index += 1;
  }
  for (let i = 0; i < GEO_DAY.notification; i += 1) {
    items.push({ itemType: 'NOTIFICATION', rawId: rawId(config, vu, index), startAt: startAt(), endAt: null,
      payload: { appName: '카카오톡', title: `k6 geo-day 알림 ${i + 1}`,
        text: '부하 테스트용 합성 알림 본문 — 실제 알림 평균 크기 근사치의 텍스트.' } });
    index += 1;
  }
  for (let i = 0; i < GEO_DAY.stay; i += 1) {
    const c = coord(i);
    items.push({ itemType: 'STAY', rawId: rawId(config, vu, index), startAt: startAt(),
      endAt: `${config.recordDate}T${clockTime(index % 24, ((index * 7) + 30) % 60, 0)}`,
      payload: { latitude: c.latitude, longitude: c.longitude } });
    index += 1;
  }
  for (let i = 0; i < GEO_DAY.movement; i += 1) {
    const s = coord(GEO_DAY.stay + i * 2);
    const e = coord(GEO_DAY.stay + i * 2 + 1);
    items.push({ itemType: 'MOVEMENT', rawId: rawId(config, vu, index), startAt: startAt(),
      endAt: `${config.recordDate}T${clockTime(index % 24, ((index * 7) + 20) % 60, 0)}`,
      payload: { start: { latitude: s.latitude, longitude: s.longitude },
                 end: { latitude: e.latitude, longitude: e.longitude },
                 transports: i % 3 === 0 ? 'IN_VEHICLE' : 'WALKING', distanceMeters: 800 + i * 120 } });
    index += 1;
  }
  return envelope(config, items);
}
