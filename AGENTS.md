# AGENTS.md

이 파일은 이 저장소에서 코드를 구현하거나 문서를 변경하는 모든 coding agent의 단일 작업 규칙이다.
`CLAUDE.md`는 별도 규칙을 갖지 않고 이 파일을 가리킨다.

## Project

Laimory는 Android 앱이 사용하는 Spring Boot REST 백엔드다.

## Always-on Rules

- 요구 해석이 여러 가지면 임의로 확정하지 말고 불확실성과 선택지를 밝힌다.
- YAGNI를 지키고 요청 범위에 필요한 변경만 한다. 미요청 추상화·설정·일반화를 추가하지 않는다.
- 기존 구조와 스타일을 따르는 수술적 변경을 한다. 이번 변경이 만든 미사용 코드만 정리한다.
- 작업을 검증 가능한 목표로 바꾸고, 위험에 비례한 테스트와 검사를 수행한다.
- 코드·설정·스키마·워크플로가 권위 원천이다. 문서가 다르면 현재 구현을 확인해 문서를 고친다.
- secret, credential, 실제 토큰·키 값을 문서·로그·예시에 복제하지 않는다.

## Knowledge Workflow

1. 구현 전에 [knowledge index](.agents/knowledge/README.md)에서 변경과 관련된 문서만 찾고 읽는다.
2. 도메인 개념·상태·필드·클래스 이름을 만들거나 바꿀 때는
   [ubiquitous language](.agents/knowledge/domain/ubiquitous-language.md)를 따른다.
3. 현재 구현, 의도된 계약, 알려진 미구현을 섞지 않는다.
4. 코드 수정 후 변경 경로와 knowledge index의 `Related paths`·`Update when`을 대조한다.
5. 의미가 달라진 knowledge 문서만 같은 변경에서 갱신한다. 단순 포맷 변경 때문에 문서를 억지로 수정하지 않는다.
6. 세션 기록, 작업 일지, raw note를 knowledge에 저장하지 않는다. 재사용 가능한 현재 상태와 불변식만 남긴다.

## Plan Workflow

- 구현 전 계획은 `.agents/plans/`의 작업별 Markdown 문서 하나에 작성·갱신한다.
- 계획 단계에서는 조사와 계획 문서 수정만 하며 저장소 파일은 변경하지 않는다.
- 계획 승인만으로 구현하지 않는다. 사용자가 명시적으로 구현을 지시한 뒤 `Git and Issues` 규칙에 따라 작업한다.
- 계획 문서는 로컬 메모로만 두며 Git·knowledge에 포함하지 않는다.

## Local Skills

작업에 맞는 `.agents/skills/<skill-name>/SKILL.md`가 있으면 전체를 읽고 그 절차를 따른다.
항상 적용되는 원칙은 이 파일이, 상세한 코드베이스 사실은 knowledge 문서가 소유한다.

## Git and Issues

- 브랜치 작업 전 [branch convention](.agents/knowledge/conventions/branch.md)을 읽는다.
- 커밋 전 [commit convention](.agents/knowledge/conventions/commit.md)을 읽는다.
- 단일 파일의 사소한 수정·간단한 버그 수정을 넘어서는 작업(새 컴포넌트나 의존성, 여러 파일 변경,
  리팩터링)은 착수 전에 `create-issue` 스킬로 GitHub issue를 등록하고 PR에 `Closes #N`을 연결한다.
- issue 생성이 인증·권한 때문에 불가능하면 그 사실을 사용자에게 알리고 진행 여부를 확인한다.
  임의의 issue 번호를 만들지 않는다.

## Verification

- 가장 좁고 관련성 높은 검증부터 실행하고 필요하면 `./gradlew test`로 넓힌다.
- 통합 테스트는 로컬 MySQL·Redis가 필요한 경우
  `docker compose up -d && ./gradlew integrationTest`로 실행한다.
- 존재하지 않는 lint·format task를 만들어내지 않는다.
- 구체적인 실행법과 CI 범위는
  [testing knowledge](.agents/knowledge/codebase/operations/testing.md)를 따른다.
