---
schema_version: 1
status: merge-candidate
pr_number: 160
pr_url: https://github.com/soma17th-369/Laimory-server/pull/160
title: "feat: 타임라인 Event 수정(PATCH)·메모(PUT) API"
base_branch: dev
head_branch: feat/timeline-event-edit
implementation_head_sha: 828257b942c1a4601274cfe82fa8dcdca1920323
generated_at: 2026-07-17T17:10:00+09:00
linked_issues: [158]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #160 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: DRAFT 타임라인 사용자 편집 계층(#158)의 PR 2/3 — Event 4필드 수정과 메모
  작성·수정·제거 API. PR 1(#159, 날짜 guard·dailyRecordId 폴링 전환) 위에서 진행.
- Acceptance criteria: `PATCH /a/api/{v}/timeline/events/{id}` 4키 모두 필수(누락=400)·절대값
  대입·명시적 null=비움; `PUT .../memo` null·blank·`{}` 제거·`String.length()` 10,000자 한도·원문
  보존; DRAFT에서만 허용(SAVED→1003), 비소유·없음 0404 은닉; PROCESSING 중 편집 허용; 갱신된
  `TimelineEventResponse` 반환; "PATCH는 memo 불변, memo PUT은 details 불변" 계약.
- Out of scope: 삭제+S3(PR 3), DRAFT→SAVED·emotion API, 인증(#108).

## Change Summary

- `TimelineRecordApi`/`TimelineRecordController` 신설(기존 draft API와 분리된 interface+controller
  한 쌍, base `/a/api/{v}/timeline`).
- `TimelineEvent` 최초의 mutation 2개(`updateDetails`/`updateMemo`, 대입 전용) +
  **`@DynamicUpdate`** — PATCH(details)와 PUT(memo)이 같은 row의 다른 필드 그룹을 독립 갱신하므로,
  기본 전체-컬럼 UPDATE의 교차-필드 lost update를 차단(변경 컬럼만 SET).
- `UpdateTimelineEventRequest`에 DTO 내부 `KeyPresenceDeserializer` — Jackson이 구분 못 하는
  키 누락/명시적 null을 분리해 "누락=400, null=비움" 계약을 강제(새 의존성 없음, 누락은 깨진
  JSON과 동일한 400 경로).
- `TimelineEventEditService` 오케스트레이터(조회→소유권/DRAFT 검증→입력 검증·정규화(strip,
  blank→null)→dirty checking 변경→응답 조립). 소유권은 `DailyRecord.userId` 조인, userId는
  컨트롤러가 결정(`DEFAULT_USER_ID`).
- `ExceptionType.TIMELINE_EVENT_NOT_FOUND`(→기존 `ERROR_0404`) 한 줄 — ErrorCode·메시지 번들
  신설 없음.
- knowledge: timeline-draft·ubiquitous-language·invariants에서 memo 편집을 "현재 구현"으로 정합화.

## Plan Deviations

- 계획 대비 추가 2건(리뷰 반영): `@DynamicUpdate`(계획엔 없던 동시성 방어 — 계약("PATCH는 memo
  불변")을 지키는 방향), `KeyPresenceDeserializer`(계획 원문 "안 보낸 필드=400"의 구현 누락 복원).
- 구현 재량 3건: api.md 미변경(대조 결과 어긋난 서술 없음 — 문서 자체 규칙 "새 내부 원인 =
  ExceptionType만"에 해당), trim 대신 `String.strip()`(blank 판정 `isBlank()`와 동일 Unicode 기준),
  memo blank 검사가 길이 검사에 선행(10,001자 공백 = 400이 아니라 제거 — blank→제거 규칙의 귀결).
- 그 외 No material deviation was observed within the evidence scope.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 최초 구현 서브에이전트 실행 | API 연결 오류로 에이전트 중단(서비스 테스트 작성 직전) | confirmed (인프라 오류) | 같은 에이전트를 transcript 보존 상태로 재개해 이어서 완료 — 코드 유실·중복 없음 | 중단 시점 diff와 재개 후 완성 커밋 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| PR reviewer (suhyun444, Blocker) | 키 누락과 명시적 null 미구분 — 부분 요청이 subtitle/endAt을 조용히 삭제 | accepted | 계획서 원문 계약("안 보낸 필드=400")과 일치 — 구현이 완화했던 것을 복원. 커밋 4c426d7 |
| PR reviewer (suhyun444, Blocker) | `@DynamicUpdate` 부재로 교차-필드 lost update 가능 | accepted | Hibernate 6.6 공식 문서로 기본 전체-컬럼 UPDATE 확인, 어노테이션 제거 시 신규 동시성 통합 테스트가 실제 실패함을 실험으로 입증. @Version은 범위 초과로 기각(같은 필드 last-write-wins는 명문화된 수용). 커밋 4c426d7 |
| PR reviewer (suhyun444, Blocker) | invariants.md에 "memo 입력 API 없음" stale 문구 | accepted | 문서 모순 확인, memo만 제거(emotion 유지). 커밋 828257b |
| 사후 조사(공식 문서·커미터 소스) | @DynamicUpdate 안티패턴 여부 | accepted(현행 유지) | 비판의 실체는 전역 기본 적용·성능 맹신·낙관적 락 대체 오용 — 본 건은 국소·정합성 목적이라 비해당. 전제(managed 전용·만진 필드만 대입)와 미래 유의점(@Version 추가 시 의미 변화, 배칭 도입 시)을 조사로 문서화 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| PATCH/PUT 계약(경계값 255/256·10,000/10,001자, 키 누락 400, null 비움, 소유권 은닉, SAVED 거절, Item 불변) | 단위 테스트 31건 + 키 presence MockMvc | passed | 828257b 로컬 `./gradlew test` |
| 교차-필드 lost update 차단 | `TimelineEventEditConcurrencyIntegrationTest`(실 MySQL, latch 동기화) — @DynamicUpdate 임시 제거 시 FAILED 확인 후 복원 | passed | 828257b 로컬 `./gradlew integrationTest` |
| 전체 빌드·기존 회귀 | `./gradlew build` + GitHub Actions `build` | passed | implementation head 828257b check SUCCESS |

## Remaining Risks

- 교차-필드 보호는 "managed 엔티티 + 만진 필드만 대입" 패턴이 전제 — 미래에 `merge()`/전필드
  복사 매퍼가 들어오면 조용히 무력화(리뷰 관점으로 인지).
- 같은 필드 동시 수정은 last-write-wins(현 계약의 의도된 수용, 엔티티 주석 명문화).
- Android 후속 미반영: PATCH 4키 필수 계약·400 처리(서버 선반영 합의).
- dev 실환경 편집 동작 관찰은 머지·배포 후 수행 예정.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: 구현 서브에이전트 1회 인프라 중단(위 표) 외
  not observed within the evidence scope

## Learning Candidates

- 여러 필드 그룹을 서로 다른 API가 독립 갱신하는 엔티티는 `@DynamicUpdate`(또는 동급 장치) 검토가
  기본 체크리스트 — 그리고 "어노테이션 제거 시 테스트가 실제 실패하는지" 실험으로 테스트 유효성을
  입증하는 방식이 유효했다.
- absent/null 구분이 계약에 있으면 Jackson 기본 역직렬화로는 구현 불가 — 계약 확정 시점에 구현
  가능성(커스텀 deserializer 필요)을 같이 검토할 것.
- 계약을 구현이 조용히 완화하는 드리프트는 리뷰가 계획서 원문과 1:1 대조할 때만 잡힌다.
