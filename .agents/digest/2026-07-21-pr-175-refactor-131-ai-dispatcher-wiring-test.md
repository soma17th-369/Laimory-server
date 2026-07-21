---
schema_version: 1
status: merge-candidate
pr_number: 175
pr_url: https://github.com/soma17th-369/Laimory-server/pull/175
title: "test: Fake AI 실 HTTP E2E를 mock·배선 테스트로 대체 (#131)"
base_branch: dev
head_branch: refactor/131-ai-dispatcher-wiring-test
implementation_head_sha: 01ab26d6f20ff6d9ed6c854d5e2128c6b970b5dd
generated_at: 2026-07-21T01:27:52Z
linked_issues: [131]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #175 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 멘토 피드백에 따라 실 HTTP(고정 port 8080)·MySQL·Redis·자기 콜백을 한 시나리오로 묶던
  `FakeAiDispatcherEndToEndIntegrationTest`를 제거하고, `app.ai.mode` 배선 검증을
  `ApplicationContextRunner` 기반 테스트로 CI(`./gradlew build`) 범위에 편입한다.
- Acceptance criteria: `AiDispatcherWiringTest` 4경우(missing·noop·fake·unknown→실패) 통과, fake case는
  Boot 자동설정 `RestClient.Builder`+실제 staging 빈(leaf mock)+`@Async` proxy 확인, 고정 port 8080
  점유 테스트 없음, runtime 코드 동작 무변경(주석-only 교정만 허용), `./gradlew build` 통과.
- Out of scope: dev runtime fake(`APP_AI_MODE=fake`) 제거, callback 계약·staging schema·TTL 변경,
  `@Async` 구현 리팩터링. runtime fake까지 제거하는 대안은 별도 팀 결정 사항으로 계획서 §11에 보존.

## Change Summary

- 신설 `AiDispatcherWiringTest`: mode별 dispatcher/staging 빈 선택과 unknown mode fail-fast(root cause
  `NoSuchBeanDefinitionException`)를 검증. test-only required consumer로 인터페이스를 요구시키고,
  `RestClient.Builder`는 production과 같은 `HttpClientAutoConfiguration`+`RestClientAutoConfiguration`
  (Boot 3.5.8 실존 확인)으로 충족. `@Async` AOP proxy 적용을 `AopUtils`/`AopProxyUtils`로 확인.
- `FakeAiDispatcherEndToEndIntegrationTest`(298줄) 삭제. 개별 단언의 소유 테스트 매핑은 이슈 #131
  본문과 계획서 §5.1에 기록. 전체 조합 보장(실 HTTP 자기 콜백 도달, 연속 append A/B/C 등)은 멘토
  피드백에 따라 의도적으로 포기.
- 주석-only 교정: dispatcher Javadoc·드리프트 주석(존재하지 않게 될 E2E 안내 제거, focused test
  소유권 명시), deploy.yml `APP_AI_MODE` 주석(dev runtime 시뮬레이터로 표현 교정), build.gradle
  Awaitility 주석(현 사용처인 geocoding·Tomcat valve 테스트 포괄). 두 보호 파일의 diff가 주석 줄뿐임을
  `git diff` 라인 검사로 확인.
- knowledge 동기화 6개 문서: testing/local-development/environments/ai-contract/README/change-impact에서
  E2E·port 8080 제약 제거, 새 소유권(wiring + `TimelineCallbackTokenIntegrationTest`) 반영.

## Plan Deviations

- plain `ApplicationContextRunner`에는 `@Value "2s"` → `Duration` 변환기가 없어 fake case가
  `ConversionNotSupportedException`으로 실패했다. production 메커니즘(`SpringApplication`이 심는
  `ApplicationConversionService`)과 동일한 변환기를 `conversionService` 빈으로 등록해 해결 — 계획에
  없던 test-harness 한정 추가이며 계획 리뷰에서 구현 단계 해결로 분류했던 항목이다.
- 그 외에는 `No material deviation was observed within the evidence scope.`

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 단계 1 테스트 첫 실행 | fake case 컨텍스트 기동 실패(`ConversionNotSupportedException`: String→Duration) | confirmed | `ApplicationConversionService`를 `conversionService` 빈으로 등록(주석으로 이유 명시) | 실패 리포트 XML 원인 추출 후 수정, 재실행 4/4 통과 |
| 커밋 분리 | 첫 커밋에 스테이징돼 있던 E2E 삭제가 섞여 들어감 | confirmed | `git reset --soft HEAD~1` 후 pathspec 커밋으로 4개 논리 커밋 재구성 | 최종 커밋 4개(6cb4724→01ab26d), push 전 수정 완료 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| Claude plan review | dispatcher "E2E가 드리프트 검출" 주석이 삭제 후 거짓이 되는데 no-diff 완료 조건과 충돌 | accepted | 주석-only 교정 예외를 계획에 추가, 구현 반영 |
| 외부 리뷰(사용자 전달) | 수동 `RestClient.Builder` 빈은 자동설정 회귀를 가림 | accepted | Boot 3.5.8 jar에서 두 자동설정 클래스 실존 확인 후 fake case를 자동설정 경로로 전환 |
| 외부 리뷰(사용자 전달) | deploy.yml·dispatcher Javadoc의 E2E 표현도 교정 필요 | accepted | `rg "E2E"` 전수(3곳) 확인 후 전부 교정, 계획 예외 조항 확장 |
| Claude PR self-review 초안 | `spring.http.client.*` 2줄은 효과 미단언 [Nit] | deferred | production property 미러링 목적 유지, 사용자 머지 지시로 리뷰 게시 생략 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| mode 배선 4경우 | `./gradlew test --tests 'AiDispatcherWiringTest'` | passed | 01ab26d (local) |
| focused 7클래스(디스패처·스테이징·컨트롤러 등) | `./gradlew test --tests ...` MySQL·Redis·고정 포트 없이 | passed | 01ab26d (local) |
| 전체 컴파일·단위 검증 | `./gradlew build` | passed | 01ab26d (local) + CI build SUCCESS (run 29759283222) |
| callback DB·Redis 계약 잔존 | `docker compose up -d && ./gradlew integrationTest --tests 'TimelineCallbackTokenIntegrationTest'` | passed | 01ab26d (local) |
| E2E 잔여 참조 | `rg 'FakeAiDispatcherEndToEndIntegrationTest|fake AI E2E|E2E.*8080|8080.*E2E'` + `rg "E2E" src/main .github` | passed (0건) | 01ab26d (local) |
| 보호 파일 주석-only diff | `git diff` 라인 검사(dispatcher, deploy.yml) | passed | 01ab26d (local) |

## Remaining Risks

- 콜백 URL 상수와 컨트롤러 매핑의 조합 드리프트를 자동 검출하는 테스트가 더 이상 없다(의도적 수용).
  URL 형태는 `FakeAiTimelineEventSuggestionDispatcherTest`, 매핑은 `TimelineCallbackControllerTest`가
  분리 소유하며, dispatcher 주석에 이 소유권을 명시했다.
- dev 머지로는 `Closes #131`이 발동하지 않으므로 이슈 #131 수동 close가 필요하다.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: fake case Duration 변환 실패 1건(수정 완료),
  커밋 분리 실수 1건(push 전 재구성)

## Learning Candidates

- plain `ApplicationContextRunner`로 `@Value` Duration 프로퍼티를 가진 빈을 기동하는 테스트에는
  `ApplicationConversionService`를 `conversionService` 빈으로 등록해야 한다 — 이후 wiring test
  선례로 재사용 가치(프로젝트 메모리에 기록됨).
- 배선 테스트에서 provider 빈 생성자 의존성은 수동 빈 대신 production과 같은 Boot 자동설정으로
  충족시키면 자동설정 회귀까지 검증 범위에 들어온다(`GeoWiringTest`·본 PR 공통 패턴).
