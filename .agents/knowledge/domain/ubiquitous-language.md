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
| 일일 기록 | Daily Record | 현재 구현 | 한 콘텐츠 subject의 특정 날짜 기록이다. `subject_id + record_date`는 유일하다. |
| 기록 날짜 | Record Date | 현재 구현 | 일일 기록의 대상 날짜다. 클라이언트가 draft 요청에 명시한 선택 날짜가 단일 권위이며, 서버는 계산·보정 없이 DailyRecord 조회·선생성에 그대로 쓴다(과거 정오 경계 파생은 #164에서 삭제). |
| 기록 시각 | Record At | 현재 구현 | 사용자가 실제로 기록을 만든 벽시계 시각(`recordAt`)이다. timezone(`recordTimeZone`)과 함께 역산용 메타데이터로만 저장하며 서버는 아무것도 파생하지 않는다 — 기록 날짜와 날짜가 달라도 된다(다음날 아침에 쓴 어제 일기). |
| 하루 감정 | Emotion Type | 현재 구현 | 하루 전체의 5단계 감정 enum(`VERY_HAPPY`~`VERY_UNHAPPY`)이다. draft에서는 NULL이고, save API의 필수 body `emotionType`이 `SAVED` 전이와 같은 조건부 UPDATE로 확정한다. 저장 전 DRAFT·과거 SAVED 행의 NULL은 정상값이며(backfill 없음) 이벤트별 감정은 없다. |
| 작성중 | Draft | 현재 구현 | draft 요청 시 선생성되거나 사용자가 아직 편집 중인 일일 기록 상태 `DRAFT`다. AI 실패 시 empty DRAFT가 남을 수 있으며 같은 날짜 재시도가 재사용한다. |
| 작성완료 | Saved | 현재 구현 | `SAVED` enum과 append·Event 수정·메모·삭제(Event/DailyRecord)·Event-Item 연결 해제 거부, 그리고 사용자가 필수 `emotionType` body와 함께 `DRAFT→SAVED`로 전환하는 `POST .../daily-records/{recordDate}/save`가 모두 있다. 전이는 하루 감정과 함께 동기 커밋이라 200이 곧 저장 완료이고, 뒤따르는 User Memory 갱신은 별개 흐름이다. |

## 타임라인 이벤트

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 타임라인 이벤트 | Timeline Event | 현재 구현 | 사용자에게 보이는 하루 타임라인의 이벤트 단위다. |
| 이벤트 타입 | Event Type | 현재 구현 | Event 자체의 분류다. Item Type(source 종류)과 독립이며 서로 변환·추론하지 않는다. `TimelineEventType` enum: `WAKE_UP`(기상), `SLEEP`(수면), `MOVEMENT`(이동), `CALENDAR_EVENT`(캘린더 일정), `MEAL`(식사), `PHOTO_MOMENT`(사진으로 찍은 순간들), `MEETING`(회의), `CLASS`(수업), `WORK`(근무), `EXERCISE`(운동), `SOCIAL`(대화), `REST`(휴식), `UNKNOWN`(알 수 없음). `UNKNOWN`은 기존 데이터·구버전 writer 컬럼 생략·AI 미판별의 fallback sentinel이다. AI가 어떤 입력을 어떤 타입으로 분류하는지(경계·우선순위)는 미구현·별도 결정이다. |
| 제목 | Title | 현재 구현 | 이벤트의 대표 문구다. AI 결과를 서버가 저장하며 사용자가 편집할 수 있다. |
| 부제목 | Subtitle | 현재 구현 | 이벤트의 보조 설명이다. nullable이다. |
| 질문 | Question | 현재 구현 | AI가 이벤트마다 생성해 사용자에게 되묻는 문장이다. AI 결과 저장에서만 채워지는 nullable 값(trim 후 최대 255자, 공백은 null)이며 일별·단건 조회 응답에 그대로 실린다. 사용자 편집 API와 답변 저장은 미구현이다. |
| 메모 | Memo | 현재 구현 | 사용자가 이벤트에 남기는 텍스트다. Event PATCH에서 선택적으로 작성·수정·제거한다 — 필드 부재는 변경 없음, null·blank는 제거, 그 외는 trim 없이 원문 저장(최대 500자 — User Memory 갱신 접수 계약의 상한). PUT memo endpoint는 Event의 다른 필드 없이 memo만 교체하는 현재 지원 API다. 두 편집 API 모두 성공 시 `body=null`을 반환한다. |
| 이벤트 시작 시각 | Start At | 현재 구현 | 이벤트 시간 범위의 시작이다. 필수이며 읽을 때 정렬 기준이다. |
| 이벤트 종료 시각 | End At | 현재 구현 | 이벤트 시간 범위의 끝이다. 단일 시점이면 nullable이다. |

## 타임라인 아이템

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 타임라인 아이템 | Timeline Item | 현재 구현 | 채택된 draft source item을 최종 저장한 독립 행이다. `rawId`를 보존하며 Event와는 junction(`timeline_event_items`)으로만 연결된다 — 한 Item이 여러 Event에 공유될 수 있다(N:M, 같은 Daily Record 안에서만). |
| 아이템 타입 | Item Type | 현재 구현 | `PHOTO`, `CALENDAR`, `STAY`, `MOVEMENT`, `HEALTH`, `NOTIFICATION` 중 하나다. DB `item_type`이 권위 필드다. |
| 아이템 시작 시각 | Start At | 현재 구현 | 아이템이 발생한 시작 시각이다. |
| 아이템 종료 시각 | End At | 현재 구현 | 기간형 아이템의 종료 시각이며 단일 시점이면 nullable이다. |
| 페이로드 | Payload | 현재 구현 | HTTP 입력·enrich에서는 sealed `TimelineItemPayload` subtype을 사용한다. staging/final DB와 response pass-through에서는 `JsonNode` JSON을 사용한다. |

## 소스 아이템

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 소스 아이템 | Source Item | 현재 구현 | Android가 보낸 draft 입력 개념이다. `SourceItemDto`는 비-entity 입력 표현이고, 서버는 이를 `TimelineDraftSourceItem` staging entity로 저장한다. |
| 소스 아이템 ID | Source Item ID | 현재 구현 | `timeline_draft_source_items.timeline_draft_source_item_id` PK다. 서버 내부 행 식별자이며 AI에는 노출하지 않는다(AI는 `rawId`로만 식별한다). |
| 원본 데이터 ID | rawId | 현재 구현 | 클라이언트 원본 식별자다. payload 밖 `raw_id` column에 저장해 dedupe한다. 서버는 canonical lowercase UUID(8-4-4-4-12, version 무관 — 형식 규칙은 `RawIds`가 단일 정의)만 허용하고 그 외는 400이다. Android는 전 itemType에서 lowercase UUIDv4를 발급하고 서버 Swagger 예시는 v7이라 version은 고정하지 않으며, 허용값은 정규화 없이 그대로 저장/echo한다. staging은 `(task_id, raw_id)` UNIQUE, final은 유일 constraint가 없다. Draft는 API 사전 제외 + AI write 직전 재검사로 방어하고, Event PATCH의 PHOTO 추가는 request 첫 항목 우선 dedupe 뒤 같은 record의 PHOTO를 재사용하며 대상 Event에 이미 연결됐으면 no-op 처리한다(같은 rawId의 non-PHOTO는 거절). |
| 채택된 소스 아이템 | Accepted Source Item | 현재 구현 | AI가 결과에서 Event에 연결한 staging source item이다. 서버 결과 저장 transaction에서 Timeline Item이 되며 같은 transaction에서 staging 행이 삭제된다. |
| 누락된 소스 아이템 | Omitted Source Item | 현재 구현 | AI가 채택하지 않아 staging에 남는 source item이다. 최종 item으로 저장하지 않으며 retention cleanup이 정리한다. |

## Payload 타입

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 아이템 페이로드 | Timeline Item Payload | 현재 구현 | HTTP input/enrich에서 쓰는 sealed payload 공통 interface다. |
| 사진 페이로드 | Photo Payload | 현재 구현 | final JSON은 `filename`, `clientPhotoUri`, 좌표, nullable 설명과 서버가 만든 `photoUrl`을 담는다. AI writer는 설명을 붙일 수 있지만 Event PATCH의 수동 PHOTO 입력은 `filename`·`clientPhotoUri`·좌표만 받고 `description=null`로 저장한다. 해당 입력에는 `photoUrl`도 없으며 서버가 subjectId와 filename에서 생성한다. |
| 일정 페이로드 | Calendar Payload | 현재 구현 | 일정 제목, 위치 텍스트, 설명, 종일 여부를 담는다. |
| 머문 곳 페이로드 | Stay Payload | 현재 구현 | 필수 좌표와 서버 파생 주소·주변 장소·머문 시간 텍스트를 담는다. |
| 이동 페이로드 | Movement Payload | 현재 구현 | `start`/`end` endpoint, `transports`, `distanceMeters`를 담는다. |
| 이동 끝점 | Movement Endpoint | 현재 구현 | 출발·도착의 필수 좌표와 서버 파생 주소·주변 장소를 담는 중첩 값이다. |
| 이동수단 | transports | 현재 구현 | 단일 문자열 이동수단 분류다. |
| 주소 | address | 현재 구현 | 서버가 reverse geocoding한 nullable 주소다. 도로명 우선, 없으면 지번이며 client 값은 무시한다. JSON key 생략(NON_NULL)은 noop 미연동, 정상 조회했으나 주소 부재, 허용된 지오코딩 실패 좌표 세 경우 모두 가능하다 — wire에 실패 marker는 없고 내부 구분은 서버 outcome/metric이 담당한다. |
| 주변 장소 목록 | places | 현재 구현 | 거리순 장소명 배열이다. NULL은 noop으로 JSON key 생략, 빈 배열은 정상 조회했으나 장소 없음 또는 허용된 지오코딩 실패 좌표다(wire 구분 없음). client 값은 무시한다. |
| 머문 시간 텍스트 | durationText | 현재 구현 | 서버가 `startAt/endAt`으로 계산한 텍스트다. client 값은 받지 않는다. |
| 건강 페이로드 | Health Payload | 현재 구현 | 지표와 단위 포함 text `value`를 담는다. 측정 구간은 item envelope에 있다. |
| 건강 지표 | Health Metric | 현재 구현 | `STEPS`, `DISTANCE`, `SLEEP` 중 하나다. |
| 알림 페이로드 | Notification Payload | 현재 구현 | `appName`, `title`, `text`를 담으며 title/text 중 하나 이상이 필요하다. |

## 사진 업로드와 서빙

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 파일명 | filename | 현재 구현 | DB에 저장하는 `{uuidv7}.{jpg|png|webp}` 형식의 최소 사진 식별자다. |
| 클라이언트 사진 URI | clientPhotoUri | 현재 구현 | 기기 로컬 URI다. 서버는 해석하지 않고 저장·echo하며 `photoUrl`과 다른 layer다. storage redaction에서 원문을 보존하는 유일한 제외 필드지만, AI 입력 조회 응답에서는 값 전체를 `[REDACTED_DEVICE_URI]` 고정 token으로 치환한다(DB·앱 응답은 원문). |
| 전체 객체 키 | Full Object Key | 현재 구현 | 서버가 `{hex(SHA-256(subjectId canonical 16 bytes))}/photos/{filename}`으로 파생하는 S3 key다. 활성 PHOTO payload에는 filename만 저장하고, 삭제 의무가 생기면 PHOTO Delete Job에 full key snapshot을 저장한다. |
| 사진 URL | photoUrl | 현재 구현 | `https://{cdnDomain}/{full object key}` 형태로 materialize해 payload에 저장한다. CDN domain·key 규칙 변경에는 backfill이 필요하다. |
| presigned 업로드 발급 | Presigned Upload | 현재 구현 | content type과 length를 서명에 묶은 PUT URL과 filename을 발급한다. |
| 사진 삭제 작업 | PHOTO Delete Job | 현재 구현 | 마지막 Event 참조가 사라진 PHOTO Item을 원문 행 그대로 보존하면서 full object key를 MySQL `timeline_photo_delete_jobs`에 기록하는 작업이다. `PENDING`은 Event PATCH가 취소·재연결할 수 있는 대기 상태, `PROCESSING`은 worker의 S3 삭제 진행 상태이고 `available_at`은 다음 claim 가능 시각이다. job은 원 Item을 FK로 참조하며 신규 job은 다음 날부터 claim한다. 여러 worker가 `SKIP LOCKED`로 서로 다른 batch를 가져가고, S3 성공 뒤 job과 Item을 한 transaction에서 최종 hard delete한다. |

## AI 타임라인 이벤트 생성

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| AI 접수 요청 | AI Dispatch Request | 현재 구현 | `POST /v1/timeline` body(`taskId`·`taskToken`·`dailyRecordId`·offset `window`)다. source item은 싣지 않고 AI가 토큰으로 입력 조회 API를 호출한다. 필드명은 AI 규격이 명명 권위인 contract fixture다. |
| AI 작업 입력 | AI Task Input | 현재 구현 | 서버가 소유하는 정규 AI 입력이다(`GET /s/api/{v}/timeline/drafts/{taskId}/input`). 기록 날짜·timezone·타임라인 윈도우·source item을 담고 DB 식별자와 사용자 ID는 노출하지 않으며, 시각은 record timezone offset ISO-8601이다. |
| AI 생성 결과 | AI Result | 현재 구현 | AI가 만든 Event, Event별 nullable 질문과 각 Event가 채택한 `rawId` 목록이다(`POST .../result`). 서버가 검증·정규화해 Event/Item/junction으로 저장하고 채택 source를 삭제한다. confidence·추론 설명 등 저장하지 않는 출력은 계약에 없다. |

## 비동기 작성 작업

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 작성 작업 | Draft Task | 현재 구현 | DRAFT daily record를 비동기로 만드는 작업 resource다. POST가 즉시 반환하고 상태는 Redis에 둔다. |
| 작업 ID | Task ID | 현재 구현 | UUIDv7 작업 식별자다. polling URL과 callback path에 사용한다. |
| 작업 상태 | Task Status | 현재 구현 | `PROCESSING`, `SUCCESS`, `FAILED` 중 하나다. |
| AI 작성 콜백 | AI Draft Callback | 현재 구현 | 결과 저장을 마친 뒤(또는 실패했을 때) 보내는 status-only 알림이다. body는 `{status,errorCode,error}`이고 `errorCode`는 음수 JSON integer다. 서버는 Redis terminal CAS와 완료 푸시만 한다. SUCCESS는 `CALLBACK_PENDING`, FAILED는 결과 저장 전 stage에서만 받으며 같은 terminal 재전송은 200·상충은 `-1017`이다. |
| 작업 토큰 | Task Token | 현재 구현 | 서버간 각 단계가 현재 요청을 인증하는 opaque 256-bit bearer token이다. dispatch가 최초 token을 전달하고 입력·결과 성공 응답이 다음 token을 body로 반환한다. 원문은 저장·로그하지 않고 Redis task에 현재 SHA-256 hash만 보존해 매 요청 검증한다. |
| 처리 단계 | Process Stage | 현재 구현 | PROCESSING task의 서버간 처리 순서를 제한하는 Redis 내부 상태다. `INPUT_PENDING → RESULT_PENDING → CALLBACK_PENDING`이며 외부 polling status에는 노출하지 않는다. token hash와 함께 현재 task JSON 전체 CAS로 전이한다. |
| 타임라인 윈도우 | Timeline Window | 현재 구현 | 클라이언트가 draft 요청에 지정한 AI 이벤트 생성 범위(`timelineWindow.startTime/endTime`)다. 서버는 필수값과 `startTime < endTime`만 검증하고, Redis에는 local 원본을 보존하며 AI 입력 조회 응답에서 record timezone 기반 offset ISO(`window.startAt/endAt`)로 변환해 내보낸다. 기록 날짜·기록 시각과 독립이며 상호 정합성은 검증하지 않는다. |
| 작업 시작 시각 | Processing Started At | 현재 구현 | 전처리(검증·dedupe·enrich·선생성+staging 커밋)를 마치고 Redis PROCESSING task를 저장하기 직전에 캡처하는 Server 절대 시각(`processingStartedAt`, UTC Instant)이다. `recordAt`(클라 기록 시각)과 무관하고 PROCESSING 전용이다 — terminal 전이 시 폐기한다. |
| subject별 진행 작업 index | Subject Processing Index | 현재 구현 | subject별 진행 중 draft 작업 조회 보조 sorted set(`timeline:draft-task:user:{canonicalUuid(subjectId)}:processing`, member=taskId, score=작업 시작 시각 epoch ms)이다. task JSON의 status/owner가 유일한 권위이며 index는 후보일 뿐이다 — task write 뒤 native ZADD/ZREM(+PEXPIRE)하고 실패 시 최신 task 기준 멱등 보정하며, 목록 API도 후보마다 JSON을 검증하고 만료·terminal·타인 소유 member를 lazy 정리한다. key TTL은 PROCESSING 저장마다 3분으로 갱신되는 inactivity cleanup이다. |
| 작업 대기 경과 시간 | Elapsed Seconds | 현재 구현 | PROCESSING polling 응답의 `elapsedSeconds`(완료된 초, 0 이상 int64)다. 작업 시작 시각부터 polling 관측 시각까지다. SUCCESS/FAILED에서는 필드를 생략한다. |

## 저장 규칙

| 규칙 | 상태 | 설명 |
|---|---|---|
| Daily Record 유일성 | 현재 구현 | `UNIQUE(subject_id, record_date)`다. |
| 이벤트-아이템 관계 | 현재 구현 | Event↔Item은 `timeline_event_items` junction N:M이다. 한 Item이 같은 Daily Record의 여러 Event에 공유될 수 있다(same-record 규칙은 DB 제약이 아니라 writer 계약). |
| Cascade 삭제 | 현재 구현 | Daily Record·Timeline Event 행 삭제 시 자기 junction이 DB FK `ON DELETE CASCADE`로 삭제된다. 삭제 대상에만 연결된 non-PHOTO Item은 같은 transaction에서 명시 삭제하고 shared Item은 유지한다. 마지막 참조가 사라진 유효 PHOTO Item은 job과 함께 보존하며, commit 뒤 worker가 S3 삭제 성공을 확인한 뒤 Item과 job을 최종 hard delete한다. |
| Event-Item 연결 해제 | 현재 구현 | DELETE items API가 Event와 PHOTO Item의 junction 한 줄만 직접 DELETE로 지운다(Event·shared Item 유지, 연결된 non-PHOTO는 400 거절, 미연결·없음·비소유는 404 은닉, 같은 junction 동시 해제의 후발 요청은 영향 행 0 → 404). 마지막 참조 판정은 best-effort 일반 읽기라 경합 시 job 없는 orphan Item이 남을 수 있고(orphan 스위퍼 후속 과제), 마지막 참조 PHOTO는 Cascade 삭제와 같은 job 보존 규칙을 따른다. |
| Daily Record 선생성 | 현재 구현 | draft POST가 DailyRecord find-or-create(+recordAt/timezone 갱신)와 source 저장을 한 트랜잭션으로 AI dispatch 전에 커밋한다. |
| AI 결과 단일 트랜잭션 | 현재 구현 | 새 callback token hash와 Redis `CALLBACK_PENDING`을 CAS로 선점한 요청이 서버 결과 검증 후 Event/Item/junction 저장과 accepted source 삭제를 하나의 DB transaction으로 commit한다. 실패하면 가능한 경우 이전 result token hash와 `RESULT_PENDING`으로 복구한다. |
| Event 편집 단일 트랜잭션 | 현재 구현 | Event PATCH는 Event 필드·선택적 memo 수정과 수동 PHOTO Item/junction 추가를 하나의 DB transaction으로 commit한다. 수동 PHOTO는 기존 같은 record의 PHOTO Item을 재사용할 수 있다. |
| AI 호출 위치 | 현재 구현 | AI dispatch는 DB transaction 밖이며 접수(202) 확인까지 동기다. |
| 추가 데이터 처리 | 현재 구현 | 같은 날짜 신규 source item은 기존 event/item/title/subtitle/memo를 재구성하지 않고 새 event로 append한다(append-only). |
| rawId 중복 제외 | 현재 구현 | 기존 final item(junction 경유 조회)과 request 안의 중복 rawId는 신규 task 대상에서 제외하고, 결과 저장 transaction이 write 직전 재검사한다. |
| startAt 충돌 회피 | 현재 구현 | 정확한 충돌은 +10분씩 미는 best-effort이며 DB unique constraint는 없다(적용 주체는 서버 결과 저장 transaction). |

## 사용자와 인증

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 사용자 | User | 현재 구현 | 소셜 로그인 사용자다. `(provider, provider_user_id)`로 식별하며 email 병합은 하지 않는다. |
| 로그인 제공자 | Provider | 현재 구현 | `GOOGLE` 또는 `KAKAO`다. |
| 제공자 사용자 ID | Provider User ID | 현재 구현 | OIDC ID token의 `sub`다. provider 안에서 사용자를 식별한다. |
| 닉네임 | Nickname | 현재 구현 | nullable 프로필 표시용 값이다. 식별자가 아니다. Kakao는 id_token `nickname` claim을 저장하고 재로그인 시 non-null 값만 갱신한다. Google은 full name을 저장하는 기존 동작이며 재로그인 갱신은 없다. |
| 사용자 메모리 | User Memory | 부분 구현 | 사용자별로 누적되는 요약 문서다. AI가 생성·갱신하고 서버는 내부 구조·필드·버전을 해석하지 않는 opaque JSON으로 보존한다(단 저장 직전 textual leaf만 v1 privacy 치환 — 구조·필드 집합 불변). `user_memories` 테이블의 `subject_id` PK로 subject당 1행을 보존하며 `User` 조회가 문서를 끌고 오지 않는다. 하루 기록 저장과 메모리 교체는 서로 다른 transaction이고, pending/guard/task와 DB 조회·저장은 모두 subjectId 기준이다. 부분 병합과 앱 노출 API는 없다. |
| 액세스 토큰 | Access Token | 현재 구현 | HS256 JWT(`iss/sub/iat/exp`)다. `/a/api` bearer token으로 request filter가 검증해 `Long` userId principal을 만든다. subject는 양수 userId만 유효하다. |
| 리프레시 토큰 | Refresh Token | 현재 구현 | access 재발급용 opaque random token이다. DB에는 SHA-256 hex hash만 저장한다. |
| 회전 | Rotation | 현재 구현 | refresh token을 사용할 때 새 token으로 교체하고 이전 token을 `ROTATED`로 만든다. |
| 재사용 탐지 | Reuse Detection | 현재 구현 | `ROTATED`/`REVOKED` token 재제시 때 사용자의 refresh token 전체를 폐기한다. |
| 앱 인증 코드 | App Code | 현재 구현 | 로그인 성공 후 앱 handoff용 60초 one-time code다. Redis에는 hash key로만 저장하고 GETDEL로 소비한다. |
| 앱 검증값 | App Verifier / App Challenge | 현재 구현 | verifier와 `base64url(sha256(verifier))` challenge로 app-code 교환을 로그인 시작 주체에 바인딩한다. |
| 인증 사용자 API | Authenticated API | 현재 구현 | `/a/api/{version}`은 bearer 인증 강제 영역이다. 무토큰/무효 토큰은 401 `-2001`이다. JWT filter의 raw `Long userId` principal은 유지하되, 콘텐츠·push API는 `@CurrentSubject` MVC resolver가 Java `UUID subjectId`로 해석해 controller/service에 주입한다. |
| 작업 소유자 | Task Owner | 현재 구현 | Redis draft task에 필수로 보존되는 UUIDv4 subject다. 폴링 소유권 대조, 콜백 terminal 전이·완료 push 대상 조회의 기준이다. raw userId/FID는 task에 보존하지 않는다. |
| 콘텐츠 주체 | Subject (`subjectId`) | 현재 구현 | 인증 userId와 분리된 콘텐츠·push 소유 UUIDv4다. 별도 value type 없이 Java `UUID`를 쓰고, DB와 Redis는 canonical lowercase 36자 문자열을 사용한다. 로그·예외에는 userId·lookup key와 함께 남기지 않는다. DailyRecord·staging·User Memory·push·Redis task/queue/guard·PHOTO namespace의 owner authority다. |
| 주체 매핑 | Subject Mapping | 현재 구현 | `user_subject_links` 행 — HMAC-SHA-256 lookup key(BINARY(32) PK)로 userId를 subject로 해석한다. `SubjectMappingService`만 접근하며, 신규 사용자 생성 transaction에서만 만들어지고 일반 경로(`getRequired`)는 누락을 자동 생성 없이 내부 불변식 위반으로 fail-closed한다. HMAC key rotation은 PK·version 원자 교체이고 subject는 불변이다. |

## 푸시 알림

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 푸시 등록 | Push Registration | 현재 구현 | subject 하나의 활성 앱 설치(FID) 하나를 나타내는 `push_registrations` 행이다. `subject_id`가 owner authority이고 FID 하나는 한 시점 단일 owner다(계정 전환 시 현재 인증 subject로 원자 재결합). callback은 draft task의 subject로 현재 FID를 조회한다. |
| Firebase 설치 ID | Firebase Installation ID (FID) | 현재 구현 | FCM 발송 target인 대소문자 구분 opaque 식별자다(Admin SDK 9.10.0에서 deprecated registration token을 대체). 서버는 trim·형식 재작성 없이 저장·비교하고, 원문을 URL·로그·예외 메시지에 남기지 않는다(body 수신 + access log 마스킹). |
| 타임라인 완료 푸시 | Timeline Completion Push | 현재 구현 | callback이 처음 확정한 terminal(`SUCCESS`/`FAILED`) 뒤 비동기 best-effort로 보내는 완료 신호다. 일반 문구 notification + data(`taskId`,`status`)뿐이며 결과의 권위 원천이 아니다 — 앱은 push를 받으면 polling API로 결과를 조회한다. Source Item의 알림 페이로드(`NotificationPayload`)와는 무관한 별개 개념이다. |

## 개인정보 치환

| 한글명 | 영문명 | 상태 | 설명 |
|---|---|---|---|
| 개인정보 치환기 | Privacy Redactor | 현재 구현 | v1 금지 유형을 고정 token으로 바꾸는 상태 없는 공용 component(`common.privacy.PrivacyRedactor`)다. 저장(draft staging·AI 결과·User Memory)과 AI 전달 경계가 같은 인스턴스를 공유한다. 원문·매치 문자열을 예외·로그·metric에 담지 않고 유형별 occurrence 수만 집계하며, 기존 token literal을 보호해 멱등이다. 길이 상한 경계(Event text 255자·memo 500자)에서는 token literal을 중간에서 자르지 않는 token-aware bounded 치환을 쓴다(원문 fallback 없음). |
| 치환 토큰 | Redaction Token | 현재 구현 | `RedactionType`이 소유하는 `[REDACTED_*]` literal 11종(PHONE·EMAIL·RRN·FOREIGNER_ID·PASSPORT·DRIVER_LICENSE·CARD·ACCOUNT·SECRET·SOCIAL_ID·DEVICE_URI)이다. 저장·AI 전달 경계가 공유하는 계약 문자열이라 enum에서만 정의한다. `DEVICE_URI`는 텍스트 자동 탐지가 없는 상수 전용 유형으로, AI 입력 조회의 `clientPhotoUri` 값 치환에만 쓴다. |

## 사용 금지 표현

이 표는 구현 상태가 아니라 항상 적용되는 naming 규범이다.

| 금지 표현 | 대신 사용할 표현 |
|---|---|
| Timeline Card / 타임라인 카드 | Timeline Event / 타임라인 이벤트 |
| Card Suggestion | Timeline Event Suggestion |
| AI Direct-Write / AI 직접 저장 | AI Result + 서버 결과 저장 transaction(AI는 DB에 직접 쓰지 않는다) |
| `timeline_card_id` | `timeline_event_id` |
| Candidate | Source Item |
| Raw Timeline Item | Source Item |
| Card Item | Timeline Item |
| Display Text | Title 또는 Subtitle |
| Metadata Map | Typed Payload |
| `Map<String, Object> payload` | `TimelineItemPayload` |
| photoUri / 사진 URI(서버 사진 식별자) | `filename` 또는 `photoUrl`; 기기 로컬 URI는 `clientPhotoUri` |
| Push Token / Registration Token / Device Token(FCM 발송 target 의미) | Firebase Installation ID (FID) |
| `NotificationPayload`(푸시 메시지 의미) | Timeline Completion Push; `NotificationPayload`는 Source Item 알림 페이로드 전용 |
| 세션 토큰 / Session Token | Access Token 또는 Refresh Token |
| 소셜 토큰(자체 token 의미) | Access Token / Refresh Token; provider 발급물은 ID token 등으로 명시 |
| 인가 코드 / Authorization Code(자체 handoff 의미) | App Code; Authorization Code는 OAuth provider code만 의미 |

## Known Gaps

부분 구현·미구현·목표 계약 상태는 해당 설명에 빠진 동작을 명시한다. 새 목표 용어를 추가할 때
구현된 것처럼 표현하지 않는다.
실 AI(Laimory-AI)의 서버간 입력·결과 호출 구현은 별도 저장소 진행분이다.
같은 날짜 draft·수동 PHOTO 추가·삭제 사이의 공통 admission/직렬화와 경합 정합성 보장은 미구현이다.

## Update When

domain model, enum, payload, AI/auth 계약, 상태 전이 또는 naming 규범이 바뀔 때 전체 관련 항목을
코드와 다시 대조한다.

## Validation

```bash
rg -n 'Timeline Card|Card Suggestion|timeline_card_id|Card Item' src .agents/knowledge
./gradlew test
```
