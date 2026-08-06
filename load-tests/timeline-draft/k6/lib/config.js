// #251 부하 테스트 공통 설정 — 세 시나리오(calendar-core, geo-1-stay, geo-18-stay)가 같은 값을 읽는다.
// 모든 값은 환경변수로만 들어온다(저장소에 실행 대상·secret을 고정하지 않는다).

/** 필수 환경변수 — 없으면 init 단계에서 즉시 실패한다(부분 실행 방지). */
export function requiredEnv(name) {
  const value = __ENV[name];
  if (value === undefined || value === '') {
    throw new Error(`환경변수 ${name}이(가) 필요합니다.`);
  }
  return value;
}

export function intEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number.parseInt(raw, 10);
  if (!Number.isInteger(value)) {
    throw new Error(`환경변수 ${name}은(는) 정수여야 합니다: ${raw}`);
  }
  return value;
}

export function floatEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') {
    return fallback;
  }
  const value = Number.parseFloat(raw);
  if (!Number.isFinite(value)) {
    throw new Error(`환경변수 ${name}은(는) 숫자여야 합니다: ${raw}`);
  }
  return value;
}

/**
 * 시나리오 공통 설정을 만든다. `scenario`·`scenarioCode`·`dateOffsetDays`는 스크립트가 소유하는 상수다.
 *
 * `dateOffsetDays`는 시나리오마다 다른 recordDate 대역을 준다 — `daily_records`가
 * `(user_id, record_date)` UNIQUE라 같은 날짜를 재사용하면 두 번째 run이 기존 DRAFT row를 UPDATE하게 되어
 * 단계 사이 작업 성격이 달라진다. STEP_INDEX가 사다리 단계마다 하루씩 더 민다.
 *
 * `scenarioCode`는 rawId에 들어가 정리·검증 쿼리가 한 RUN_ID 안에서 시나리오와 단계를 구분하게 한다.
 * 없으면 `04-verify-run.sql`이 core와 geo의 행을 한 덩어리로 세어 "사용자당 task 1회"를 검증할 수 없다.
 */
export function loadConfig(scenario, scenarioCode, dateOffsetDays) {
  const vus = intEnv('VUS', 0);
  if (vus <= 0) {
    throw new Error('환경변수 VUS(양의 정수)가 필요합니다.');
  }

  const stepIndex = intEnv('STEP_INDEX', 0);
  if (stepIndex < 0) {
    throw new Error('STEP_INDEX는 0 이상이어야 합니다.');
  }

  // 실제 사용자 기록과 겹치지 않는 합성 날짜 대역이다. 실 데이터 날짜를 쓰지 않는다.
  const recordDateBase = __ENV.RECORD_DATE_BASE || '2031-01-01';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(recordDateBase)) {
    throw new Error(`RECORD_DATE_BASE는 YYYY-MM-DD여야 합니다: ${recordDateBase}`);
  }

  const startDelayMs = intEnv('START_DELAY_MS', 5000);
  if (startDelayMs < 1000) {
    throw new Error('START_DELAY_MS는 1000 이상이어야 합니다(VU가 barrier에 도달할 시간).');
  }
  const requestBudgetMs = intEnv('REQUEST_BUDGET_MS', 60000);

  const baseUrl = requiredEnv('BASE_URL').replace(/\/+$/, '');
  requireAiNoopConfirmation(baseUrl);

  const runId = requiredEnv('RUN_ID');
  // rawId는 최대 36자다(서버 컬럼 계약). 잘라내면 요청 안에서 rawId가 충돌해 dedupe로 item이 사라지므로
  // 자르지 않고 init에서 실패시킨다.
  const rawIdLength = `k6-${runId}-${scenarioCode}${stepIndex}-00000-00`.length;
  if (rawIdLength > 36) {
    throw new Error(
      `RUN_ID가 너무 깁니다: rawId가 ${rawIdLength}자로 상한 36자를 넘습니다. `
      + `RUN_ID를 ${runId.length - (rawIdLength - 36)}자 이하로 줄이세요.`
    );
  }

  return {
    scenario,
    scenarioCode,
    runId,
    baseUrl,
    vus,
    stepIndex,
    recordDate: addDays(recordDateBase, dateOffsetDays + stepIndex),
    startDelayMs,
    requestBudgetMs,
    artifactDir: (__ENV.ARTIFACT_DIR || 'load-tests/timeline-draft/.artifacts').replace(/\/+$/, ''),
    // 아래 세 값이 사다리의 중단 gate다. 기본값은 SLO가 아니라 폭주를 멈추는 안전 상한이다 —
    // VU 1 calibration 결과를 보고 시나리오마다 조정한다.
    maxErrorRate: floatEnv('MAX_ERROR_RATE', 0.01),
    maxP95Ms: intEnv('MAX_P95_MS', 3000),
    maxStartWindowMs: intEnv('MAX_START_WINDOW_MS', 1000),
  };
}

/**
 * 원격 대상에는 `CONFIRM_AI_NOOP=yes` 없이 발사할 수 없게 막는다.
 *
 * draft 생성은 시나리오와 무관하게 매 요청 AI dispatch를 부른다. 대상이 `noop`이 아니면 1,000건이
 * 그대로 실제 AI로 전파되므로, 이것은 geo 전용 가드(CONFIRM_SIMULATOR)와 별개로 항상 필요하다.
 * localhost는 예외로 둬 로컬 검증 반복을 막지 않는다.
 */
function requireAiNoopConfirmation(baseUrl) {
  const host = baseUrl.replace(/^[a-zA-Z]+:\/\//, '').split('/')[0].split(':')[0];
  const isLocal = host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
  if (isLocal || __ENV.CONFIRM_AI_NOOP === 'yes') {
    return;
  }
  throw new Error(
    `원격 대상(${host})에는 CONFIRM_AI_NOOP=yes가 필요합니다. `
    + '대상의 APP_AI_MODE가 noop인지 먼저 확인하세요 — noop이 아니면 요청 수만큼 실제 AI로 '
    + 'dispatch가 전파됩니다.'
  );
}

/** `YYYY-MM-DD`에 days를 더한다(UTC 기준 계산 — 로컬 타임존과 무관하게 결정적). */
export function addDays(isoDate, days) {
  const base = Date.parse(`${isoDate}T00:00:00Z`);
  if (Number.isNaN(base)) {
    throw new Error(`날짜를 해석할 수 없습니다: ${isoDate}`);
  }
  return new Date(base + days * 86400000).toISOString().slice(0, 10);
}

/** `HH:MM:SS` 벽시계 문자열 — 서버는 offset 없는 LocalDateTime만 받는다. */
export function clockTime(hour, minute, second) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(hour)}:${pad(minute)}:${pad(second)}`;
}
