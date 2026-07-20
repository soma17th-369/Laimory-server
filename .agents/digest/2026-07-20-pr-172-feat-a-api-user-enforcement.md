---
schema_version: 1
status: merge-candidate
pr_number: 172
pr_url: https://github.com/soma17th-369/Laimory-server/pull/172
title: "feat: /a/api JWT 인증 강제와 실제 userId 전파"
base_branch: dev
head_branch: feat/a-api-user-enforcement
implementation_head_sha: 05466085c4adde0a8b9e4bbd43010d27ab6aff8e
generated_at: 2026-07-20T13:55:00+09:00
linked_issues: [108]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #172 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: `/a/api` 보호 경로 7개를 permitAll → `authenticated()`로 전환하고, 고정
  `TimelineDefaults.DEFAULT_USER_ID=0` fallback을 제거해 JWT principal userId가
  draft/record/staging/날짜 guard/S3 key/폴링/편집·삭제까지 전파되게 한다(인증 게이트와 사용자 귀속을
  한 PR·한 배포 단위로 전환 — 계획 문서 `.agents/plans/108-authenticated-api-user-id.md`).
- Acceptance criteria: 7개 operation이 valid JWT만 허용, 무효 토큰은 401 `ERROR_2001` 단일 계약,
  JWT subject의 userId가 모든 귀속 지점에 동일 전파, 타 사용자·legacy task 상태 비노출(404 은닉),
  callback은 task owner·staging owner 검증, `TimelineDefaults`·"인증 도입 전" 문구 제거,
  DB schema·Redis TTL 불변.
- Out of scope: OAuth 로그인/refresh 변경, role 권한 모델, `/api`·`/s/api` Bearer 전환,
  기존 user 0 데이터 이전·삭제, legacy PHOTO URL 접근 철회, App Link(#156) 구현.

## Change Summary

- 신규 `JwtAuthenticationFilter`(security chain 내부, `/a/api` 정확한 경로 세그먼트 전용)가 Bearer JWT를
  검증해 래퍼 없는 `Long` userId principal을 만들고, 거절은 신규 `ApiAuthenticationEntryPoint`가
  401 `ERROR_2001` envelope(`WWW-Authenticate: Bearer`, `EXCEPTION_TYPE` attribute, token 비노출)로
  직접 작성한다. `SecurityConfig`는 `/a/api`·`/a/api/**`만 authenticated()이고 나머지는 permitAll
  유지(미매핑 404 계약 보존), 필터 전역 servlet 등록은 `FilterRegistrationBean#setEnabled(false)`로 차단.
- `JwtTokens`는 0·음수 userId 발급을 `IllegalStateException`으로 거절하고 parse도 empty 처리한다
  (과거 user 0 데이터 접근 차단).
- 7개 API interface·controller에 `@Parameter(hidden = true) @AuthenticationPrincipal(errorOnInvalidType
  = true) Long userId`를 추가하고 401을 문서화했다. `TimelineDefaults`는 삭제됐다.
- `TimelineDraftTask`에 additive `@JsonInclude(NON_NULL) Long userId`(세 상태 모두 보존, legacy JSON은
  null 역직렬화). 폴링은 상태 분기 전에 owner를 대조해 타 사용자·legacy를 404 `ERROR_1001`로 은닉하고,
  owner 불일치 시 관측용 WARN 로그를 남긴다. callback은 request principal 없이 task 저장 owner로
  recovery/finalize/guard 해제를 수행하며, owner 없는 legacy는 token 검증·소비 뒤 finalize 없이 404
  fail-closed. assembler는 조립 전 staging row owner ↔ task owner를 검증한다(불일치 → `ERROR_1011`).
- Redis task JSON에 owner 필드가 추가되는 것은 AI가 직접 읽는 공개 계약의 additive 변경이라
  knowledge(ai-contract 등 8개 문서)를 같은 변경에서 갱신했다.

## Plan Deviations

- 계획 §5.4의 "OpenAPI test"는 runtime spec 렌더링 대신 어노테이션 수준 계약 테스트
  (`TimelineApiAuthenticationContractTest` — bearerAuth·401 문서·hidden Long principal·7개 수 고정)로
  구현했다. 검증 목적은 동일하고 슬라이스 부팅 비용이 없다.
- 그 외에는 `No material deviation was observed within the evidence scope.`

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 테스트 갱신 | 시그니처 변경으로 test 소스 컴파일 에러 다수(8개 파일 우선 노출, 이후 2개 추가) | confirmed | 파일별 owner 인자·인증 주입 일괄 반영, `AuthTestSupport` 신설 | `compileTestJava` 에러 목록 → 0건 수렴 |
| 단위 테스트 | `TimelineDraftTaskServiceTest`의 refresh guard in-order 검증 1건 실패 | confirmed | 일괄 치환에서 누락된 `refreshDateGuard(0L, …)` 1곳을 `USER_ID`로 수정 | 해당 테스트 재실행 46/46 통과 |
| 메시지 번들 | perl 삽입이 한국어 번들 2곳에 미적용 | confirmed | Python UTF-8 삽입으로 교체 후 3개 번들 확인 | `grep ERROR_2001` 3곳 확인 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| PR reviewer (suhyun444, 세션 내 self-review) | 타 사용자 taskId 폴링(owner 불일치)에 관측용 WARN 로그 추가 | accepted | `CALLBACK_TOKEN_MISMATCH` WARN 선례와 동일한 보안 신호 관측 목적. legacy(owner null)는 타 사용자 개입이 없어 제외, 응답 은닉(1001)은 유지. 커밋 0546608 |
| plan(§9 확정 결정) | principal은 래퍼 없는 `Long`, runtime 완화 flag 없음, legacy task fail-closed | accepted | 계획 승인 시 확정된 기본안을 그대로 구현 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 인증 경계(필터/EntryPoint/JwtTokens/SecurityConfig 게이트) | `./gradlew test --tests 'com.laimory.server.auth.*' --tests '…SecurityConfigTest'` | passed | 145f2f6 (신규 3개 테스트 클래스 포함) |
| controller principal 전파·401 envelope | controller slice 테스트(무인증 401·eq(USER_ID) verify) | passed | 145f2f6 |
| userId·owner 전파(service/store/callback/assembler) | `./gradlew test` 전체 | passed | 145f2f6, 0546608 |
| Redis owner round-trip·legacy null | `TimelineTaskStoreIntegrationTest`(실 Redis) | passed | 145f2f6 |
| callback owner 보존·Bearer 무관 성공 | `TimelineCallbackTokenIntegrationTest`(실 MySQL·Redis) | passed | 145f2f6 |
| E2E(실 JWT 발급→Bearer draft/폴링→photo hash namespace→타 사용자 1001→무토큰 401→callback 성공) | `FakeAiDispatcherEndToEndIntegrationTest`(DEFINED_PORT 8080) | passed | 145f2f6 |
| 전체 회귀 | `./gradlew test build`·`./gradlew integrationTest` 전체 | passed | 0546608(로컬), build check SUCCESS |
| fallback 전수 제거 | `rg 'TimelineDefaults|DEFAULT_USER_ID|인증 도입 전|고정 사용자'` sweep | passed | src 잔존 0건(knowledge의 "제거됐다" 서술만 잔존) |
| pre-digest CI | GitHub Actions `build` check | passed | 05466085 (SUCCESS) |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- dev 머지 즉시 자동 배포되며 인증 없는 구 Android 빌드는 `/a/api`에서 전부 401을 받는다 — 실사용자
  없음 전제에서 감수하기로 결정(계획 §6.1)했고, 클라 개발자 사전 공지가 필요하다.
- 배포 후 smoke 미실행: 무토큰 401 `ERROR_2001` → 유효 토큰 404 `ERROR_1001` → 통제 draft의 userId 귀속 →
  타 사용자 토큰 1001 → OAuth 로그인 회귀 확인이 남아 있다.
- 실사용 데이터 생성 이후 구버전 전체 롤백은 금지(permitAll + userId 0 쓰기 재개) — roll-forward 원칙.
- dev Redis에 owner 없는 legacy task가 남아 있으면 TTL(최대 25h)까지 폴링 1001·콜백 fail-closed로
  거절된다(의도된 손실 허용).
- 외부 실 AI writer 연동 시 staging `user_id`가 task owner와 일치해야 한다는 계약(ai-contract.md 갱신)을
  AI 측이 반영해야 한다.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: 테스트 컴파일 에러 다수(시그니처 변경 기인)와
  단위 테스트 1건 실패·수정, perl 인코딩 삽입 실패 1건 — 모두 위 Problems 표에 기록된 범위이며 최종
  전 suite 통과로 수렴.

## Learning Candidates

- `@WebMvcTest`가 `SecurityConfig`를 `@Import`하는 슬라이스는 보안 빈 의존이 늘 때마다 일괄 수정이
  필요하다 — 공용 `AuthTestSupport.JwtTokensTestConfig` 패턴을 새 슬라이스 작성 시 기본으로 쓰면
  반복 비용이 줄어든다.
- 보호 API의 문서 계약(bearerAuth·401·hidden principal)은 runtime spec 대신 어노테이션 reflection
  테스트로 싸게 고정할 수 있었다 — 유사한 "선언 계약" 검증에 재사용 가능한 형태.
- 대량 시그니처 변경 시 컴파일러를 열거기로 쓰는 흐름(전체 깨뜨리고 파일 단위 수렴)이 유효했지만,
  일괄 치환 패턴 누락(`refreshDateGuard(0L…)`)이 테스트 실패로만 드러났다 — 치환 후 잔존 패턴
  재grep을 관행화할 가치가 있다.
