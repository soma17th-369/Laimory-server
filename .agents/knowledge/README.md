# Agent Knowledge Index

코드에서 바로 드러나지 않는 현재 동작, 계약, 불변식과 운영 제약을 coding agent가 필요할 때만 읽도록
연결하는 인덱스다. 코드·설정·스키마·워크플로가 항상 최종 권위다.

이 knowledge 구조는 commit `8f1907915c26fa16aefd1601be57909c2e63bd37`을 기준으로
2026-07-12에 작성했다. 구조 개편 중 HEAD가 달라지면 영향받은 단계부터 코드와 다시 대조한다.

## 사용법

1. 작업과 관련된 행만 골라 읽는다.
2. `Related paths`가 바뀌었다고 무조건 문서를 수정하지 않는다.
3. `Update when`의 의미 변화가 있을 때만 같은 변경에서 문서를 갱신한다.
4. 문서와 구현이 다르면 권위 원천을 확인하고 현재·의도·미구현을 구분해 문서를 교정한다.
5. 환경변수 이름은 기록할 수 있지만 secret이나 실제 credential 값은 복제하지 않는다.

## Router

| Page | Read when | Related paths | Update when | Authority | Validate with |
|---|---|---|---|---|---|
| [Codebase index](codebase/README.md) | 구현·설계·운영 작업을 시작할 때 | `src/`, `build.gradle`, 배포·인프라 | 하위 문서나 라우팅이 바뀔 때 | 하위 문서의 권위 원천 | 하위 문서별 검증 |
| [Ubiquitous language](domain/ubiquitous-language.md) | 개념·상태·필드·클래스 이름을 만들거나 바꿀 때 | domain model, DTO, API contract | 용어, 금지 표현, 구현 상태가 바뀔 때 | 실제 모델·DTO·계약 | 관련 unit/integration tests |
| [Domain invariants](domain/invariants.md) | timeline·auth·저장 흐름을 바꿀 때 | domain services, entities, schema, tests | 반드시 보존할 규칙이 추가·변경될 때 | 코드·스키마·테스트 | 관련 unit/integration tests |
| [Branch convention](conventions/branch.md) | 브랜치 생성·병합·PR 흐름을 다룰 때 | repository workflow | 브랜치 전략이 바뀔 때 | 팀 브랜치 정책 | 현재 branch·PR target 확인 |
| [Commit convention](conventions/commit.md) | 커밋을 만들거나 메시지를 작성할 때 | commit history | type·형식 정책이 바뀔 때 | 팀 커밋 정책 | `git log` 확인 |

## 포함하지 않는 것

- 현재 작업의 진행 로그, session memory, raw note
- 코드에서 쉽게 다시 읽을 수 있는 전체 클래스·필드 목록
- 검증되지 않은 목표 상태를 현재 구현처럼 쓴 설명
- secret, credential, 실제 토큰·키·presigned URL
- 사용처가 생기지 않은 빈 decision·runbook 디렉터리
