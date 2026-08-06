// one-shot spike 공용 실행부 — "서로 다른 사용자 N명이 1초 안에 각 1회 요청"을 세 시나리오가 공유한다.
//
// 측정 설계: VU마다 iteration 1회(`per-vu-iterations`)이고, 모든 VU가 setup이 정한 같은 절대 시각(barrier)까지
// 기다렸다가 동시에 발사한다. barrier가 없으면 VU 기동 순서가 그대로 요청 분산이 되어 "1초 스파이크"가 아니라
// 완만한 ramp가 된다. `request_start_offset_ms`는 barrier 대비 실제 발사 지연이며, 이 값의 max가 곧
// request start window다 — 1초를 넘으면 부하 생성기 실패로 보고 run을 무효 처리한다.
//
// threshold는 전부 custom metric에 건다. setup()의 preflight 요청이 http_req_* 내장 metric에 섞이기 때문에
// 내장 metric에 gate를 걸면 측정 대상이 오염된다.

import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { users } from './tokens.js';

const startOffsetMs = new Trend('request_start_offset_ms');
const draftReqDuration = new Trend('draft_req_duration', true);
const draftAccepted = new Rate('draft_accepted');
const draftRequests = new Counter('draft_requests');

// 상태 클래스별 counter. tag를 단 단일 counter는 threshold에 쓰이지 않으면 summary에 sub-metric으로
// 나오지 않아 실패 분류를 읽을 수 없다 — 이름을 나눠 summary에 그대로 드러낸다.
// 정확한 API error code(-1014/-1015/-2001 등)는 WAS access log가 요청마다 남긴다.
const draftStatus = {
  '2xx': new Counter('draft_status_2xx'),
  '4xx': new Counter('draft_status_4xx'),
  '5xx': new Counter('draft_status_5xx'),
  other: new Counter('draft_status_other'),
};

function statusClass(status) {
  if (status >= 200 && status < 300) {
    return '2xx';
  }
  if (status >= 400 && status < 500) {
    return '4xx';
  }
  if (status >= 500 && status < 600) {
    return '5xx';
  }
  // status 0은 연결 실패·timeout처럼 응답을 받지 못한 경우다.
  return 'other';
}

export function buildOptions(config) {
  return {
    scenarios: {
      spike: {
        executor: 'per-vu-iterations',
        vus: config.vus,
        iterations: 1,
        maxDuration: `${config.startDelayMs + config.requestBudgetMs}ms`,
        gracefulStop: '30s',
      },
    },
    thresholds: {
      draft_accepted: [`rate>=${1 - config.maxErrorRate}`],
      draft_req_duration: [`p(95)<${config.maxP95Ms}`],
      request_start_offset_ms: [`max<${config.maxStartWindowMs}`],
      // 모든 VU가 실제로 발사했는지 — 미달이면 부하 생성기가 부하를 만들지 못한 것이라 결과가 무효다.
      draft_requests: [`count==${config.vus}`],
    },
    summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max'],
  };
}

/**
 * 잘못된 BASE_URL·부족한 token·죽은 대상에 1,000 VU를 쏘지 않도록 먼저 확인하고, 모든 VU가 공유할
 * barrier 시각을 정한다. 여기서 던지면 부하가 시작되지 않는다.
 */
export function setupSpike(config) {
  if (users.length < config.vus) {
    throw new Error(`token 수(${users.length})가 VUS(${config.vus})보다 적습니다.`);
  }
  const probe = http.get(`${config.baseUrl}/status`, { tags: { name: 'preflight-status' } });
  if (probe.status !== 200) {
    throw new Error(`대상이 준비되지 않았습니다: GET ${config.baseUrl}/status → ${probe.status}`);
  }
  return { scheduledStartMs: Date.now() + config.startDelayMs };
}

/**
 * VU 하나의 전체 iteration. `buildBody(config, vu)`가 시나리오별 요청 body를 만든다.
 * VU와 사용자는 1:1로 고정한다 — `idInTest`는 테스트 전체에서 유일한 1-based VU 번호다.
 */
export function runSpike(config, buildBody, data) {
  const vu = exec.vu.idInTest;
  const user = users[vu - 1];

  const waitMs = data.scheduledStartMs - Date.now();
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }
  startOffsetMs.add(Date.now() - data.scheduledStartMs);

  const response = http.post(
    `${config.baseUrl}/a/api/v1/timeline/drafts`,
    JSON.stringify(buildBody(config, vu)),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${user.token}`,
      },
      tags: { name: 'create-draft' },
    }
  );

  draftRequests.add(1);
  draftReqDuration.add(response.timings.duration);

  // 성공 계약: 202 Accepted + envelope header.code 0 + body.taskId. 앱은 실패도 200/202가 아닌
  // 상태코드로 내보내지만, 코드까지 확인해야 "접수됐다"를 증명할 수 있다.
  let code = null;
  let hasTaskId = false;
  try {
    const parsed = response.json();
    if (parsed && parsed.header) {
      code = parsed.header.code;
    }
    hasTaskId = Boolean(parsed && parsed.body && parsed.body.taskId);
  } catch (error) {
    code = null;
  }

  const accepted = response.status === 202 && code === 0 && hasTaskId;
  draftAccepted.add(accepted);
  draftStatus[statusClass(response.status)].add(1);
}

/** k6 summary + run 메타데이터를 `.artifacts/`에 남기고, 콘솔에는 판정에 필요한 값만 압축해 출력한다. */
export function summarizeSpike(config, data) {
  const path = `${config.artifactDir}/${config.runId}-${config.scenario}-${config.vus}vu-summary.json`;
  const output = {};
  output[path] = JSON.stringify(
    {
      meta: {
        runId: config.runId,
        scenario: config.scenario,
        vus: config.vus,
        stepIndex: config.stepIndex,
        recordDate: config.recordDate,
        baseUrl: config.baseUrl,
        gates: {
          maxErrorRate: config.maxErrorRate,
          maxP95Ms: config.maxP95Ms,
          maxStartWindowMs: config.maxStartWindowMs,
        },
      },
      metrics: data.metrics,
    },
    null,
    2
  );
  output.stdout = renderText(config, data);
  return output;
}

function metricValue(data, name, key) {
  const metric = data.metrics[name];
  if (!metric || !metric.values || metric.values[key] === undefined) {
    return null;
  }
  return metric.values[key];
}

function format(value, digits) {
  return value === null ? 'n/a' : value.toFixed(digits);
}

/** counter는 한 번도 기록되지 않으면 metric 자체가 없다 — 그 경우는 0으로 읽는다. */
function countOf(data, name) {
  const value = metricValue(data, name, 'count');
  return value === null ? 0 : value;
}

function renderText(config, data) {
  const acceptedRate = metricValue(data, 'draft_accepted', 'rate');
  const lines = [];
  lines.push('');
  lines.push(`  run          : ${config.runId} / ${config.scenario} / ${config.vus} VU`);
  lines.push(`  target       : ${config.baseUrl}`);
  lines.push(`  recordDate   : ${config.recordDate} (step ${config.stepIndex})`);
  lines.push('');
  lines.push(`  requests     : ${format(metricValue(data, 'draft_requests', 'count'), 0)} / ${config.vus}`);
  lines.push(`  accepted     : ${format(acceptedRate === null ? null : acceptedRate * 100, 2)} %`);
  lines.push(`  status       : 2xx ${countOf(data, 'draft_status_2xx')}`
    + `  4xx ${countOf(data, 'draft_status_4xx')}`
    + `  5xx ${countOf(data, 'draft_status_5xx')}`
    + `  no-response ${countOf(data, 'draft_status_other')}`);
  lines.push(`  duration p95 : ${format(metricValue(data, 'draft_req_duration', 'p(95)'), 1)} ms`
    + `   p99 ${format(metricValue(data, 'draft_req_duration', 'p(99)'), 1)} ms`
    + `   med ${format(metricValue(data, 'draft_req_duration', 'med'), 1)} ms`);
  lines.push(`  start window : ${format(metricValue(data, 'request_start_offset_ms', 'max'), 1)} ms`
    + ` (gate ${config.maxStartWindowMs} ms)`);
  lines.push('');

  const failed = [];
  Object.keys(data.metrics).forEach(function (name) {
    const thresholds = data.metrics[name].thresholds;
    if (!thresholds) {
      return;
    }
    Object.keys(thresholds).forEach(function (expression) {
      if (thresholds[expression].ok === false) {
        failed.push(`${name} ${expression}`);
      }
    });
  });

  if (failed.length === 0) {
    lines.push('  gate         : PASS — 다음 단계로 진행할 수 있다.');
  } else {
    lines.push('  gate         : FAIL — 사다리를 여기서 멈춘다.');
    failed.forEach(function (item) {
      lines.push(`                 - ${item}`);
    });
  }
  lines.push('');
  return lines.join('\n');
}
