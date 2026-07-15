---
schema_version: 1
status: merge-candidate
pr_number: 153
pr_url: https://github.com/soma17th-369/Laimory-server/pull/153
title: "refactor: SecurityFilterChain @Order를 간격 배치(100, 200)로 변경"
base_branch: dev
head_branch: refactor/security-chain-order-gap
implementation_head_sha: 52ff7aba04f5690b65033f7bfef7eadf08a2d0c7
generated_at: 2026-07-15T04:36:54Z
linked_issues: []
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #153 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 두 `SecurityFilterChain`의 `@Order` 값을 (1, 2)에서 (100, 200)으로 바꿔, 나중에 새 체인을 기존 값 수정 없이 사이·앞에 끼워 넣을 여지를 확보한다.
- Acceptance criteria: 체인 상대 순서(OAuth 핸드셰이크 체인 → API 체인) 유지, 동작 변경 없음, 전체 단위 테스트 통과.
- Out of scope: `TransactionIdFilter`의 `Ordered.HIGHEST_PRECEDENCE`는 의도적 극단값("모든 요청의 최전방" 불변식 선언)이므로 변경하지 않는다.

## Change Summary

- `OAuth2LoginSecurityConfig.oauth2LoginFilterChain`: `@Order(1)` → `@Order(100)`.
- `SecurityConfig.apiFilterChain`: `@Order(2)` → `@Order(200)`. 같은 파일 javadoc의 `@Order(1)` 언급도 `@Order(100)`으로 갱신.
- 외부 가시 동작 변경 없음 — 체인 매칭은 상대 순서만 사용하며 상대 순서는 동일하다.

## Plan Deviations

- AGENTS.md 기준(여러 파일 변경)에 따라 GitHub issue 등록을 시작했으나, 사용자가 "이슈 등록 필요없어보여"로 명시 거절하여 이슈 없이 진행했다. `linked_issues`가 빈 것은 이 결정의 결과다.

## Problems Encountered

No material problem was observed within the evidence scope.

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| human (사용자) | 순번식(1, 2) 대신 간격 배치(100, 200)가 잘 만든 방식이라는 제안 | accepted | 체인 order 공간은 이 저장소가 소유하고 값이 임의라 간격 배치의 전제가 성립 — 삽입 시 기존 값 무수정 |
| Claude | `TransactionIdFilter`의 `HIGHEST_PRECEDENCE`는 공유 공간(서블릿 필터, Boot 고정점 존재)의 의미 선언이므로 간격 배치 대상이 아님 | accepted | 여유를 두면 라이브러리가 조용히 앞에 끼어들어 tx-id 불변식이 리뷰 없이 깨질 수 있음; 사용자가 "그대로 가져가는게 좋겠네"로 수용 |
| human (사용자) | 이번 변경은 GitHub issue 없이 진행 | accepted | 사용자 명시 결정(AskUserQuestion 응답) |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 보안 체인 구성 동작 보존 | `./gradlew test --tests "com.laimory.server.config.SecurityConfigTest"` | passed | 52ff7aba (BUILD SUCCESSFUL) |
| 전체 단위 테스트 회귀 없음 | `./gradlew test` | passed | 52ff7aba (BUILD SUCCESSFUL) |

The final check for the digest commit is intentionally not recorded here because changing this file would create another head SHA. Verify it on the GitHub PR before merging.

## Remaining Risks

- No remaining risk was identified within the evidence scope. 상대 순서가 보존되는 상수 변경이며 통합 테스트가 필요한 외부 의존 변화는 없다.

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: not observed within the evidence scope

## Learning Candidates

- order/우선순위 값 설계 기준: 저장소가 소유한 임의 값 공간(예: SecurityFilterChain order)은 간격 배치(100, 200)로 삽입 여지를 남기고, 제3자 고정점과 공유하는 공간의 의미 선언(예: `HIGHEST_PRECEDENCE` 최전방 필터)은 극단값을 유지한다 — conventions knowledge 문서로 승격할지 검토 가치.
