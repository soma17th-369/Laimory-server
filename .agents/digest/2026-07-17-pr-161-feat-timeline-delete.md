---
schema_version: 1
status: merge-candidate
pr_number: 161
pr_url: https://github.com/soma17th-369/Laimory-server/pull/161
title: "feat: 타임라인 Event·DailyRecord 삭제 API와 S3 배치 삭제"
base_branch: dev
head_branch: feat/timeline-delete
implementation_head_sha: 9aa2e42ea326845f93aabf086093d76934054f40
generated_at: 2026-07-17T18:40:00+09:00
linked_issues: [158]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #161 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 타임라인 편집 계층(#158)의 마지막 PR(3/3) — Event 삭제와 DailyRecord(하루 전체) 삭제
  API. PHOTO의 S3 객체를 동기 배치로 선삭제한 뒤 DB cascade 삭제.
- Acceptance criteria: DELETE 2종 `200 ApiResponse<Void>`; 마지막 Event 삭제에도 Record 유지;
  날짜 guard `delete:{operationId}` 선점(PROCESSING 중·동시 삭제 → 1016)과 **모든 종료 경로 finally
  compare-and-release**; S3 `DeleteObjects` ≤1,000/batch + 요청 단위 10s/3s 타임아웃, 실패 시
  `ERROR_1017`(신설, 502)로 DB 보존·재시도 수렴; orphan 허용 규칙(cleanup과 동일); 별도 bean 짧은
  트랜잭션에서 소유권·DRAFT 재확인 후 `deleteById`(하위는 DB FK cascade); 로그에 key·URL·내용 금지.
- Out of scope: Outbox·보상 업로드·참조 카운트, CloudFront invalidation, DRAFT→SAVED·emotion API,
  인증(#108).

## Change Summary

- `TimelineRecordApi`/`Controller`에 DELETE 2종 추가.
- 신설 `TimelineDeletionService`(오케스트레이터): 사전검증(fast-fail: 404 은닉·SAVED 1003) → guard
  선점 → **guard 안에서** PHOTO key 수집(`Supplier` 지연 평가로 순서를 구조로 강제) → S3 배치 →
  DB 삭제 bean → finally release. 신설 `TimelineDeletionTransactionService`: 별도 bean
  `@Transactional`에서 소유권·DRAFT 재확인 후 단건 `deleteById`.
- `S3PhotoStorageService.deleteAll`: 1,000 key/batch 순차, `AwsRequestOverrideConfiguration`으로
  요청 단위 apiCallTimeout 10s·apiCallAttemptTimeout 3s(전역 client·단건 delete 불변). SDK 예외·
  객체별 error → `BusinessException(PHOTO_BATCH_DELETE_FAILED)`.
- `ERROR_1017`(BAD_GATEWAY) + `ExceptionType.PHOTO_BATCH_DELETE_FAILED`(ERROR)·
  `DAILY_RECORD_NOT_FOUND`(INFO) + 메시지 3번들. `TASK_FAILURE_CODES` 미포함(동기 502 전용).
- `TimelineTaskService.deleteGuardHolder`, leaf `deleteById` 2개.
- knowledge 4건: persistence(S3 삭제 2경로·guard holder 확정), timeline-draft, invariants(S3 성공
  후에만 DB cascade, **향후 save API도 같은 guard 취득**), ubiquitous-language.

## Plan Deviations

- guard 선점 전에 조회·검증 수행(계획: 선점→검증): guard 키에 필요한 recordDate가 record 조회
  없이는 없고, 가망 없는 요청을 Redis 부수효과 전에 거절. 권위 검사는 계획 위치 유지 — PHOTO key
  수집은 guard 안(동시 finalize의 item 추가 창 배제, 테스트로 순서 고정), 상태 재확인은 DB 트랜잭션 안.
- 1017 변환 위치를 `S3PhotoStorageService.deleteAll` 내부로(SDK 예외·객체별 error 두 실패 모드의
  의미가 동일해 한 곳에 배치).
- Redis 통합의 "삭제 후 draft 생성 성공"은 전체 draft 경로 대신 guard 선점 성공으로 검증(enrich·
  dispatch 의존 배제, guard 직렬화 불변식만 정밀 검증).
- 그 외 No material deviation was observed within the evidence scope.

## Problems Encountered

No material problem was observed within the evidence scope.

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| 구현 중 자체 판단 | items 조회를 값 파라미터가 아닌 `Supplier`로 주입 | accepted | "guard 선점 후 수집" 순서를 호출부 재량이 아닌 골격 구조로 강제 — 수집·삭제 사이 동시 finalize의 S3 orphan 창 제거 |
| 계획(plan-review 확정) | 실패 경로 포함 finally 해제 | accepted | 미해제 시 클라 재시도가 guard TTL(1h) 동안 1016으로 차단돼 "재시도 수렴" 설계가 깨짐 |

GitHub PR 리뷰 스레드는 not observed within the evidence scope(0건).

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| chunk 분할(0·1·1,000=1batch·1,001=2batch)·객체별 부분 실패·SDK 예외→1017+DB 미삭제·orphan skip·key 중복 제거·SAVED/404/1016·모든 경로 release | 단위 테스트(S3 mock) | passed | 9aa2e42 로컬 `./gradlew test` |
| Event cascade·마지막 Event 삭제 후 Record 유지·Record 전체 cascade | 실 MySQL 통합(`@Tag("integration")`) | passed | 9aa2e42 로컬 `./gradlew integrationTest` |
| 삭제 guard 직렬화(선점 중 draft 1016·해제 후 선점 성공) | 실 Redis 통합 | passed | 9aa2e42 로컬 `./gradlew integrationTest` |
| 전체 빌드·기존 회귀(8080 E2E 포함) | `./gradlew build` + GitHub Actions `build` | passed | implementation head 9aa2e42 check SUCCESS |

## Remaining Risks

- 일부 S3 batch만 성공한 구간은 복구 장치 없이 재시도 수렴에 의존(계획 수용 사항 — 이미 삭제된
  key는 S3가 성공 처리).
- guard TTL 만료 창(>1h stale task callback)에서 finalize가 삭제와 인터리브하면 S3 orphan 가능 —
  PR 1부터 수용한 위험 범위 내(피해는 스토리지 누수로 한정).
- dev 배포 후 관찰 필요: 현실적 사진 수로 삭제 latency·`photoObjects/batches/s3ElapsedMs/
  totalElapsedMs` INFO 로그(1,001개 실객체는 만들지 않음).
- Android 후속: DELETE 2종·`ERROR_1017` 재시도 UX. 새 엔드포인트는 #108 인증 스윕 대상.
- 이슈 #158은 dev 대상 PR이라 `Closes` 미발동 — 머지 후 수동 close 필요.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- 타이밍이 정합성인 파이프라인에서는 값 대신 `Supplier`(지연 평가)를 주입해 "언제 실행되는가"의
  소유권을 골격에 모으는 패턴이 유효했다.
- 외부 저장소(S3) 선삭제→DB 후삭제 순서 + "실패 시 원본 보존→재시도 수렴"은 Outbox 없이도
  멱등 삭제를 만드는 저비용 레시피(전역 사진 참조 카운트가 없다는 전제 확인 필수).
