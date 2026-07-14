---
schema_version: 1
status: merge-candidate
pr_number: 147
pr_url: https://github.com/soma17th-369/Laimory-server/pull/147
title: "feat: 지오코딩 장소 검색을 category 5콜에서 주소 keyword 1콜로 전환"
base_branch: dev
head_branch: feat/geo-keyword-place-search
implementation_head_sha: 53832be5af93e6252316f37ee4998c8cc7700cc3
generated_at: 2026-07-14T03:50:00Z
linked_issues: [133]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #147 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 지오코딩 place enrich의 장소 검색을 category 5콜 순회에서 주소 keyword 검색 1콜로 전환해 좌표당 외부 호출을 6콜에서 정상 2콜(전이 재시도 포함 최대 4회 요청)로 줄이고, 카테고리 그룹 코드(FD6/CE7/CT1/AT4/AD5) 밖 업종도 포착한다.
- Acceptance criteria: coord2address가 준 주소(도로명 우선, 지번 fallback)를 질의어로 `keyword.json` 1콜(`radius=50`, `sort=distance`) 호출. 주소 없는 좌표는 keyword 콜 생략(places 빈 배열, 정상). 실패 계약(strict loud-fail, 콜 단위 재시도, 전이 5xx만 MAX_ATTEMPTS=2, 4xx 즉시 영구) 불변. `./gradlew build` 통과.
- Out of scope: WebClient/Reactor 전환과 좌표 간 병렬화(후속 PR2, #133 잔여), dev 배포 후 표본 좌표 관찰 게이트.

## Change Summary

- `KakaoMapPlaceProvider`: `PLACE_CATEGORY_GROUP_CODES` 상수·category 5콜 for 루프 삭제 → `fetchNearbyPlaceNames(query, lat, lng, calls)`가 keyword 1콜로 장소 조회. 단일 콜이라 응답 `sort=distance` 순서를 신뢰해 distance 파싱·전역 병합 정렬·id dedupe(내부 `Place` record, Comparator, HashSet) 제거. `lookup`은 주소가 null이면 keyword 콜을 생략하고 `List.of()` 사용. 재시도 래퍼 `fetchDocuments`는 무변경 재사용(endpoint 상수만 `category`→`keyword`).
- 문서/주석: "좌표당 6콜" 서술을 "정상 2콜(전이 재시도 포함 최대 4회 요청)"로 정정(provider Javadoc, enrichment service/test 주석, `external-integrations.md`). "같은 주소(건물)의 입주 장소" 표현을 "주소 keyword + 반경 50m 매칭의 실측 관찰 기반 휴리스틱"으로 완화(카카오 공식 문서는 질의어 매칭·반경 제한까지만 보장).
- 외부에서 보이는 동작 변화: places에 기존 5개 카테고리 밖 업종(리테일·병원 등)이 포함될 수 있고, 인접 건물(50m 내 다른 주소) 장소는 더 이상 포함되지 않는다. 광역 지번 좌표는 radius=50 필터로 places가 빈 결과로 수렴할 수 있다(확정 트레이드오프).

## Plan Deviations

- dev pull 시 provider가 `@Slf4j`(PR #145 lombok 전환)로 바뀌어 있어 계획서의 명시적 Logger 선언 전제와 달랐으나, 현재 코드 기준으로 적용해 실질 편차 없음.
- 그 외 `No material deviation was observed within the evidence scope.`

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 구현 착수 | 계획 수립 시 읽은 provider와 디스크 상태 불일치(첫 Edit에서 "modified on disk" 경고) | confirmed (dev에 머지된 #145 lombok @Slf4j 전환) | 파일 재읽기 후 현재 상태 기준으로 편집 | Edit 도구 경고 및 재읽기 결과 |

우려했던 MockRestServiceServer 한글 query URL 템플릿 인코딩 매칭 문제는 발생하지 않았다(테스트 첫 실행부터 통과).

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| 사용자(멘토 자문 반영) | category 방식 완전 대체(하이브리드·병행 아님) | accepted | 실측: 건물 지번도 도로명과 동급 품질, 광역 지번 정크는 radius=50으로 빈 결과 수렴 — 인접 건물 장소 포기 트레이드오프 수용 |
| PR reviewer (suhyun444) | "같은 주소(건물) 입주 장소"는 카카오 보장 계약이 아니라 관찰 기반 휴리스틱 — 표현 완화 | accepted | 공식 문서는 질의어 매칭·반경 제한까지만 설명. Javadoc·knowledge·PR 본문 정정(a69bcec, 53832be) |
| PR reviewer (suhyun444) | "최대 2콜"은 재시도 제외 기준 — "정상 2콜, 재시도 포함 최대 4회 요청"으로 정합화 | accepted | fetchDocuments가 콜당 최대 2회 시도하므로 좌표당 최대 4회 요청이 정확. #133 QPS 산정 기준으로도 명시 |
| Codex 계획 리뷰(3라운드, 계획 단계) | PR2 설계 보강(첫 관측 실패 분류, MDC signal 시점 전파, PHOTO 좌표 수집 금지 등) | accepted | 본 PR 범위 밖 — 계획서 133-delightful-key.md에 반영되어 PR2에서 적용 예정 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| keyword 전환 계약(질의어 선택·주소 없음 스킵·재시도·shape 오류) | `./gradlew test --tests 'com.laimory.server.geo.*'` | passed | bd143c6 및 53832be 로컬 실행 |
| enrichment dedupe·예외 강등 불변 | `./gradlew test --tests '...SourceItemEnrichmentServiceTest'` | passed | bd143c6 및 53832be 로컬 실행 |
| 전체 CI 게이트 | `./gradlew build` 로컬 + GitHub `build` check | passed (COMPLETED SUCCESS) | 53832be, inspect_pr.py 결과 |
| keyword 검색 실응답 품질(스타필드 하남·AK& 기흥·광역 지번) | 카카오 REST API 직접 호출(curl) 실측 | passed | #133 이슈 본문에 기록된 실측 결과 |
| dev 실환경 표본 좌표 품질 | dev 배포 후 관찰 게이트 | not-run | 머지 후 수행 예정(계획서 관찰 게이트 절) |

## Remaining Risks

- 인접 건물(50m 내 다른 주소) 장소가 places에서 빠지는 품질 변화는 dev 관찰 게이트에서 표본(도로명/지번/주소 없음/복합건물)으로 확인 필요.
- 광역 지번 좌표의 places 빈 결과 수렴은 의도된 트레이드오프이나 실사용 빈도는 미관측.
- keyword 검색 랭킹이 주소 매칭을 우선한다는 보장은 카카오 계약이 아니므로(휴리스틱), 회귀 시 category 방식 복원이 rollback 경로(이 PR revert).

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: 첫 Edit에서 디스크 상태 불일치 경고 1건(재읽기로 해소), 그 외 not observed within the evidence scope.

## Learning Candidates

- 외부 API의 관찰된 동작(건물 입주 장소 매칭)을 계약처럼 문서화하지 말 것 — 공식 문서가 보장하는 범위(질의어+반경)와 실측 휴리스틱을 구분해 서술.
- "최대 N콜" 표현은 재시도 포함 여부를 항상 명시할 것(QPS/rate limit 산정의 입력이 됨).
