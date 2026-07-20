---
schema_version: 1
status: merge-candidate
pr_number: 171
pr_url: https://github.com/soma17th-369/Laimory-server/pull/171
title: "feat: Timeline Event 타입 구조 도입 (#166)"
base_branch: dev
head_branch: feat/timeline-event-type
implementation_head_sha: bba851c8ec52c2f6e4b0dd57a613a8ccc14c6578
generated_at: 2026-07-20T20:35:00+09:00
linked_issues: [166]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #171 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: Timeline Event에 `ItemType`과 독립된 이벤트 수준 분류(`eventType`)를 도입하고, AI staging → assembler/validator → final Event → 모든 Event 응답까지 한 값이 유실·재해석 없이 흐르는 계약과 저장 구조를 만든다.
- Acceptance criteria: 13개 literal enum(`UNKNOWN` fallback 포함) 정의, 두 테이블 `event_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'`, staging raw String → assembler 단일 변환 경계(실패 시 `ERROR_1011` FAILED), 응답 3면(폴링·PATCH·memo) 노출, 상세 PATCH optional `eventType`(누락=유지, 명시적 null·미지원=400), 구버전 4키 요청·구버전 writer 호환 유지.
- Out of scope: AI 분류 경계·우선순위(프롬프트 설계), 타입 기반 조회/필터/인덱스, 기존 Event 소급 분류, callback body 변경, Android 구현, prod 배포.

## Change Summary

- `TimelineEventType` enum 신설(13 literal). final entity `TimelineEvent.eventType`은 `@Enumerated(STRING)` non-null, staging `TimelineDraftEventSuggestion.eventType`은 외부 AI writer 소유라 raw `String` 매핑(D1-A) — 미지원 DB literal의 hydration 예외가 콜백 FAILED 경계를 우회하는 것을 차단.
- `TimelineEventSuggestionAssembler`가 raw literal → enum 변환의 단일 서버 경계. null/blank/미지원은 IAE → 콜백 `ERROR_1011` FAILED, 예외 메시지에 raw 값 비-echo.
- finalize(`DailyTimelineService`)는 suggestion 타입을 재추론 없이 final Event로 복사. fake AI는 `UNKNOWN` 명시 기록.
- 공용 `TimelineEventResponse.eventType` 추가(additive) — draft SUCCESS 폴링, Event 상세 PATCH, memo PUT 응답에 동일 노출.
- 상세 PATCH에 optional `eventType` 키(D3.1-A): 기존 4키는 계속 필수, 키 누락은 현재 값 유지, 명시적 null·미지원 literal은 역직렬화 400. `KeyPresenceDeserializer`가 누락/명시적 null을 구분.
- `schema.sql` 두 테이블에 `event_type VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'` — default는 기존 행 backfill·구버전 writer 컬럼 생략·rollback 호환용.
- knowledge 5개 문서 갱신(ubiquitous-language, invariants, ai-contract, timeline-draft, persistence). persistence.md에 "dev 스키마 변경 DDL은 머지 전 적용(dev push=자동 배포 트리거)" 제약 명시.

## Plan Deviations

No material deviation was observed within the evidence scope. 계획서(`.agents/plans/166-timeline-event-type.md`)의 D1-A·D2·D3·D3.1 확정안대로 구현했다. PR 본문의 운영 체크리스트만 리뷰 반영으로 "머지 후 DDL"에서 "머지 전 dev DDL"로 정정했다(계획서 6.2절의 "배포 전" 원칙 자체는 불변 — dev에서는 머지가 곧 배포 트리거라는 구체화).

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| PR 본문 작성 | dev DDL을 "머지 후 운영 작업"으로 기재 — dev는 merge push가 `deploy.yml` 자동 배포를 트리거하므로 그 순서로는 새 컨테이너가 `ddl-auto=validate` 기동 실패로 dev 다운 | confirmed | PR 본문을 "머지 전 dev DDL 적용"으로 재구성, persistence.md에 제약 명시(bba851c), 사용자가 dev-mysql에 additive ALTER 2건 적용 완료 확인 | 리뷰 스레드 discussion_r3613807846, `.github/workflows/deploy.yml` on.push.branches=[dev] |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| PR reviewer (suhyun444) | dev 머지=자동 배포이므로 DDL 순서를 머지 전으로 정정하라 | accepted | `deploy.yml`이 dev push에서 구 컨테이너 중단 후 새 컨테이너 기동을 확인 — 미적용 머지는 validate 기동 실패 |
| PR reviewer (suhyun444) | 기존 DB 폐기 후 `schema.sql` 재생성(destructive reset)으로 문서·절차 전환 | rejected | 채팅으로 사용자 재확인: prod는 이번 배포 범위 아님, dev도 additive ALTER 2줄이 재생성(데이터 소실·S3 orphan·문서 재작성)보다 간단 — 계획서 D2·6.2절의 additive+호환 유지 정책 유지 |
| plan review (Claude) | 계획 Ready 판정 — staging raw String 근거(콜백 try 밖 staging 조회) 등 코드 대조 검증 | accepted | `TimelineCallbackService` 129행 조회가 try(139행) 밖임을 확인, D1-A 채택 근거 성립 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 전체 unit/slice 테스트 | `./gradlew test` | passed (523 tests, 0 failures) | 89255f1 |
| staging literal 전수 변환·미지원 거부·raw 비-echo | `TimelineEventSuggestionAssemblerTest` (`@EnumSource` 13종, null/blank/오타/소문자/trailing-space) | passed | 89255f1 |
| 잘못된 staging 타입 → `ERROR_1011` FAILED, finalize 미실행 | `TimelineCallbackServiceTest` | passed | 89255f1 |
| PATCH eventType 누락=유지·명시적 null/미지원=400·구버전 4키 성공 | `TimelineEventEditServiceTest`, `TimelineRecordControllerTest` | passed | 89255f1 |
| 폴링·수정·memo 응답 `eventType` 직렬화 | `TimelineControllerTest`, `TimelineRecordControllerTest` | passed | 89255f1 |
| MySQL 왕복·구버전 INSERT(컬럼 생략)→UNKNOWN 로드 | `./gradlew integrationTest` (33 tests, 로컬 MySQL에 additive DDL 선적용) | passed | 89255f1 |
| E2E staging→콜백→finalize→폴링 `eventType=UNKNOWN` 유지 | `FakeAiDispatcherEndToEndIntegrationTest` | passed | 89255f1 |
| 구현 head CI | GitHub Actions `build` | passed (SUCCESS) | bba851c / actions/runs/29738262584 |
| dev-mysql additive DDL 적용 | 사용자 수동 실행(mysql CLI) — 두 ALTER 성공, staging 빈 테이블 확인 | passed | 사용자 제공 세션 출력 (2026-07-20) |

## Remaining Risks

- 실제 AI writer(별도 저장소)가 아직 `event_type`을 INSERT하지 않는다 — 그동안 staging은 DB default `UNKNOWN`으로 채워져 동작하며, 활성화는 "Server 배포 → AI writer" 순서를 지켜야 한다(먼저 새 literal을 쓰면 해당 task FAILED).
- prod DB에는 컬럼 미적용 — prod 배포 전 같은 additive DDL 선행 필수.
- 새 literal 추가 시 Server enum 배포가 AI writer 활성화에 선행해야 한다는 운영 계약이 코드로 강제되지 않는다(ai-contract.md에 문서화만 됨).

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: 테스트 컴파일 1회 실패(시그니처 미갱신 호출부 5건) 후 즉시 수정 — 이후 전체 통과. 그 외 not observed within the evidence scope.

## Learning Candidates

- dev 대상 스키마 변경 PR은 "머지=배포 트리거"라 live DDL을 머지 전에 적용해야 한다 — persistence.md에 규칙로 반영됨(bba851c). 이후 스키마 PR 체크리스트에 재사용 가치.
- 외부 writer 소유 staging 컬럼은 JPA enum 매핑 대신 raw String + 애플리케이션 경계 변환(D1-A 패턴)이 콜백 오류 경계를 보존한다 — 유사한 외부-writer 컬럼 추가 시 재사용 가치.
