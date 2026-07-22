---
schema_version: 1
status: merge-candidate
pr_number: 182
pr_url: https://github.com/soma17th-369/Laimory-server/pull/182
title: "feat: Timeline Item N:M 및 AI direct-write 재설계 서버 cutover"
base_branch: dev
head_branch: feat/timeline-item-nm-direct-write
implementation_head_sha: ecdf1da373859dc107d8fa582eb262cb9a29a940
generated_at: 2026-07-22
linked_issues: [179, 180, 181]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #182 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: suggestion staging 폐기에서 출발한 Timeline 재설계의 서버 측 cutover — Event↔Item을 N:M(junction)으로 바꾸고, AI가 final Event/Item/junction을 direct-write하며 서버 callback은 상태만 전이하도록 전환한다(이슈 #180, Epic #179).
- Acceptance criteria: create→AI direct-write commit→callback→poll→조회/삭제 E2E가 새 계약으로 동작하고, 기존 Android 공개 API 요청/응답 shape가 불변이며, `./gradlew test`·`integrationTest`가 통과한다.
- Out of scope: 실 AI writer(Laimory-AI 저장소) 구현, AI DB 계정 GRANT·private HTTP 인증(이슈 #181), DRAFT→SAVED 신규 기능.

## Change Summary

- 데이터 모델: `timeline_event_items` junction으로 Event↔Item N:M 전환(Item은 record/event FK 없는 독립 행), suggestion 테이블·참조 제거, staging `(task_id, raw_id)` UNIQUE, final 테이블 감사 컬럼 DB default. junction 키는 `@EmbeddedId`.
- draft POST: DailyRecord 선생성(find-or-create+SAVED 재확인+recordAt/timezone 갱신)+source 저장을 한 트랜잭션으로 커밋 후 AI dispatch. Redis task shape 축소(record 메타데이터 제거, dailyRecordId 전 상태 보존).
- AI dispatch: `POST /v1/timeline`(taskId·원문 callbackToken·dailyRecordId·offset window), `app.ai.mode=http` 신설. 실패를 미접수 확정(4xx→FAILED)과 UNKNOWN(read timeout·5xx·계약 불일치→PROCESSING·guard 유지)으로 분리.
- callback: 서버 finalize 제거(상태 전이 전용), token-uses 카운터 삭제로 유효한 재콜백을 terminal no-op 200 멱등 흡수.
- 조회/삭제: junction 경유 조립(응답 shape 유지), exclusive/shared Item 구분·exclusive PHOTO만 S3 삭제·orphan Item 명시 삭제.
- fake mode: direct-write 계약으로 전환(`FakeAiTimelineAppendService` + commit-then-callback).
- `raw_id`(source·final)를 `utf8mb4_bin` collation으로 전환해 DB 비교를 Java dedupe와 일치(`abc`≠`ABC`).

## Plan Deviations

- 계획서(§3.4)의 `@IdClass` 대신 `@EmbeddedId`로 junction 복합키를 매핑했다. 리뷰/논의에서 이 코드베이스의 plain Long FK 컨벤션과 널리 인용되는 권고를 반영한 결정이며, DB 컬럼·PK는 동일해 스키마 변경은 없었다.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 리뷰 반영 | dispatch 예외를 전부 FAILED로 확정하면 read timeout 등 UNKNOWN에서 AI가 실제 커밋한 결과와 polling FAILED가 어긋나고 이후 AI write가 새 draft/삭제와 겹칠 수 있음 | confirmed | 실패를 미접수 확정(4xx)/UNKNOWN으로 분리, UNKNOWN은 PROCESSING·guard 유지 | `HttpTimelineAiDispatcherTest`·`TimelineDraftTaskServiceTest` dispatch 분기 테스트 |
| 리뷰 반영 | `raw_id` UNIQUE가 테이블 기본 `_unicode_ci`라 Java 대소문자 구분 dedupe와 불일치 → `abc`/`ABC` 동시 요청 시 DB duplicate-key 500 위험 | confirmed | source·final raw_id를 `utf8mb4_bin`으로 전환 + dev DB ALTER 적용 | case-sensitive 통합 테스트, dev DB collation_name=utf8mb4_bin 확인 |
| 리뷰 반영 | 빈 base-url 기본값이 http 모드 기동 실패를 보장하지 않아 첫 dispatch에서야 오류 | confirmed | 생성자에서 non-blank absolute http(s) URL 검증(fail-fast) | `AiDispatcherWiringTest.httpMode_withoutBaseUrl_failsContext`·생성자 단위 테스트 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| PR reviewer (thread 1) | dispatch UNKNOWN 결과를 FAILED로 확정하지 말 것 | accepted | AI 커밋 결과와 불일치·write 겹침 차단; 4xx만 미접수 확정으로 FAILED |
| PR reviewer (thread 2) | raw_id 비교 규칙을 DB와 Java에서 통일 | accepted | opaque echo 계약 유지 위해 lowercase 강제 대신 utf8mb4_bin collation 통일 |
| PR reviewer (thread 3) | 동시 재콜백을 CAS로 원자화 | rejected | 상태 전이가 멱등(last-write-wins·compare-and-release)이라 잔여는 best-effort 중복 푸시뿐, 창도 좁아 CAS 비용 대비 이득 낮음(운영에서 문제 시 재검토) |
| PR reviewer (thread 4) | http base-url 기동 시 검증 | accepted | 상대 URI가 첫 dispatch에서야 터지는 것을 fail-fast로 대체 |
| 논의 | junction 복합키를 @IdClass→@EmbeddedId | modified | plain Long FK 컨벤션 정합 + 널리 인용되는 권고 반영, 스키마 불변 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 서비스·리포·계약 로직 | `./gradlew test` (유닛/슬라이스/ArchUnit) | passed | ecdf1da |
| N:M FK/cascade·junction 왕복·복합 PK 중복 거부·raw_id case-sensitivity·direct-write E2E·AI restart failure | `./gradlew integrationTest` (실 MySQL·Redis, 39 tests) | passed | ecdf1da, 로컬 docker 볼륨 재생성 |
| whitespace | `git diff --check` | passed | ecdf1da |
| dev DB collation 적용 | 수동 ALTER + information_schema 조회 | passed | dev-mysql: 두 raw_id 컬럼 utf8mb4_bin |

## Remaining Risks

- dev 배포 후 fake mode(`APP_AI_MODE=fake`) smoke 미실시 — 머지 후 확인 필요.
- 실 AI writer(Laimory-AI) direct-write 구현·AI DB GRANT·private HTTP 인증(이슈 #181) 미완 — http 모드 실연동은 그 이후.
- 수용된 MVP 한계: commit 후 callback 유실 task는 자동 복구 없이 TTL 만료(final graph는 유지); `(daily_record_id, raw_id)` DB UNIQUE 없음(race/legacy 중복 허용).
- dev 머지는 `Closes #180`를 발동하지 않으므로(main 전용) 이슈 #180 수동 close 필요.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- 외부 접수형 dispatch에서 "미접수 확정 vs 결과 불명(UNKNOWN)"을 구분해 UNKNOWN을 보존하는 패턴은 at-least-once/direct-write 계약 전반에 재사용 가능.
- opaque 식별자 컬럼은 애플리케이션 비교(대소문자 구분)와 DB collation을 처음부터 일치시켜야 함(rawId·FID 공통 교훈).
