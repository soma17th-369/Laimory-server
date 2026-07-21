---
schema_version: 1
status: merge-candidate
pr_number: 178
pr_url: https://github.com/soma17th-369/Laimory-server/pull/178
title: "feat: AI 타임라인 완료 FCM 푸시 알림 발송"
base_branch: dev
head_branch: feat/174-timeline-fcm-push
implementation_head_sha: 0f86e5c99b804256050a27ba7e5553b161edb197
generated_at: 2026-07-22T01:35:00+09:00
linked_issues: [174]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #178 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: AI 타임라인 draft 작업이 콜백 처리로 SUCCESS/FAILED에 도달하면 task owner의 활성 앱 설치
  전체(FID)에 FCM 완료 푸시를 비동기 best-effort로 발송한다. FCM은 완료 신호일 뿐이며 polling이 결과의
  권위 원천이자 유실 안전망이다.
- Acceptance criteria: FID 등록 PUT/DELETE API(멱등·원자 재결합), `push_registrations` 저장(단일 owner
  unique FID), Firebase Admin 9.10.0 FID target 발송(500 chunk·오류 분류별 무효 정리), callback terminal
  확정 뒤에만 enqueue·실패 시 콜백 200 보존, noop/firebase 모드와 배포 preflight/runbook.
- Out of scope: Android 구현, durable retry/outbox, dispatch 동기 실패(ERROR_1009) 알림, topic 발송,
  stale 등록 일괄 정리 scheduler.

## Change Summary

- 신규 feature package `com.laimory.server.push`: 인증 PUT/DELETE `/a/api/{v}/push-registrations`
  (FID는 body로만 수신, access log 마스킹), native `INSERT ... ON DUPLICATE KEY UPDATE` 원자
  upsert(계정 전환 재결합), `PushMessageSender` port + noop(기본)/firebase 구현.
- `TimelineCallbackService.finishSuccess/finishFailed`가 terminal 저장·guard 해제 뒤
  `TimelineCompletionPushNotifier.notifyAsync`(@Async, 별도 빈)를 quiet enqueue — 모든 예외가 콜백
  응답과 격리된다.
- FCM 발송: notification(일반 문구) + data(`taskId`,`status`), Android TTL 1h, 기본 priority,
  최대 500 FID chunk, `UNREGISTERED`/target-level `INVALID_ARGUMENT`만 snapshot 조건부 삭제.
- schema: `push_registrations` 추가(컬럼 단위 `utf8mb4_bin`, soft-owner user_id 무FK). live DB에는
  배포 전 수동 DDL 선적용 계약(`ddl-auto=validate`).
- 배포: deploy.yml에 firebase 모드 조건부 preflight(파일 존재 + pull image의 runtime user `test -r`) 및
  read-only credential mount, was.sh.tftpl secrets 골격, terraform/README FCM 활성화·rollback runbook.
- knowledge 10문서 현행화(ubiquitous-language, invariants, timeline-draft, api, ai-contract,
  external-integrations, persistence, environments, deployment, observability).

## Plan Deviations

- 계획 대비 3건의 리뷰 반영 확장: (1) credential preflight를 root 관점 파일 검사에서 runtime user(UID
  1001) 읽기 검증으로 강화, (2) Firebase Admin SDK HTTP timeout 유한값(connect 5s/read·write 15s) 강제,
  (3) 무효 등록 삭제를 발송 snapshot 시각 조건부로 변경. 계약 방향은 계획과 동일하며 안전 경계만 강화됐다.
- 그 외에는 `.agents/plans/174-timeline-fcm-push.md`(plan-review 판정 Ready) 그대로 구현됐다.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| sender unit test | Mockito UnfinishedStubbingException 15건 | confirmed | `thenReturn(batchOf(...))` 인자 안 중첩 스터빙을 batch 사전 조립로 변경 | 수정 후 동일 테스트 전부 통과 |
| config test | firebase fail-fast 단언이 rootCause(IOException)와 불일치 | confirmed | 실패 체인 전체를 `hasStackTraceContaining`으로 검증 | FirebasePushConfigTest 통과 |
| 리뷰 반영 편집 | 세션 요약 후 파일 내용 불일치로 Edit 1회 실패 | confirmed | 파일 재독 후 정확한 원문으로 재시도 | FirebasePushConfig 수정 커밋 0f86e5c |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| PR reviewer | credential이 runtime user(appuser, UID 1001)에게 읽히는지 preflight 필요 | accepted | Dockerfile `USER appuser` 확인 — ubuntu:0600 배치 파일은 컨테이너가 못 읽어 배포 다운 유발 |
| PR reviewer | Admin SDK HTTP timeout 기본 0(무한) → 유한값 강제 | accepted | FirebaseOptions timeout setter 3종 확인, hang 시 @Async thread 고갈 경로 실재 |
| PR reviewer | 무효 FID 삭제는 send snapshot 조건부여야 함 | accepted | 지연 UNREGISTERED가 snapshot 이후 재등록 행을 삭제하는 레이스 실재 — `last_registered_at <= snapshot` 조건 추가 |
| Claude(self review) | noop 모드 로그 targets가 0으로 보고되는 관측성 문제 | accepted | notifier가 조회한 FID 수를 로깅하도록 변경 |
| plan-review | Firebase 9.10.0 FID target·Android 25.1.x 계약은 1차 소스로 검증됨 | accepted | GitHub release/Maven Central/공식 문서 대조 후 계획 확정(Ready) |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 등록 API·masker·sender·notifier·config·콜백 회귀 | `./gradlew test` (전체 unit) | passed | 0f86e5c 로컬 실행 |
| upsert 원자 재결합·binary collation·조건부 삭제 | `./gradlew integrationTest`(docker MySQL, 볼륨 재생성) | passed | 0f86e5c 로컬 실행(push 9건 포함) |
| 빌드·이미지 | `./gradlew build` + `docker build` | passed | 7372bce 시점 로컬 실행 |
| CI build check | GitHub Actions | passed (1m49s) | 0f86e5c |
| dev DB DDL 선적용 | 사용자 수동 적용 확인(대화) | passed | 사용자 확인 — 도구 검증은 SSO 만료로 미수행 |
| dev 실기기 FCM smoke | 수동 | not-run | firebase 모드 활성화 후 별도 수행 예정 |

## Remaining Risks

- dev WAS에 기배치된 credential 파일이 ubuntu:0600 상태 — firebase 모드 활성화 전 `chown 1001` +
  `chmod 0400` 필요(누락 시 신규 preflight가 배포를 중단시킴).
- firebase 모드 실기기 검증(SUCCESS/FAILED·권한 거부·계정 전환)은 Android 측 FID 등록 구현 후에만 가능.
- 노션 API 명세에 신규 push-registrations 엔드포인트 미반영.
- prod는 자체 배포라 이 워크플로 밖 — `APP_PUSH_MODE` 미주입 시 noop 기본으로 안전.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: Mockito 중첩 스터빙 15건(테스트만), config test 단언
  1건, 세션 요약 후 Edit 원문 불일치 1건 — 모두 같은 세션에서 해결됨.

## Learning Candidates

- `thenReturn()` 인자에서 mock 조립 헬퍼를 호출하면 중첩 스터빙으로 즉사한다 — batch/stub은 반드시
  바깥 `when(...)` 이전에 완성.
- public accessor가 없는 SDK 내부 계약(MulticastMessage 등)은 버전 핀 + reflection 테스트로 고정하면
  업그레이드 시 구조 변화를 먼저 잡는 가드가 된다.
- 파일 mount 기반 secret은 컨테이너 runtime UID 관점의 읽기 검증까지 preflight에 포함해야 root 관점
  검사의 사각을 없앤다.
