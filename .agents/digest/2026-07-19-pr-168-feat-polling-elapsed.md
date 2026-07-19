---
schema_version: 1
status: merge-candidate
pr_number: 168
pr_url: https://github.com/soma17th-369/Laimory-server/pull/168
title: "feat: PROCESSING 폴링에 AI 작업 대기 경과 시간(elapsedSeconds) 추가"
base_branch: dev
head_branch: feat/polling-elapsed
implementation_head_sha: 864391e8d2127ce03854858f6b2a40ae6217eaa8
generated_at: 2026-07-19T13:23:12Z
linked_issues: [165]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #168 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: draft task가 `PROCESSING`인 동안 AI 작업 대기 경과 시간을 polling 응답 `elapsedSeconds`(완료된 초, 0 이상 int64)로 제공해 클라이언트가 대기 UX를 서버 기준으로 표시할 수 있게 한다.
- Acceptance criteria: 신규 PROCESSING polling에 non-negative `elapsedSeconds` 존재; SUCCESS/FAILED와 시각 없는 legacy PROCESSING에서는 필드 생략(500·값 위조 없음); PROCESSING/terminal TTL·guard·callback 불변식 유지; OpenAPI·knowledge 갱신.
- Out of scope: SUCCESS/FAILED 경과 시간 제공, terminal task에 시각 보존, callback 경로 시각 전달, POST 접수 기준 총시간, ETA/진행률, TTL 변경, Redis backfill, API 버전 추가.

## Change Summary

- Redis `TimelineDraftTask`에 PROCESSING 전용 `Instant processingStartedAt` 추가(UTC ISO-8601 문자열, `NON_NULL` — terminal/legacy JSON에 key 미노출). `processing` factory만 시각을 받고 `success`/`failed`는 null 고정.
- `TimelineDraftTaskService#createDraftTask`가 전처리(검증·dedupe·enrich·MySQL staging)를 마친 뒤 Redis PROCESSING 저장 직전에 기존 `Clock` bean으로 시각을 1회 캡처("AI dispatch 대기 시작" 경계 — POST 접수 시각 아님).
- `TimelineDraftTaskPollingService`가 PROCESSING branch에서만 경과 완료 초를 계산(legacy null → 필드 생략, 시계 역행/future timestamp → 0 clamp, long seconds 전용 — int cast/millis 곱셈 없음).
- 공개 계약: `DraftTaskStatusResponse.elapsedSeconds`(`Long`, `@JsonInclude(NON_NULL)`, `@Schema` int64·minimum 0) — additive 변경으로 terminal 응답 shape 불변. `TimelineApi` polling description 갱신.
- `TimelineCallbackService`는 무변경(terminal 전이가 시각을 폐기하므로 전달 불필요).
- knowledge 4개 문서 갱신(ubiquitous-language·invariants·timeline-draft·persistence).

## Plan Deviations

- 계획 §8은 `.agents/knowledge/codebase/interfaces/api.md` 갱신을 포함했으나 실제로는 갱신하지 않았다. 근거: 해당 문서는 필드 수준 계약을 의도적으로 나열하지 않으며("runtime OpenAPI가 field-level source", "수동 endpoint·field 목록보다 위 source가 우선") envelope·path·error 매핑이 바뀌지 않아 문서 의미가 변하지 않았다. 필드 계약은 DTO `@Schema`와 `TimelineApi` description이 소유한다.
- 그 외: No material deviation was observed within the evidence scope.

## Problems Encountered

No material problem was observed within the evidence scope.

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| plan review (Claude) | 축소 스코프(PROCESSING 전용·dispatch 대기 기준·terminal 미보존)가 이슈 #165 원문과 모순 — 정합 필요 | accepted | 사용자가 축소를 의도로 확정(방향 a). 구현 착수 전 이슈 #165 본문·체크리스트를 축소 계약으로 갱신해 PR-이슈 정합 확보. |
| PR reviewer (suhyun444, thread r3610606051) | polling description 첫 문장 "PROCESSING이면 status만"이 새 `elapsedSeconds` 계약과 모순 | accepted | 864391e에서 `@Operation` description과 `DraftTaskStatusResponse` 클래스 Javadoc 첫 문장을 "status와 elapsedSeconds"로 정합. 스레드 회신·resolve 완료. |
| plan (user-approved) | terminal signature(`markSuccess`/`markFailed`)에 시각 인자를 추가하지 않고 terminal task에 null 기록 | accepted | PROCESSING 전용 lifecycle을 기존 `recordAt`/`timelineWindow` 패턴과 정렬 — callback 경로 무변경으로 변경 표면 최소화. |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 경과 초 계산(완료 초·소수 버림·future 0 clamp·legacy null·long overflow 없음) | `TimelineDraftTaskPollingServiceTest` (12 tests) | passed | 43a3eab |
| PROCESSING 저장에 시각 전달·Clock 1회 캡처 경계·거절 경로 미캡처 | `TimelineDraftTaskServiceTest` (41 tests) | passed | 43a3eab |
| terminal 시각 폐기 + TTL 1h/24h 유지 | `TimelineTaskServiceTest` (4 tests) | passed | 43a3eab |
| Redis JSON 계약(ISO-8601 직렬화·round-trip·legacy 필드 부재 null·terminal key 생략) | `TimelineTaskStoreTest` (11 tests) | passed | 43a3eab |
| HTTP 응답 shape(PROCESSING 숫자 존재·legacy/terminal key 생략) | `TimelineControllerTest` (14 tests) | passed | 43a3eab |
| OpenAPI 스키마(int64·minimum 0·optional) | 신규 `DraftTaskStatusResponseSchemaTest` | passed | 43a3eab |
| 실 Redis Instant round-trip·terminal 미보존 | `TimelineTaskStoreIntegrationTest` (로컬 MySQL·Redis) | passed | 43a3eab |
| E2E: PROCESSING에 non-negative elapsedSeconds, SUCCESS에 key 부재 | `FakeAiDispatcherEndToEndIntegrationTest` (2 tests) | passed | 43a3eab |
| 전체 unit suite | `./gradlew test` | passed | 43a3eab |
| implementation head CI | GitHub Actions `build` check | passed | https://github.com/soma17th-369/Laimory-server/actions/runs/29688398346/job/88196668862 (864391e) |

## Remaining Risks

- 배포 게이트(코드 외): 기존 Android polling decoder의 unknown-field 관용성 확인 전 Server-first 배포 시 strict decoder면 기존 앱이 깨질 수 있다. Android는 `elapsedSeconds`를 optional로 파싱해야 하며 null→0 변환 금지(PR 본문 배포 메모).
- 실 AI 활성화 전 Redis PROCESSING JSON을 직접 읽는 AI decoder의 unknown-field 관용성 확인 필요(현재 noop/fake라 소비자 없음).
- 배포 직후 최대 약 1시간(PROCESSING TTL) legacy task 혼재 구간에서 클라이언트는 필드 부재를 "경과 시간 미상"으로 처리해야 한다.
- #164(client timeline window)가 같은 factory/service signature·fixture를 수정하므로 #164는 이 PR 머지 후 최신 dev에 rebase해야 한다.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- 스코프 축소 시 이슈 원문 체크리스트와의 정합을 구현 착수 전에 확정하는 절차(plan-review에서 실제로 blocker로 작동) — 계획 문서에 "이슈 정합" 항목을 두는 것을 검토할 만하다.
- PROCESSING 전용 필드 추가 패턴(record 마지막 nullable component + `NON_NULL` + factory 분리 + legacy 필드-부재 store 테스트)이 `dailyRecordId` 사례와 동일하게 재사용됨 — Redis task shape 확장 시 표준 절차 후보.
