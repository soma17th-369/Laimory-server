---
schema_version: 1
status: merge-candidate
pr_number: 148
pr_url: https://github.com/soma17th-369/Laimory-server/pull/148
title: "feat: 지오코딩을 WebClient/Reactor로 전환하고 좌표 간 병렬 조회 도입"
base_branch: dev
head_branch: feat/geo-reactive-parallel-lookup
implementation_head_sha: c98cc47e1fe722007d0ba198913bb3512c33a5da
generated_at: 2026-07-14T05:30:00Z
linked_issues: [133]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #148 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 지오코딩 enrich의 좌표 조회를 WebClient/Reactor(멘토 지정)로 전환하고 좌표 간 bounded 병렬 fan-out을 도입해, 좌표 N개의 wall-clock이 콜 수에 선형이던 구조를 동시 조회로 바꾼다. PR1(#147, keyword 전환)의 후속.
- Acceptance criteria: `MapPlaceProvider`가 `Mono<GeoPlace>` 반환·blocking 경계는 `GeocodingService` 전담(Reactor가 timeline 계층에 새지 않음). 재시도·strict loud-fail·1014/1015 매핑 계약 보존. 동시 상한 `app.geo.lookup-concurrency`(기동 시 >=1 fail-fast). transactionId가 이벤트루프 스레드 로그에 보존. `./gradlew build`·`integrationTest`·`docker build` 통과.
- Out of scope: 서버 전면 리액티브 전환(WebFlux 서버·R2DBC), micrometer context-propagation 도입(수제 헬퍼 유지 — 사용자 결정), dev 스모크(머지 후 관찰 게이트).

## Change Summary

- transport reactive화: `MapPlaceProvider.lookup` → `Mono<GeoPlace>`, `KakaoMapPlaceProvider`를 WebClient(Reactor Netty)로 재작성. 재시도는 `retryWhen(Retry.fixedDelay(1, 200ms))` + retryable 필터 + `onRetryExhaustedThrow`(원본 예외 보존). classifier 첫 분기는 기존 `MapPlaceLookupException` 통과(재래핑 금지). 최종 실패 warn 1회.
- 타임아웃 SSOT 이동: 지오코딩은 `spring.http.reactiveclient.connect/read-timeout=2s`(기존 `spring.http.client.*`는 fake AI dispatcher·TestRestTemplate용 존치). `app.geo.kakao-base-url` 프로퍼티화(MockWebServer test seam).
- 좌표 간 병렬화: enrich 2-pass(STAY 좌표·MOVEMENT start/end만 LinkedHashSet 수집 — PhotoPayload는 좌표 필드가 있어도 비대상, 빈 set이면 lookupAll 생략) → `GeocodingService.lookupAll`이 `flatMap(concurrency)`로 병렬 조회 → 재구성. `Coordinate` record를 geo 패키지 public으로 승격.
- 동시 상한: `app.geo.lookup-concurrency` 기본 20(`APP_GEO_LOOKUP_CONCURRENCY`), 생성자 `>=1` fail-fast. 카카오 초당 한도 비공개(공식 확인)·429=영구 실패+strict라 무제한 대신 상한.
- 실패 의미 변화(수용된 트레이드오프): 1014/1015는 배치 종합이 아니라 **가장 먼저 관측된 실패**의 분류 — 전이·영구 경쟁 시 비결정(둘 다 502). 첫 실패 시 in-flight 취소.
- tx 전파: 서블릿 스레드 MDC의 transactionId만 Reactor Context에 실어(`contextWrite`, null이면 생략) signal 로그 실행 순간에만 MDC 복원·원복(`TxContextLogging` 신설, `TransactionIds.MDC_KEY` 상수 사용).

## Plan Deviations

- concurrency 기본값을 계획의 5에서 **20으로 상향** — 사용자 결정(카카오 일 쿼터 여유·초당만 비공개, 429 시 `=1` 완화책 구조 유지). 관찰 게이트 스모크 값도 1/5 → 1/20으로 변경.
- `TxContextLogging`을 계획상 커밋 2가 아닌 커밋 1에 포함 — provider 로그가 커밋 1부터 이벤트루프 스레드에서 실행되므로 각 커밋 green 유지를 위해 앞당김.
- 그 외 `No material deviation was observed within the evidence scope.`

## Problems Encountered

`No material problem was observed within the evidence scope.` (병렬성 테스트를 포함한 전체 테스트가 각 커밋에서 첫 실행에 통과. 우려했던 MockWebServer 전환·Sinks 기반 결정론 검증 모두 재작업 없이 안착.)

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| Codex 계획 리뷰 3라운드(계획 단계) | first-observed 실패 명문화, MDC signal 시점 적용·원복, concurrency fail-fast, PHOTO 좌표 수집 금지, classifier 재래핑 금지, MockWebServer 채택 | accepted | 계획서 133-delightful-key.md에 반영된 것을 본 PR이 구현 |
| PR reviewer (suhyun444) | reactiveclient read-timeout의 커넥터 적용 경로가 회귀 검증되지 않음 — 지연 응답 + 짧은 timeout으로 focused test 추가 | accepted | d01fa79 — 계획의 YAGNI 판단을 번복: Boot 계약 테스트가 아니라 우리 배선 가정(raw builder 회귀·프로퍼티 rot) 가드라는 리뷰 프레이밍이 정확 |
| 사용자 | concurrency 기본 5 → 20 상향 | accepted | c98cc47 — 순차 대비 이득 확대, 429 시 하향이 즉시 완화책인 구조 불변 |
| 사용자 | micrometer context-propagation 대신 수제 TxContextLogging 유지 | accepted | 로그 2곳·키 1개 규모에 전역 훅+의존성은 과함(YAGNI). 리액티브 사용처·전파 키가 늘면 전환 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| provider 계약(질의어·재시도·IO 실패·shape·429) | MockWebServer 실 HTTP 루프백 테스트 | passed | ea8ee47~c98cc47 로컬 `./gradlew test` |
| 병렬 구독·bounded·first-observed 실패(양방향)·in-flight 취소·tx Context 전파 | Sinks/PublisherProbe 결정론 테스트(GeocodingServiceTest) | passed | 8713462 이후 로컬 실행 |
| MDC 복원 계약 3케이스(clear/원복/미접촉) | TxContextLoggingTest | passed | 8713462 |
| 수집 범위(STAY/MOVEMENT만·PHOTO 무호출·dedupe·encounter order)·1014/1015 매핑 | SourceItemEnrichmentServiceTest | passed | 8713462 |
| reactiveclient read-timeout → 커넥터 적용 | GeoWiringTest(auto-config 러너 + MockWebServer 지연 응답 → 2회 시도 후 retryable) | passed | d01fa79 |
| 전체 CI 게이트 | `./gradlew build` 로컬 + GitHub `build` check | passed (COMPLETED SUCCESS) | c98cc47, inspect_pr.py |
| webflux 공존 기동(SERVLET 판별) | `docker compose up -d && ./gradlew integrationTest` | passed | c98cc47 로컬 실행 |
| runtime dependency 추가 후 이미지 조립 | `docker build .` | passed | c98cc47 로컬 실행 |
| dev 실환경 병렬 효과·429 관찰 | 배포 후 스모크(concurrency 1/20) | not-run | 머지 후 관찰 게이트에서 수행 |

## Remaining Risks

- 카카오 초당 한도가 비공개라 기본 20이 안전한지 실환경 미검증 — dev 스모크에서 429 관측 시 `APP_GEO_LOOKUP_CONCURRENCY=1`(재기동 필요)이 즉시 완화책, transport 문제면 본 PR revert가 rollback(PR1 잔존 가능).
- 1014/1015 비결정(혼합 실패 경쟁)은 수용된 트레이드오프 — 클라 재시도 UX에 문제가 되면 재설계 필요.
- reactive 사용처가 늘면 TxContextLogging을 micrometer context-propagation으로 승격 검토.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope.

## Learning Candidates

- "Boot 계약이라 테스트 안 함(YAGNI)"으로 뺀 항목이라도, 리뷰가 "우리 배선 가정의 회귀 가드"로 재프레이밍하면 가치가 달라진다 — 무엇을 테스트하는가(라이브러리 vs 우리의 조립)를 기준으로 재평가할 것.
- 부분 리액티브 도입의 복잡성은 연산자 코드가 아니라 가장자리(MDC, 예외 래핑, 실패 순서 의미, block 경계)에 모인다 — 경계를 좁게 유지한 것이 유효했다.
