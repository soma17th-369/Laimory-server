# 용어 사전

## Scope

Laimory의 도메인 용어와 사용 금지 표현의 단일 기준이다.
상태는 `현재 구현`, `부분 구현`, `미구현`, `목표 계약`으로 구현 현실을 구분한다.

## Read When

도메인 개념·상태·필드·API·클래스·변수 이름을 만들거나 바꿀 때 읽는다.

## Authoritative Sources

- timeline/auth/user의 entity, enum, DTO, service와 tests
- `src/main/resources/db/schema.sql`
- `*Api.java`, security/OpenAPI config
- [Timeline draft runtime](../codebase/runtime/timeline-draft.md)
- [Authentication runtime](../codebase/runtime/authentication.md)
- [AI contract](../codebase/interfaces/ai-contract.md)

## 일일 기록

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 일일 기록 | Daily Record | 현재 구현 | 한 사용자의 특정 날짜 기록이다. `user_id + record_date`는 유일하다. |
| 기록 날짜 | Record Date | 현재 구현 | 정오를 날짜 경계로 계산한 일일 기록의 대상 날짜다. 요청 timezone은 검증·저장하지만 현재 경계 계산에는 사용하지 않는다. |
| 하루 감정 | Emotion Type | 부분 구현 | 하루 전체의 5단계 감정 enum과 nullable DB 필드는 있다. draft에서는 NULL이며 사용자가 설정하는 save 흐름은 아직 없다. 이벤트별 감정은 없다. |
| 작성중 | Draft | 현재 구현 | AI finalize 후 생성되거나 사용자가 아직 편집 중인 일일 기록 상태 `DRAFT`다. |
| 작성완료 | Saved | 부분 구현 | `SAVED` enum과 append·Event 수정·메모·삭제(Event/DailyRecord) 거부는 구현돼 있다. 사용자가 `DRAFT→SAVED`로 전환하는 API는 없다(도입 시 같은 날짜 guard를 취득해야 한다). |

## 타임라인 이벤트

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 타임라인 이벤트 | Timeline Event | 현재 구현 | 사용자에게 보이는 하루 타임라인의 이벤트 단위다. |
| 제목 | Title | 현재 구현 | 이벤트의 대표 문구다. suggestion staging에서 만들어질 수 있다. |
| 부제목 | Subtitle | 현재 구현 | 이벤트의 보조 설명이다. nullable이다. |
| 메모 | Memo | 현재 구현 | 사용자가 이벤트에 남기는 텍스트다. PUT 단일 endpoint로 작성·수정·제거한다 — null·blank·필드 부재는 제거, 그 외는 trim 없이 원문 저장(최대 10,000자). |
| 이벤트 시작 시각 | Start At | 현재 구현 | 이벤트 시간 범위의 시작이다. 필수이며 읽을 때 정렬 기준이다. |
| 이벤트 종료 시각 | End At | 현재 구현 | 이벤트 시간 범위의 끝이다. 단일 시점이면 nullable이다. |

## 타임라인 아이템

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 타임라인 아이템 | Timeline Item | 현재 구현 | 채택된 draft source item을 최종 이벤트 아래 저장한 값이다. `rawId`를 보존한다. |
| 아이템 타입 | Item Type | 현재 구현 | `PHOTO`, `CALENDAR`, `STAY`, `MOVEMENT`, `HEALTH`, `NOTIFICATION` 중 하나다. DB `item_type`이 권위 필드다. |
| 아이템 시작 시각 | Start At | 현재 구현 | 아이템이 발생한 시작 시각이다. |
| 아이템 종료 시각 | End At | 현재 구현 | 기간형 아이템의 종료 시각이며 단일 시점이면 nullable이다. |
| 페이로드 | Payload | 현재 구현 | HTTP 입력·enrich에서는 sealed `TimelineItemPayload` subtype을 사용한다. staging/final DB와 response pass-through에서는 `JsonNode` JSON을 사용한다. |

## 소스 아이템

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 소스 아이템 | Source Item | 현재 구현 | Android가 보낸 draft 입력 개념이다. `SourceItemDto`는 비-entity 입력 표현이고, 서버는 이를 `TimelineDraftSourceItem` staging entity로 저장한다. |
| 소스 아이템 ID | Source Item ID | 현재 구현 | `timeline_draft_source_items.timeline_draft_source_item_id` PK다. AI는 source association UPDATE에 쓰고 서버 assembler가 내부 `itemIds`를 조립한다. callback body에는 없다. |
| 원본 데이터 ID | rawId | 현재 구현 | 클라이언트 원본 식별자다. payload 밖 `raw_id` column에 저장해 dedupe한다. UUIDv7은 client convention이며 서버는 blank와 길이만 검증한다. 유일 constraint는 없다. |
| 채택된 소스 아이템 | Accepted Source Item | 현재 구현 | 같은 task의 event suggestion과 유효하게 연결된 staging source item이다. finalize 때만 Timeline Item이 된다. |
| 누락된 소스 아이템 | Omitted Source Item | 현재 구현 | staging association이 NULL인 source item이다. MVP에서는 최종 item으로 저장하지 않는다. |

## Payload 타입

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 아이템 페이로드 | Timeline Item Payload | 현재 구현 | HTTP input/enrich에서 쓰는 sealed payload 공통 interface다. |
| 사진 페이로드 | Photo Payload | 현재 구현 | `filename`, `clientPhotoUri`, 좌표, 설명과 서버가 만든 `photoUrl`을 담는다. client의 `photoUrl`은 무시한다. |
| 일정 페이로드 | Calendar Payload | 현재 구현 | 일정 제목, 위치 텍스트, 설명, 종일 여부를 담는다. |
| 머문 곳 페이로드 | Stay Payload | 현재 구현 | 필수 좌표와 서버 파생 주소·주변 장소·머문 시간 텍스트를 담는다. |
| 이동 페이로드 | Movement Payload | 현재 구현 | `start`/`end` endpoint, `transports`, `distanceMeters`를 담는다. |
| 이동 끝점 | Movement Endpoint | 현재 구현 | 출발·도착의 필수 좌표와 서버 파생 주소·주변 장소를 담는 중첩 값이다. |
| 이동수단 | transports | 현재 구현 | 단일 문자열 이동수단 분류다. |
| 주소 | address | 현재 구현 | 서버가 reverse geocoding한 nullable 주소다. 도로명 우선, 없으면 지번이며 client 값은 무시한다. |
| 주변 장소 목록 | places | 현재 구현 | 거리순 장소명 배열이다. NULL은 noop으로 JSON key 생략, 빈 배열은 장소 없음이다. client 값은 무시한다. |
| 머문 시간 텍스트 | durationText | 현재 구현 | 서버가 `startAt/endAt`으로 계산한 텍스트다. client 값은 받지 않는다. |
| 건강 페이로드 | Health Payload | 현재 구현 | 지표와 단위 포함 text `value`를 담는다. 측정 구간은 item envelope에 있다. |
| 건강 지표 | Health Metric | 현재 구현 | `STEPS`, `DISTANCE`, `SLEEP` 중 하나다. |
| 알림 페이로드 | Notification Payload | 현재 구현 | `appName`, `title`, `text`를 담으며 title/text 중 하나 이상이 필요하다. |

## 사진 업로드와 서빙

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 파일명 | filename | 현재 구현 | DB에 저장하는 `{uuidv7}.{jpg|png|webp}` 형식의 최소 사진 식별자다. |
| 클라이언트 사진 URI | clientPhotoUri | 현재 구현 | 기기 로컬 URI다. 서버는 해석하지 않고 저장·echo하며 `photoUrl`과 다른 layer다. |
| 전체 객체 키 | Full Object Key | 현재 구현 | 서버가 `{sha256hex(userId)}/photos/{filename}`으로 파생하는 S3 key다. DB에는 저장하지 않는다. |
| 사진 URL | photoUrl | 현재 구현 | `https://{cdnDomain}/{full object key}` 형태로 materialize해 payload에 저장한다. CDN domain·key 규칙 변경에는 backfill이 필요하다. |
| presigned 업로드 발급 | Presigned Upload | 현재 구현 | content type과 length를 서명에 묶은 PUT URL과 filename을 발급한다. |

## AI 타임라인 이벤트 생성

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 타임라인 이벤트 제안 | Timeline Event Suggestion | 부분 구현 | AI output staging entity와 fake flow는 있다. 실제 production AI dispatcher는 없다. AI는 staging event를 INSERT하고 source association을 UPDATE한다. |
| 아이템 ID 목록 | Item IDs | 현재 구현 | 서버 assembler가 staging 관계에서 조립하는 내부 `TimelineEventSuggestionDto.itemIds`다. callback payload도 AI 반환 list도 아니다. |
| 타임라인 이벤트 제안 검증 | Timeline Event Suggestion Validation | 현재 구현 | 서버가 조립한 `itemIds`, title, 시간 범위, 빈 이벤트와 중복 배정을 검증한다. |

## 비동기 작성 작업

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 작성 작업 | Draft Task | 현재 구현 | DRAFT daily record를 비동기로 만드는 작업 resource다. POST가 즉시 반환하고 상태는 Redis에 둔다. |
| 작업 ID | Task ID | 현재 구현 | UUIDv7 작업 식별자다. polling URL과 callback path에 사용한다. |
| 작업 상태 | Task Status | 현재 구현 | `PROCESSING`, `SUCCESS`, `FAILED` 중 하나다. |
| 타임라인 이벤트 제안 콜백 | Timeline Event Suggestion Callback | 현재 구현 | AI의 staging commit 이후 보내는 status-only 알림이다. body는 `{status,errorCode,error}`이고 서버가 staging을 읽어 finalize한다. |
| 타임라인 윈도우 | Timeline Window | 현재 구현 | 신규 source item의 `startTime/endTime` 범위다. Redis task에 저장하며 시간 있는 신규 item이 없으면 NULL이다. |
| 사용자 메모리 | User Memory | 부분 구현 | Redis task shape(`usersCharacter` 등)는 있지만 값을 공급하는 source는 없다. |
| 작업 시작 시각 | Processing Started At | 현재 구현 | 전처리(검증·dedupe·enrich·staging 저장)를 마치고 Redis PROCESSING task를 저장하기 직전에 캡처하는 Server 절대 시각(`processingStartedAt`, UTC Instant)이다. `recordAt`(클라 기록 시각)과 무관하고 PROCESSING 전용이다 — terminal 전이 시 폐기한다. |
| 작업 대기 경과 시간 | Elapsed Seconds | 현재 구현 | PROCESSING polling 응답의 `elapsedSeconds`(완료된 초, 0 이상 int64)다. 작업 시작 시각부터 polling 관측 시각까지다. SUCCESS/FAILED와 시각 없는 legacy task에서는 필드를 생략한다. |

## 저장 규칙

| 규칙 | 상태 | 설명 |
|---|---|---|
| Daily Record 유일성 | 현재 구현 | `UNIQUE(user_id, record_date)`다. |
| 이벤트-아이템 관계 | 현재 구현 | Timeline Item은 정확히 하나의 Timeline Event에 속한다. |
| 이벤트 FK | 현재 구현 | `timeline_items.timeline_event_id`는 `NOT NULL`이다. |
| Cascade 삭제 | 현재 구현 | Daily Record·Timeline Event를 삭제하면 하위 행(Events·Items)이 DB FK `ON DELETE CASCADE`로 함께 삭제된다. 삭제 API는 대상 PHOTO의 S3 배치 삭제 성공 후에만 DB 삭제를 시작한다. |
| finalize 단일 트랜잭션 | 현재 구현 | validate 후 Daily Record, Events, Items 저장과 두 staging table 삭제가 하나의 DB transaction이다. |
| AI 호출 위치 | 현재 구현 | AI dispatch는 DB transaction 밖 fire-and-forget이다. |
| 추가 데이터 처리 | 현재 구현 | 같은 날짜 신규 source item은 기존 event/item/title/subtitle/memo를 재구성하지 않고 새 event로 append한다. |
| rawId 중복 제외 | 현재 구현 | 기존 final item과 request 안의 중복 rawId는 신규 finalize 대상에서 제외한다. |
| startAt 충돌 회피 | 현재 구현 | 정확한 충돌은 +10분씩 미는 best-effort이며 DB unique constraint는 없다. |

## 사용자와 인증

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 사용자 | User | 현재 구현 | 소셜 로그인 사용자다. `(provider, provider_user_id)`로 식별하며 email 병합은 하지 않는다. |
| 로그인 제공자 | Provider | 현재 구현 | `GOOGLE` 또는 `KAKAO`다. |
| 제공자 사용자 ID | Provider User ID | 현재 구현 | OIDC ID token의 `sub`다. provider 안에서 사용자를 식별한다. |
| 액세스 토큰 | Access Token | 부분 구현 | HS256 JWT 발급·parse는 구현돼 있다. 의도상 `/a/api` bearer token이지만 request filter와 enforcement/userId 전파는 없다. |
| 리프레시 토큰 | Refresh Token | 현재 구현 | access 재발급용 opaque random token이다. DB에는 SHA-256 hex hash만 저장한다. |
| 회전 | Rotation | 현재 구현 | refresh token을 사용할 때 새 token으로 교체하고 이전 token을 `ROTATED`로 만든다. |
| 재사용 탐지 | Reuse Detection | 현재 구현 | `ROTATED`/`REVOKED` token 재제시 때 사용자의 refresh token 전체를 폐기한다. |
| 앱 인증 코드 | App Code | 현재 구현 | 로그인 성공 후 앱 handoff용 60초 one-time code다. Redis에는 hash key로만 저장하고 GETDEL로 소비한다. |
| 앱 검증값 | App Verifier / App Challenge | 현재 구현 | verifier와 `base64url(sha256(verifier))` challenge로 app-code 교환을 로그인 시작 주체에 바인딩한다. |
| 인증 사용자 API | Authenticated API | 목표 계약 | `/a/api/{version}`은 bearer 인증 영역이다. 현재 enforcement는 `permitAll`이고 timeline fallback은 userId 0이다. |

## 사용 금지 표현

이 표는 구현 상태가 아니라 항상 적용되는 naming 규범이다.

| 금지 표현 | 대신 사용할 표현 |
|---|---|
| Timeline Card / 타임라인 카드 | Timeline Event / 타임라인 이벤트 |
| Card Suggestion | Timeline Event Suggestion |
| `timeline_card_id` | `timeline_event_id` |
| Candidate | Source Item |
| Raw Timeline Item | Source Item |
| Card Item | Timeline Item |
| Display Text | Title 또는 Subtitle |
| Metadata Map | Typed Payload |
| `Map<String, Object> payload` | `TimelineItemPayload` |
| photoUri / 사진 URI(서버 사진 식별자) | `filename` 또는 `photoUrl`; 기기 로컬 URI는 `clientPhotoUri` |
| 세션 토큰 / Session Token | Access Token 또는 Refresh Token |
| 소셜 토큰(자체 token 의미) | Access Token / Refresh Token; provider 발급물은 ID token 등으로 명시 |
| 인가 코드 / Authorization Code(자체 handoff 의미) | App Code; Authorization Code는 OAuth provider code만 의미 |

## Known Gaps

부분 구현·미구현·목표 계약 상태는 해당 설명에 빠진 동작을 명시한다. 새 목표 용어를 추가할 때
구현된 것처럼 표현하지 않는다.

## Update When

domain model, enum, payload, AI/auth 계약, 상태 전이 또는 naming 규범이 바뀔 때 전체 관련 항목을
코드와 다시 대조한다.

## Validation

```bash
rg -n 'Timeline Card|Card Suggestion|timeline_card_id|Card Item' src .agents/knowledge
./gradlew test
```
