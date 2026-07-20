---
schema_version: 1
status: merge-candidate
pr_number: 170
pr_url: https://github.com/soma17th-369/Laimory-server/pull/170
title: "feat: 클라이언트 제공 recordDate·timelineWindow 수신 및 정오 경계 제거"
base_branch: dev
head_branch: feat/client-timeline-window
implementation_head_sha: d27181efab14f98b04e03457c90ef325f096eff0
generated_at: 2026-07-20T14:25:00+09:00
linked_issues: [164]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #170 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: draft 생성 요청 계약을 클라이언트 권위로 전환한다 — ① `timelineWindow`(AI 이벤트 생성 범위)를
  필수 요청 필드로 받아 서버 계산(min/max·floor) 없이 Redis task로 pass-through, ② `recordDate`(기록이
  속하는 날)를 필수 요청 필드로 받아 서버 정오 경계 파생을 삭제, ③ `recordAt`을 실제 작성 시각
  메타데이터로 의미 전환.
- Acceptance criteria: recordDate·window가 계산 없이 guard·DailyRecord·Redis·finalize까지 그대로 전달;
  `recordDate`/`recordAt`/`timelineWindow` 상호 날짜 정합성 미검증(독립 계약); 필수값·`start < end`만 400;
  `computeTimelineWindow`·`resolveRecordDate` 완전 삭제; Redis field name·compact 포맷(`yyyyMMdd'T'HHmmss`)·
  `processingStartedAt`(#165) 경계 불변; 전체 unit·integration 테스트 통과.
- Out of scope: Android 측 구현(사용자 별도 진행), window의 달력 경계·하루 길이 재검증, recordDate 범위
  제한, source item 규칙·callback/staging·MySQL schema·Redis key/TTL·polling 계약 변경.

## Change Summary

- HTTP: `POST /a/api/{v}/timeline/drafts` 요청에 필수 `recordDate`(`LocalDate`)와
  `timelineWindow.startTime/endTime`(offset 없는 ISO local datetime, 새 `TimelineWindowDto`) 추가.
  OpenAPI 예시를 "다음날 아침 일기"(recordDate 07-08 ≠ recordAt 07-09) 시나리오로 교체해 독립 계약을
  예시로 고정.
- Service: `createDraftTask`가 요청 recordDate·window를 side effect(guard 선점·enrich·저장) 전에
  필수값·순서만 검증 후 그대로 사용. `computeTimelineWindow`(item min/max·음수 구간 floor) 삭제.
- `RecordDates.resolveRecordDate`(정오 경계) 삭제 — 프로덕션 호출처가 이 서비스 한 곳뿐임을 확인.
  `requireValidTimeZone`은 유지.
- HTTP DTO와 Redis entity(`TimelineDraftTask.TimelineWindow`)를 분리해 entity의 compact `@JsonFormat`이
  HTTP 파싱에 적용되지 않게 함. Redis에 저장되는 shape·포맷은 불변(값의 출처만 변경).
- knowledge 4개 문서(ubiquitous-language·invariants·timeline-draft·ai-contract) 동기 갱신.
- 이슈 #164 본문에 "범위 확장 (2026-07-20)" 절 추가(확장 계약과 근거 기록).

## Plan Deviations

- 계획 자체가 세션 중 2단계로 확정됨: 원안(window pass-through만) → 범위 확장(recordDate 명시 수신 +
  recordAt 실제 시각 + 정오 경계 삭제). 확장 근거는 발견된 통합 결함 — Android가 `recordAt`에 선택날짜
  자정을 조작 전송해 서버 정오 경계와 결합하면 `record_date`가 항상 선택날짜−1로 저장됨(조회가 ID 기반뿐이라
  미발현). 사용자가 명시 승인했고 최종 계획서·이슈 본문에 반영됨.
- 최종 계획 대비 구현 이탈: No material deviation was observed within the evidence scope.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 계획 검토 | 선택 날짜 D의 기록이 `record_date = D−1`로 저장되는 통합 결함 발견(양 저장소 코드 대조로 확인) | confirmed | recordDate 클라 명시 수신 + 정오 경계 삭제로 근본 제거. "다음날 아침 일기" UX는 Android 기본 선택 날짜 규칙로 이동(클라 측은 별도 진행) | Android `TimelineDraftRepositoryImpl.createDraft`의 `atStartOfDay()` + 서버 `resolveRecordDate` 자정→전날 경로; `createDraftTask_recordDateIndependentOfRecordAt` 회귀 테스트 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| 사용자 | recordAt 기반 서버 계산 유지안·window 파생안 검토 후 recordDate 명시 필드로 확정, window는 AI용 시간 범위 전용으로 독립 유지 | accepted | 소급 기록(picker)·수동 날짜 선택이 화면과 항상 일치하려면 날짜 권위가 신호를 가진 클라이언트에 있어야 함 |
| PR reviewer (suhyun444) | [Blocker] 원안(window만 추가)으로 되돌리고 recordDate·정오 경계 삭제를 철회하라 | rejected | 계약은 2026-07-20 범위 확장으로 재확정됨(이슈 #164 본문). 원안 복귀는 하루 밀림 결함을 보존함. 사용자 지시로 반박 회신 후 resolve — [discussion_r3612139915](https://github.com/soma17th-369/Laimory-server/pull/170#discussion_r3612139915) |
| Claude 셀프 리뷰 초안 | Nit 3건(파라미터 6개·RecordDates 이름·극단 연도 recordDate) | deferred | 사용자 승인 전 리뷰 초안 단계에서 미게시로 종료(코멘트 처리 흐름으로 전환). 전부 선택 사항 수준으로 판정했음 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| recordDate·window pass-through, 날짜 불일치 허용, 400 시 side-effect 없음 | `TimelineDraftTaskServiceTest` 46건 | passed | 0925c32 |
| HTTP 파싱(LocalDate·ISO local datetime)→서비스 전달, 상태 매핑 | `TimelineControllerTest` 15건 + `TimelineApiExampleTest` | passed | 0925c32 |
| Redis field name·compact 포맷 불변 | `TimelineTaskStoreTest` 11건 | passed | 0925c32 |
| 정오 경계 테스트 삭제·timezone 검증 유지 | `RecordDatesTest` 3건 | passed | 0925c32 |
| 전체 unit 회귀 | `./gradlew test` | passed | 로컬 실행(JDK 21), BUILD SUCCESSFUL |
| draft 생성→콜백→finalize E2E·append·토큰 | `./gradlew integrationTest`(로컬 MySQL·Redis) — `FakeAiDispatcherEndToEndIntegrationTest` 2건, `TimelineCallbackTokenIntegrationTest` 4건 포함 전체 | passed | 로컬 실행, BUILD SUCCESSFUL |
| implementation head CI | GitHub `build` check | passed | [run 29713187884](https://github.com/soma17th-369/Laimory-server/actions/runs/29713187884/job/88260678677) (d27181e) |

## Remaining Risks

- Android lockstep: 이 PR 배포 시 `recordDate`·`timelineWindow` 없는 구버전 요청은 400. Android 측
  변경(명시 전송 + recordAt 실제 시각 + 기본 선택 날짜 규칙)은 사용자가 별도 진행하며, 준비 전 서버 단독
  배포 금지.
- dev DB에 기존 정오 경계로 저장된 하루 밀린 `record_date` 행 존재 — 배포 전 데이터라 마이그레이션 없이
  무시/수동 정리 방침.
- 극단 연도 `recordDate`(예: +99999-01-01)는 400 검증을 통과하고 finalize의 MySQL DATE 범위에서 실패
  가능 — 범위 제한은 계약상 의도적 비목표(자해성 요청 한정, 조작 요청에서만 도달).
- AI 소비자 관점에서 PROCESSING task의 `timelineWindow`가 항상 non-null로 바뀜(기존엔 시간 없는 item만
  있으면 null 가능) — shape·포맷 불변이며 ai-contract.md에 기록, 실 AI 배포 전 확인 필요.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- 클라 조작 값(자정 recordAt)이 서버 휴리스틱(정오 경계)의 입력 전제를 깨는 유형의 교차 저장소 결함은
  계획 검토 단계에서 양쪽 코드를 실제로 대조해야만 드러났다 — 교차 계약 변경 계획 검토 시 상대 저장소
  원문 대조를 기본 절차로 둘 가치.
- "예시를 계약의 경계 시나리오(날짜 불일치)로 작성 + 예시 파싱 테스트로 고정" 패턴은 독립 계약의 회귀
  방지로 재사용 가치가 있음.
