# Commit 전략

커밋 메시지는 Commit Type으로 시작하고, 콜론(`:`) 뒤에 어떤 작업을 했는지 간단히 적는다.

```text
<type>: <간단한 작업 내용>
```

예: `feat: oauth 로그인 추가`, `fix: null AppConfig 응답 처리`, `refactor: AppConfigService 분리`

## Commit Type

| Commit Type | 설명 |
|---|---|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 수정 |
| `style` | 코드 동작에 영향 없는 포매팅 수정 |
| `refactor` | 코드 리팩터링(동작 변경 없음) |
| `test` | 테스트 코드 추가·리팩터링 |
| `chore` | 패키지 매니저 설정 등 기타 작업 |
| `design` | CSS 등 사용자 UI 디자인 변경 |
| `comment` | 필요한 주석 추가·수정 |
| `rename` | 파일·디렉터리 이름 변경 또는 이동만 한 작업 |
| `remove` | 파일 삭제만 한 작업 |

## 규칙

- type은 위 표의 값만 쓴다.
- type 뒤에 콜론과 공백 하나를 둔다.
- 작업 내용은 무엇을 했는지 한 줄로 간결하게 적는다.
