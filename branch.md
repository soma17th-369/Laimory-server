# Branch 전략

3단계 브랜치로 운영한다.

## 영구 브랜치

- **`prod`** — 운영 배포 브랜치. 직접 커밋 금지. `dev`에서 검증된 변경만 머지한다.
- **`dev`** — 통합/개발 브랜치. 모든 작업 브랜치가 여기로 머지된다.

## 작업 브랜치

`dev`에서 분기하며, 접두사로 작업 목적을 구분한다.

| 접두사 | 용도 |
|---|---|
| `feat/<name>` | 기능 추가 |
| `refactor/<name>` | 리팩터링 (동작 변경 없음) |
| `fix/<name>` | 버그 수정 |

예: `feat/oauth-login`, `refactor/appconfig-service`, `fix/null-config`

## 흐름

```
작업 브랜치 (feat/refactor/fix)
   │  PR
   ▼
  dev   ← 통합 및 검증
   │  검증 완료 후 머지
   ▼
 prod   ← 운영 배포
```

## 규칙

- 작업 브랜치는 항상 최신 `dev`에서 분기한다.
- `prod`, `dev`로는 직접 push하지 않고 **PR을 통해** 머지한다.
- 작업 브랜치는 머지 후 삭제한다.
