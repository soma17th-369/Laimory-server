---
schema_version: 1
status: merge-candidate
pr_number: 143
pr_url: https://github.com/soma17th-369/Laimory-server/pull/143
title: "refactor: ExceptionType/ErrorCode 분리(N:1)로 로그 레벨 SSOT 이관 + access 로그 record 직렬화"
base_branch: dev
head_branch: refactor/exception-type-log-level
implementation_head_sha: f136a16d0b5b4a8611682e3deb9b731a131eff78
generated_at: 2026-07-13T11:10:00+09:00
linked_issues: [142]
evidence_scope:
  - current-conversation
  - local-git
  - github-pr
---

# PR #143 Digest

> This document describes the implementation before its own digest commit. The final post-digest CI result and squash merge commit remain authoritative in GitHub.

## Goal

- Purpose: 멘토 코드리뷰 피드백 반영 — ① access 로그 필드의 kv() 배열+placeholder 이중 관리 제거, ② HTTP status 기반 로그 레벨 결정(5xx ERROR/4xx WARN) 제거.
- Acceptance criteria: 새 에러/내부 사유 추가가 enum 엔트리 추가만으로 가능(기존 로직 무수정), 필드 추가가 record 한 곳 수정으로 완결(컴파일 강제), 레벨은 `ExceptionType.logLevel()`이 SSOT, 기존 클라이언트 계약(코드·status·메시지) 무변경, 전체 테스트 통과.
- Out of scope: 요청/응답 body 캡처 구현(정책 문구만 개정, 구현은 후속 이슈), Logstash 등 파이프라인 구성 변경.

## Change Summary

- `ExceptionType` enum 신설: 내부 실패 사유 카탈로그, access 로그 레벨의 SSOT. `ErrorCode`(클라이언트 계약: 코드명·HTTP status·i18n 메시지 key)와 **N:1 매핑** — 같은 코드라도 내부 사유·심각도가 다를 수 있다(ERROR_2002·2003·0400에서 즉시 사용). message·status는 per-code 일관성이 필요해 ErrorCode 축에 유지.
- `BusinessException`은 `ExceptionType`을 받도록 교체(`getErrorCode()`는 위임 유지 — 기존 테스트 단언 호환). src/main throw 지점 15곳 치환.
- `GlobalExceptionHandler`: 개별 진단 로그 라인 제거, `ExceptionType`+`errorDetail`을 request attribute로 심어 access 로그 1줄에 합류. stacktrace는 catch-all과 MVC 처리 5xx만(둘 다 필터가 예외 객체를 못 보는 경로). 폴백 경로는 framework status 보존(`ExceptionType.fromStatus`).
- `TransactionIdFilter`: kv() 배열+placeholder 5분기 제거 → `HttpRequestLog` record(정적 팩토리 `of()`) + `StructuredArguments.fields()` 단일 직렬화 + `log.atLevel()`. 신규 로그 필드 `exceptionType`·`errorDetail`.
- `ExcludedPaths` 신설(별도 파일 단일 관리): 등재 기준 "정상 완료가 아무 정보도 담지 않는 트래픽"(/status·favicon). 정상 완료만 로그 생략, 에러·미처리 예외는 경로 무관하게 남음. 기존 QUIET(DEBUG 강등) 개념 폐기.
- `LogSanitizer` 신설: 외부 유입 자유 문자열의 CR/LF 제거+길이 상한. `errorEnvelope` 단일 조립 지점(200자)과 PhotoUploadService contentType 로그(100자)에 적용. ES template `errorDetail`에 `ignore_above: 256` 이중 방어.
- 로깅 민감정보 정책 개정(observability.md): "body 로그 금지" 폐기 → "요청·응답 적극 로깅, 금지는 토큰·비밀번호·presigned URL·세션 값만". knowledge 문서(api.md·observability.md) 갱신 + access 로그 필드 롤아웃 SSM runbook 신설.

## Plan Deviations

- 승인된 플랜의 "TransactionIdFilterTest에서 quiet(DEBUG) 강등 검증" 부분은 구현 중 사용자 지시로 설계가 바뀌었다 — quiet 개념 자체를 폐기하고 `ExcludedPaths` 단일 관리로 대체(해당 테스트는 제외 계약 검증으로 교체).
- 1라운드 플랜 리뷰에서 수용했던 "IAE errorDetail=클래스명 고정"은 로깅 정책 개정(사용자 결정)으로 번복 — raw message 유지 + LogSanitizer 정화로 대체(PR 리뷰 Blocker 대응과 함께).
- 문서 갱신 대상이 CLAUDE.md에서 knowledge 문서로 이동 — 작업 도중 dev에 문서 구조 개편(#141)이 머지되어 CLAUDE.md가 AGENTS.md 심볼릭 링크로 바뀜.

## Problems Encountered

| Stage | Symptom and impact | Cause status | Resolution | Evidence |
|---|---|---|---|---|
| 구현 중 | TransactionIdFilter·GlobalExceptionHandlerTest가 세션 내 편집과 병행 변경(#138 Transaction-Id 헤더)으로 충돌 | confirmed | 현재 버전 재독 후 헤더 로직 보존하며 재작성 | 로컬 diff·전체 테스트 통과 |
| dev 머지 | knowledge 개편(#141) 머지로 PR이 CONFLICTING — CLAUDE.md 파일 타입 충돌(일반 파일 vs 심볼릭 링크) | confirmed | dev 쪽(심볼릭 링크) 채택, 정책 문구는 knowledge 문서로 이관 | merge commit cbddc9f, 머지 후 전체 테스트 통과 |
| 테스트 | TimelineControllerTest에서 ErrorCode의 데이터 어휘 사용처(폴링 failed 응답) import 누락으로 컴파일 실패 | confirmed | ErrorCode import 복원(데이터 어휘와 throw 어휘 공존 확인) | compileTestJava 실패 → 수정 후 통과 |

## Review Decisions

| Source | Decision | Disposition | Reason |
|---|---|---|---|
| 외부 플랜 리뷰(1R) | MVC/RSE 경로의 status 보존 vs ErrorCode.status SSOT 모순 지적 | accepted | framework status 보존 확정, SSOT 범위를 BusinessException 경로로 한정(수술적 변경 원칙) — spring-webmvc 6.2.14 jar에서 406/503/500 처리 직접 확인 |
| 외부 플랜 리뷰(1R) | logLevel SSOT와 서비스 진단 WARN 충돌 | accepted | SSOT 범위를 "access 완료 로그 레벨"로 한정, 독립 이벤트 5곳 명시·유지 |
| 외부 플랜 리뷰(2R) + 사용자 | IAE raw message의 로그 반영은 민감정보 위반 → 이후 정책 개정 | modified | 정책이 "적극 로깅+진짜 비밀만 금지"로 개정되어 1R 판정(클래스명 고정) 번복, raw message 유지 |
| PR 리뷰어(Blocker) | errorDetail 무제한이면 Lucene 32,766B term 한도 초과 시 access 로그 문서 전체 ES 거부 | accepted | LogSanitizer 중앙 정화(200자·CR/LF) + ignore_above 256 이중 방어 + 라이브 ES 재적용 |
| PR 리뷰어 | contentType 로그의 CR/LF·길이 위험 | modified | 반영하되 근거 정정: 위조 각도는 JSON encoder escape로 배포 환경에서 무력화, 크기·로그 침식 방지로 수용 |
| PR 리뷰어 | N:1 타입 단언 부재(코드 단언만으로 타입 회귀 통과) | accepted | AppCode 2분기·Refresh 2경로에 getExceptionType 단언 추가 |
| 사용자 | ExceptionType에 message·status를 두지 않음(멘토 원형과 상이) | accepted | N쪽에 두면 같은 코드의 내부 구분이 응답(문구·status)으로 유출 — per-code 일관성 필드는 코드 축에 |

## Verification

| Target | Method | Result | Evidence SHA or source |
|---|---|---|---|
| 전체 회귀(316 tests) | `./gradlew test --rerun-tasks` | passed | f136a16 로컬 실행, failures=0 errors=0 |
| JSON 스키마(top-level 전개·숫자 타입) | LogstashEncoder 실출력 파싱 테스트 | passed | TransactionIdFilterTest.completionLog_jsonEncoderExpandsRecordToTopLevelFields |
| N:1 계약(2002·2003 두 쌍) | ExceptionTypeTest + 서비스 테스트 타입 단언 | passed | f136a16 |
| fromStatus 폴백(406/503/500 포함) | 매핑 테이블 파라미터라이즈드 테스트 | passed | ExceptionTypeTest |
| 핸들러 미경유 직접 응답 경로 | AppChallengeFilter+TransactionIdFilter 체인 테스트 | passed | AppChallengeFilterTest |
| errorDetail 정화(CR/LF·200자) | 핸들러 슬라이스 회귀 테스트 | passed | GlobalExceptionHandlerTest.illegalArgument_detailIsSanitized_noCrlfAndBounded |
| 라이브 ES template·mapping(ignore_above 포함) | SSM으로 template PUT + 기존 인덱스 _mapping PUT 후 매핑 조회 | passed | SSM invocation(2회), "errorDetail":{"type":"keyword","ignore_above":256} 확인 |
| implementation head CI | GitHub Actions build | passed | f136a16 이전 head까지 green, f136a16 build는 머지 전 GitHub에서 확인 |

## Remaining Risks

- 배포 후 Kibana에서 신규 필드(exceptionType·errorDetail)의 실데이터 전개 확인 필요(terraform/README E2E 절차) — 배포 전엔 검증 불가.
- 요청/응답 body 캡처는 정책만 개정되고 미구현 — 후속 이슈로 추적.
- `EXCLUDED_PATHS`·`fromStatus` 열거는 확장 시 상수/분기 추가가 필요한 지점(데이터화 트리거를 코드 주석·knowledge에 기록).

## Observed Execution Signals

- Exact tool-call count: unavailable
- Exact failed tool-call count: unavailable
- Material failures that affected the implementation: compileTestJava 1회 실패(ErrorCode import 누락 — 즉시 수정), Skill 레지스트리의 세션 중 리네임 미반영으로 스킬 직접 로드 2회.

## Learning Candidates

- 클라이언트 채널(코드·status·메시지)의 per-code 일관성은 규칙이 아니라 필드 배치(1쪽 소유)로 강제한다 — N:1 매핑 설계 시 재사용 가치.
- keyword 매핑 필드에 외부 유입 문자열을 넣을 때는 소스 정화 + `ignore_above` 이중 방어를 기본값으로 — 문서 전체 거부는 조용히 발생한다.
- ES 스키마와 record 컴포넌트의 동기화는 코드 밖 계약 — record 필드명과 template 키를 대조하는 빌드 테스트 후보(미채택).
