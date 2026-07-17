---
schema_version: 1
status: merge-candidate
pr_number: 159
pr_url: https://github.com/soma17th-369/Laimory-server/pull/159
title: "feat: 타임라인 날짜 guard 도입과 SUCCESS task dailyRecordId 결과 식별 전환"
base_branch: dev
head_branch: feat/timeline-date-guard
implementation_head_sha: 6d12351390f62616f33da55950379181c84021ca
generated_at: 2026-07-17T15:30:00+09:00
linked_issues: [158]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #159 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 타임라인 Event 수정·메모·삭제 API(#158)의 전제 작업 — ① 날짜 단위 Redis guard로 동일
  (userId, recordDate)에 AI 작업 하나만 허용, ② SUCCESS task에 `dailyRecordId`를 저장하고 폴링을
  ID 조회로 전환해 "record 삭제 후 같은 날짜 재생성 시 과거 task가 새 기록을 반환"하는 오조회 차단.
- Acceptance criteria: 동일 날짜 두 번째 draft 409(`ERROR_1016`); guard 해제 경계 ①선처리 실패 즉시
  해제 ②PROCESSING 후 terminal 저장 실패 TTL 방치 ③terminal 성공 시 compare-release; PROCESSING/FAILED
  JSON에 `dailyRecordId` null 미노출(AI 직접 읽기 계약 보존); legacy SUCCESS task 폴링 `ERROR_0404`;
  기존 FakeAi E2E multi-append 회귀 green.
- Out of scope: Event 수정/메모 API(PR 2), 삭제+S3(PR 3), 인증(#108).

## Change Summary

- `RedisGateway`(구 `PrefixedRedis`)에 원자 연산 3개 추가: `setIfAbsent`(SET NX+TTL),
  `expireIfValueMatches`/`deleteIfValueMatches`(코드베이스 첫 Lua — 비교와 PEXPIRE/DEL 원자화).
- 날짜 guard 논리 키 `timeline:date-guard:{userId}:{recordDate}`, holder `task:{taskId}`, TTL 1h.
  draft 생성이 선점(실패 시 신설 `ERROR_1016` 409), PROCESSING 저장 성공 후 refresh가 **dispatch 허용
  게이트**(소유 미확인 시 dispatch 없이 `markFailed(ERROR_1009)`), terminal 전이 성공 직후 compare-release.
- `TimelineDraftTask`에 `@JsonInclude(NON_NULL) Long dailyRecordId` — 정상 finalize(`appendDailyTimeline`
  반환값)와 staging 부재 멱등 복구 두 경로 모두 저장. 폴링 SUCCESS는 이 ID로만 조회, ID 부재(legacy)·
  record 삭제·비소유는 `ERROR_0404`(신설 ExceptionType `DRAFT_RESULT_NOT_FOUND`, "task 없음" 1001과 구분).
- `DailyTimelineResponse.dailyRecordId` 추가(additive), 기존 `TimelineApi` OpenAPI 409/404 설명 갱신,
  메시지 3번들에 1016 추가.
- knowledge 갱신: persistence(Redis 키 표·원자 연산·게이트웨이 명), timeline-draft runtime(guard
  흐름·dispatch 게이트·폴링 전환) 외 rename 연쇄 문서 5건.
- 동작 변경: 동일 날짜 중복 draft 요청이 기존 "둘 다 통과(MVP 수용)"에서 409로 변경. legacy SUCCESS
  task(24h TTL)는 폴링 404.

## Plan Deviations

- 계획 대비 추가 1건: 리뷰 Blocker 반영으로 guard refresh가 "best-effort TTL 정렬"에서 "dispatch 허용
  게이트"로 승격됨(소유 미확인 시 dispatch 차단). 계획의 해제 경계 규칙 자체는 유지.
- 계획 외 부수 작업 2건(같은 브랜치): Lua 텍스트 블록 delimiter 정리(style), `PrefixedRedis`→
  `RedisGateway` rename(refactor — 사용자 지시, Gateway 패턴 명명).
- 그 외 `No material deviation was observed within the evidence scope.`

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| integrationTest 1차 실행 | `TimelineCallbackTokenIntegrationTest` 3건 실패(BusinessException) | confirmed | 고정 날짜 공유 클래스에서 terminal 미도달 테스트(wrongToken)의 guard가 잔존해 후속 draft가 1016 거절 — 두 통합 테스트 클래스 cleanup에 date-guard 키 삭제 추가(운영 동작 무관, 테스트 위생) | d2e2790 이후 실행 로그 → 수정 후 integrationTest BUILD SUCCESSFUL |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| PR reviewer (suhyun444, [Blocker]) | refresh false/예외에도 dispatch 진행 — 날짜당 하나 불변식 훼손 가능(전처리 1h 초과 후 타 작업 선점 시 이중 실행) | accepted | refresh 반환값이 원자적 소유권 판정인데 void로 폐기되고 있었음. true일 때만 dispatch, 미확인 시 markFailed(1009) 종결로 전환. 커밋 447b010, 스레드 resolved |
| 외부 plan 리뷰(구현 전 반영) | PROCESSING/FAILED JSON에 null 노출 방지 위해 `@JsonInclude(NON_NULL)` 필드 명시 필수(기본 inclusion ALWAYS) | accepted | TimelineTaskStoreTest가 PROCESSING JSON fragment를 단언하는 기존 테스트로 확인, 구현·테스트에 반영 |
| 외부 plan 리뷰(구현 전 반영) | `findByUserIdAndRecordDate`는 폴링에서만 제거, SAVED 검사·멱등 복구에 유지 | accepted | 실제 사용처 3곳 확인. UNIQUE(user_id, record_date) 제약도 동시 finalize 방어라 유지 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| guard 해제 경계 ①②③·1016·legacy 0404·직렬화 NON_NULL·dispatch 게이트 분기 | `./gradlew test` (단위) | passed | 6d12351 로컬 실행 |
| SET NX 배타성·Lua compare-refresh/release holder 존중(실 Redis 첫 Lua) | `./gradlew integrationTest` — `TimelineTaskStoreIntegrationTest.dateGuard_claimIsExclusive_andCompareOpsRespectHolder` | passed | 6d12351 로컬 실행 |
| 콜백 토큰 동시성·finalize E2E(실 MySQL+Redis) | `TimelineCallbackTokenIntegrationTest` | passed | 6d12351 로컬 실행 |
| terminal 후 같은 날짜 재-draft 허용(회귀) | `FakeAiDispatcherEndToEndIntegrationTest` multi-append | passed | 6d12351 로컬 실행 |
| 전체 빌드 | `./gradlew build` + GitHub Actions `build` check | passed | implementation head 6d12351 check SUCCESS |

## Remaining Risks

- 배포 게이트: 배포 전 in-flight PROCESSING task(1h)는 guard 없이 생성된 것 — 소진 확인 후 배포.
  기존 SUCCESS task(24h TTL)는 `dailyRecordId` 부재로 폴링 404(`ERROR_0404`) — 24h 수용 결정.
- Android 후속 미반영: `dailyRecordId` 필드, `ERROR_1016` 재시도 UX(서버 선반영 합의 사항).
- guard는 단일 Redis lease라 Redis 재시작 시 소실 가능 — 피해는 draft 중복(구 MVP 동작 수준)으로
  한정되며 record 중복은 DB UNIQUE(user_id, record_date)가 차단(설계 수용 사항).
- dev 실환경 관찰(guard 동작·1016 빈도)은 dev 머지·배포 후 수행 예정.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: integrationTest 1차 실행의 테스트 위생 실패
  3건(위 표) 외 not observed within the evidence scope

## Learning Candidates

- 고정 날짜를 공유하는 통합 테스트 클래스는 새 Redis 키(lease류)를 도입할 때 cleanup에 해당 키
  삭제를 함께 추가해야 한다 — 이번에 두 클래스에서 재발한 패턴(체크리스트 후보).
- 소유권/상태 판정을 반환하는 연산을 void 헬퍼로 감싸면 "확인하고도 행동하지 않는" 코드가 구조적으로
  고정된다 — 반환값이 판정인 연산은 게이트로 소비할 것(리뷰 Blocker의 일반화).
- 텍스트 블록 닫는 `"""`는 항상 제 줄에(PR 2·3에도 적용).
