---
schema_version: 1
status: merge-candidate
pr_number: 169
pr_url: https://github.com/soma17th-369/Laimory-server/pull/169
title: "feat: 카카오 로그인 프로필 닉네임 수집 및 재로그인 갱신"
base_branch: dev
head_branch: feat/kakao-profile-nickname
implementation_head_sha: 1c6042e55619dcc28e05cd15f5b6da3cdd5ce54c
generated_at: 2026-07-19T22:35:00+09:00
linked_issues: [167]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #169 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: Kakao 로그인에서 프로필 닉네임을 수집해 `users.nickname`에 저장하고, 기존 Kakao 사용자는 재로그인 시 최신 닉네임으로 갱신한다.
- Acceptance criteria: Kakao scope `openid,profile_nickname`; 닉네임은 검증된 id_token `nickname` claim만 사용(UserInfo 미호출); 누락·blank·비문자열 claim은 null; 기존 사용자 재로그인 시 non-null 닉네임만 갱신하고 누락 claim은 기존 값 보존; Google 동작·`(provider, provider_user_id)` identity·무트랜잭션 unique 충돌 수렴 유지; JWT·App Code URL·로그에 claim 미노출; DB schema 무변경.
- Out of scope: Kakao `account_email`·`name`·`legal_name` 수집, `users.name`/`display_name` 컬럼, 이메일 기반 계정 병합, Google claim 매핑 변경, 닉네임 편집·프로필 조회 API, 운영 DDL·backfill.

## Change Summary

- Kakao OAuth scope를 `openid` → `openid,profile_nickname`으로 변경 (`application.properties`). `user-info-uri`는 계속 미설정 — UserInfo endpoint를 호출하지 않고 검증된 id_token claim만 사용한다.
- `OAuth2LoginSuccessHandler`: provider별 claim 매핑을 private nested record `ProviderProfile`의 exhaustive switch로 분리. Kakao는 email 항상 null(미수집 계약, 예상 밖 claim이 있어도 무시) + `nickname` claim(비문자열·blank는 null), Google은 기존 `getEmail()`/`getFullName()` 유지.
- `UserService.findOrCreate`: 기존 Kakao 사용자를 non-null 닉네임일 때만 `updateNickname` + `saveAndFlush`로 갱신. 누락 claim(null)은 동의 철회와 provider 응답 누락을 구분할 수 없어 기존 값을 보존한다. 동시 최초 로그인 unique 충돌 후 승자 재조회 경로에도 동일 갱신을 적용하며, 무트랜잭션 수렴 계약은 변경하지 않았다.
- `User`에 `updateNickname` 추가. schema·DTO·JWT·App Code 계약 무변경.
- knowledge 4문서(ubiquitous-language·invariants·authentication·external-integrations)에 닉네임 계약과 보존 정책 반영.

## Plan Deviations

- 계획(`.agents/plans/167-kakao-profile.md`, 2026-07-19 개정판) 대비 기능 범위 이탈 없음. 구현 후 사용자 요청으로 handler의 삼항 분기를 `ProviderProfile` record + switch로 리팩터링했다(계약 불변 — 기존 테스트 무수정 통과로 확인).
- 계획 7.3의 "scope 설정 검증" 항목은 self-review 코멘트 처리 과정에서 실 property binding을 태우는 `KakaoClientRegistrationTest`로 구체화했다.

## Problems Encountered

No material problem was observed within the evidence scope.

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| plan review (Claude) | 개정 계획의 핵심 전제(Kakao id_token에 `nickname` claim 포함, UserInfo 불필요) 1차 문서 검증 후 Ready | accepted | Kakao REST API 문서의 ID 토큰 페이로드 표에서 `nickname` 선택 claim과 필요 동의항목 확인 |
| user + Claude 설계 논의 | claim 매핑 위치를 커스텀 `OidcUserService`(Spring 확장점)가 아닌 handler 내 record로 유지 | accepted | principal 소비처가 성공 핸들러 1곳뿐(세션 즉시 소멸·자체 JWT 전환 구조)이라 principal 레벨 정규화의 수혜자가 없음; UserInfo 조회가 필요해지는 시점에 이사 |
| user | 삼항 분기 제거 — exhaustive switch + nested record 채택 | accepted | provider 추가 시 컴파일 단계에서 매핑 누락 강제; 레포 기존 관례(KakaoMapPlaceProvider.KakaoAddress 등)와 일치 |
| PR self-review thread (suhyun444) | Kakao 실 property binding·redirect scope 회귀 테스트 부재 지적 | accepted | `SecurityConfigTest`의 dummy registration이 오토컨피그를 back-off시켜 실 바인딩 미검증 — `KakaoClientRegistrationTest` 추가(1c6042e), thread resolve 완료 |
| Claude 리뷰 초안 (미게시) | 닉네임 동일 값 재로그인 시 save 생략 가드 [Nit] | rejected | 사용자가 게시·반영 불요 결정; merge가 dirty check로 UPDATE를 생략해 실측 영향은 추가 SELECT 1회 수준 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| Kakao claim 추출(닉네임 전달, 예상 밖 email 무시, 누락/blank/비문자열 null) | `OAuth2LoginSuccessHandlerTest` unit | passed | 1c6042e |
| Kakao 재로그인 갱신·누락 보존·Google 무갱신·unique 충돌 승자 갱신 | `UserServiceTest` unit | passed | 1c6042e |
| 실 application.properties 바인딩과 authorization redirect scope(`openid profile_nickname`) | `KakaoClientRegistrationTest` (@WebMvcTest, dummy registration 없음) | passed | 1c6042e |
| 전체 단위 회귀·빌드 | `./gradlew test build` | passed | 1c6042e 로컬 실행 |
| 통합 회귀(실 MySQL·Redis) | `docker compose up -d && ./gradlew integrationTest` | passed | fe92513 로컬 실행 (이후 커밋은 프로덕션 계약 불변 리팩터·테스트 추가) |
| CI `build` | GitHub Actions | passed | https://github.com/soma17th-369/Laimory-server/actions/runs/29689704670/job/88200143264 (1c6042e) |
| 실 Kakao 계정 동의 화면·신규/재로그인 smoke | dev 배포 후 수동 | not-run | 배포 후 수행 예정 |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- 배포 전 Kakao 콘솔에서 `profile_nickname` 동의항목 활성 상태 재확인 필요(dev/prod 동일 앱 — 사용자 확인). 미활성 상태로 배포하면 Kakao 로그인 시작이 KOE 에러로 실패한다.
- 실 Kakao 계정 smoke test(동의 화면 노출, 신규 저장, 닉네임 변경 후 재로그인 갱신, 동의 철회 시 보존)는 dev 배포 후 수동 검증으로 남아 있다.
- scope 추가로 기존 Kakao 사용자는 다음 로그인에서 추가 동의 화면을 본다(1회성, 예상된 동작).
- rollback은 scope·코드 원복만으로 충분하다(schema 변경 없음).

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- `@WebMvcTest` slice에서 dummy `ClientRegistrationRepository` 빈은 OAuth2 client 오토컨피그를 back-off시켜 실 property binding을 검증에서 제외한다 — 설정이 완료 조건인 변경은 env placeholder만 채운 실 바인딩 테스트로 고정하는 패턴이 재사용 가능하다.
- provider별 OIDC claim 매핑은 필드별 분기(parallel conditionals) 대신 provider당 한 번 dispatch하는 exhaustive switch + record로 고정하면 provider 추가 누락을 컴파일 단계에서 잡는다.
