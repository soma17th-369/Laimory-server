---
schema_version: 1
status: merge-candidate
pr_number: 177
pr_url: https://github.com/soma17th-369/Laimory-server/pull/177
title: "feat: dev assetlinks.json에 debug 앱 App Link 관계 등록"
base_branch: dev
head_branch: feat/176-dev-assetlinks-debug-app-link
implementation_head_sha: 1b1361456901d593c829e5ace507fbaf3a1367b1
generated_at: 2026-07-21T02:20:41Z
linked_issues: [176]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #177 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: Android 로그인 App Link 검증(soma17th-369/Laimory-android#156)의 서버측 작업으로, dev의
  빈 `/.well-known/assetlinks.json`(`[]`)을 debug 앱(`com.soma369.laimory.debug`)의 Digital Asset
  Links 관계로 교체해 도메인 검증이 성립하게 한다.
- Acceptance criteria(이슈 #176): well-known 경로가 redirect 없이 무인증 200 `application/json`으로
  debug package·확정 fingerprint 관계를 반환하고, 그 계약이 테스트로 고정되며, 기존 auth/security
  테스트가 회귀 없이 통과한다.
- Out of scope: release 앱·`laimory.app`용 association·Play App Signing·prod OAuth(후속 분리),
  Android 쪽 `autoVerify`·단말 검증(Laimory-android #156에서 진행), 새 controller/nginx/Terraform 변경.

## Change Summary

- `src/main/resources/static/.well-known/assetlinks.json`: 빈 배열을 단일 statement로 교체 —
  relation `delegate_permission/common.handle_all_urls`, namespace `android_app`,
  package `com.soma369.laimory.debug`, 계획서에서 확정된 debug 인증서 공개 SHA-256 fingerprint 1건.
  추가 개발자 fingerprint는 같은 statement의 배열에 누적하는 운영 방침(계획서 §4.1).
- `SecurityConfigTest`: `assetLinks_servedWithoutAuthAsExactDebugAppRelation` 추가 — 무인증 200,
  `application/json`, relation·namespace·package·fingerprint 정확 일치를 `@WebMvcTest` 슬라이스의
  정적 리소스 핸들러 경로로 고정. 클래스 javadoc의 계약 목록에 한 줄 추가.
- 런타임 코드·설정 변경 없음. 제공 경로는 기존 Spring Boot 정적 리소스(permitAll) 그대로다.

## Plan Deviations

- 계획서 `.agents/plans/156-android-app-link.md`(plan-review 판정 Ready) §6.2 순서대로 구현했다.
  No material deviation was observed within the evidence scope.
- 계획 §6.1의 "Android #156에 dev-only 범위 기록" 항목은 Android 저장소 쓰기 금지 규칙에 따라
  사용자 직접 수행으로 위임하고 서버측 구현에서 제외했다.

## Problems Encountered

No material problem was observed within the evidence scope.

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| Claude plan-review | 계획 Ready 판정 — 서버 파일·Security permitAll·nginx/deploy 경로·라이브 200 재확인, 수정 요구 없음 | accepted | 저장소·라이브 대조 근거로 계획 그대로 구현 |
| Claude self-review 초안 | 테스트의 fingerprint 문자열 2분할이 전문 grep을 어렵게 함 (nit) | deferred | 사용자가 리뷰 게시 전 머지를 지시 — 동작 영향 없는 스타일 항목이라 미반영 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| well-known 무인증 200 JSON·package·fingerprint 계약 | `SecurityConfigTest` (신규 테스트 포함 대상 실행) | passed | 1b13614, 로컬 `./gradlew test --tests ...` |
| OAuth success/failure redirect·핸드오프 회귀 | `OAuth2LoginSuccessHandlerTest`·`OAuth2LoginFailureHandlerTest` | passed | 1b13614, 로컬 targeted 실행 |
| 전체 단위 테스트·공백 검사 | `./gradlew test`, `git diff --check` | passed | 1b13614, 로컬 실행 |
| CI build | GitHub Actions `build` check | passed | https://github.com/soma17th-369/Laimory-server/actions/runs/29793856503/job/88521083511 |
| 배포 전 dev 라이브 상태(빈 `[]` 200 JSON, redirect 없음) | `curl` 직접 확인 (2026-07-21) | passed | https://dev.laimory.app/.well-known/assetlinks.json |

## Remaining Risks

- dev 머지·배포 후 검증 미실행: well-known HTTP 응답(redirect 없는 200, 새 관계 반영)과 Digital
  Asset Links API `statements:list` 조회 확인이 남아 있다(이슈 #176 체크리스트).
- 등록 fingerprint는 계획서 확정값이며, 실제 설치할 debug APK의 `apksigner verify --print-certs`
  결과와의 최종 대조는 사용자/Android 담당자 수행으로 남아 있다.
- Android 쪽 `autoVerify` 적용·단말 검증(Laimory-android #156)과 dev-only 범위 코멘트 기록이 남아 있다.
- dev 머지는 `Closes #176`을 발동하지 않으므로 이슈 #176은 수동 close가 필요하다.
- 이 dev 정적 관계를 미래 prod host에 그대로 노출하지 않도록 release association 제공 방식이 후속
  과제로 남아 있다(계획서 §5.2).

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- `@WebMvcTest` 슬라이스에서도 정적 리소스 핸들러가 동작해 `classpath:/static/` 파일 계약을 MockMvc로
  고정할 수 있다 — well-known류 정적 계약 테스트에 재사용 가능.
- 이 macOS 환경에는 `python` 명령이 없어 스킬 스크립트는 `python3`로 호출해야 한다.
