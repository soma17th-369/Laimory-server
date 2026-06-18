# Commit 전략

커밋 메시지는 **Commit Type**으로 시작하고, 콜론(`:`) 뒤에 어떤 작업을 했는지 간단히 적는다.

```
<type>: <간단한 작업 내용>
```

예: `feat: oauth 로그인 추가`, `fix: null AppConfig 응답 처리`, `refactor: AppConfigService 분리`

## Commit Type

| Commit Type | 설명 |
|---|---|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 수정 |
| `style` | 코드 포매팅, 세미콜론 누락 등 코드 동작에 영향 없는 변경 |
| `refactor` | 코드 리팩터링 (동작 변경 없음) |
| `test` | 테스트 코드 추가/리팩터링 |
| `chore` | 패키지 매니저 설정 등 기타 잡무 (예: `.gitignore`) |
| `design` | CSS 등 사용자 UI 디자인 변경 |
| `comment` | 필요한 주석 추가/수정 |
| `rename` | 파일·폴더 이름 변경 또는 위치 이동만 한 경우 |
| `remove` | 파일 삭제 작업만 한 경우 |

## 규칙

- type은 위 표의 값만 쓴다. 임의의 type을 만들지 않는다.
- type 뒤에는 콜론(`:`)을 붙이고, 한 칸 띄운 뒤 작업 내용을 적는다.
- 작업 내용은 무엇을 했는지 한 줄로 간결하게 적는다.
