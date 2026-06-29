# 용어 사전

Laimory 도메인 용어는 아래 표현을 기준으로 사용한다.

## 일일 기록

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 일일 기록 | Daily Record | 한 사용자의 특정 날짜 기록이다. `user_id + record_date`는 유일해야 한다. |
| 기록 날짜 | Record Date | 일일 기록의 대상 날짜다. |
| 하루 감정 | Emotion Type | 하루 전체를 대표하는 감정이다. 5단계: `VERY_HAPPY`/`HAPPY`/`NEUTRAL`/`UNHAPPY`/`VERY_UNHAPPY`. draft 생성 흐름에선 미설정(NULL), 별도 save 흐름에서 설정. 이벤트별 감정은 MVP에 없다. |
| 작성중 | Draft | AI가 생성했거나 사용자가 아직 편집 중인 일일 기록 상태다. |
| 작성완료 | Saved | 사용자가 저장을 완료한 일일 기록 상태다. |

## 타임라인 이벤트

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 타임라인 이벤트 | Timeline Event | 사용자에게 보이는 하루 타임라인의 이벤트 단위다. |
| 제목 | Title | 이벤트의 대표 문구다. AI가 생성할 수 있다. |
| 부제목 | Subtitle | 이벤트의 보조 설명이다. AI가 생성할 수 있다. |
| 메모 | Memo | 사용자가 이벤트에 남기는 생각, 느낀점, 메모다. |
| 이벤트 시작 시각 | Start At | 이벤트가 표현하는 시간 범위의 시작 시각이다. |
| 이벤트 종료 시각 | End At | 이벤트가 표현하는 시간 범위의 종료 시각이다. 단일 시점 이벤트면 비어 있을 수 있다. |

## 타임라인 아이템

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 타임라인 아이템 | Timeline Item | AI가 이벤트에 포함시킨 source item이 DB에 저장된 것이다. |
| 아이템 타입 | Item Type | 아이템 종류다. 예: `PHOTO`, `CALENDAR`, `LOCATION`, `MOVEMENT`. |
| 아이템 시작 시각 | Start At | 아이템이 발생한 시작 시각이다. |
| 아이템 종료 시각 | End At | 기간형 아이템의 종료 시각이다. 단일 시점 아이템이면 비어 있을 수 있다. |
| 페이로드 | Payload | 타입별 세부 데이터다. DB에는 JSON으로 저장하되 Java에서는 typed payload로 다룬다. |

## 소스 아이템

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 소스 아이템 | Source Item | Android에서 받은 데이터를 서버가 AI 요청 전에 만든 임시 입력 데이터다. DB 엔티티가 아니다. |
| 소스 아이템 ID | Source Item ID | DB에 저장된 source item 행(`timeline_draft_source_items`)의 PK `timeline_draft_source_item_id`다. AI는 콜백의 `itemIds`에 이 값을 담아 어떤 source item을 이벤트에 넣을지 가리킨다. 클라가 부여하는 별도 요청 인덱스는 없다(POST는 순수 배열). |
| 채택된 소스 아이템 | Accepted Source Item | AI가 이벤트의 `itemIds`에 포함한 source item이다. 이것만 Timeline Item으로 저장된다. |
| 누락된 소스 아이템 | Omitted Source Item | AI가 어떤 이벤트에도 포함하지 않은 source item이다. MVP에서는 저장하지 않는다. |

## Payload 타입

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 아이템 페이로드 | Timeline Item Payload | 모든 payload 타입의 공통 인터페이스다. Java sealed interface로 표현한다. |
| 사진 페이로드 | Photo Payload | 사진 파일명(`filename`)과 위치 정보(위도/경도)를 담는다. DB엔 서빙 URL이 아니라 최소 식별자 `filename`만 저장한다. |
| 일정 페이로드 | Calendar Payload | 일정 제목, 캘린더명, 위치 텍스트 등을 담는다. |
| 장소 페이로드 | Location Payload | 장소명, 지역명, 위도, 경도 등을 담는다. |
| 이동 페이로드 | Movement Payload | 출발지, 도착지, 이동수단, 노선명 등을 담는다. |

## 사진 업로드/서빙

사진은 서버가 발급한 presigned PUT URL로 클라가 S3에 직접 올리고, 조회는 무서명 CloudFront URL로 서빙한다. DB엔 `filename`만 저장하고 full key·서빙 URL은 서버가 사용자 id로부터 파생한다.

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 파일명 | filename | DB에 저장하는 사진의 최소 식별자다. 형식은 `{uuidv7}.{ext}`(ext=jpg/png/webp). full key/서빙 URL이 아니다. |
| 전체 객체 키 | full object key | 실제 S3 객체 키다. 항상 서버가 파생한다: `{sha256hex(userId)}/photos/{filename}`. 날짜 폴더·taskId 없음. DB에 저장하지 않는다. |
| 사진 URL | photoUrl | 무서명 CloudFront 서빙 URL(`https://{cdnDomain}/{full object key}`)이다. 응답 전용이며 읽을 때 구성한다(DB 미저장). |
| presigned 업로드 발급 | Presigned Upload | 클라가 올릴 사진 메타(타입·크기)를 받아 photo마다 `filename` + presigned PUT URL을 발급하는 작업이다. 크기는 서명에 바인딩한다. |

## AI 타임라인 이벤트 생성

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 타임라인 이벤트 제안 | Timeline Event Suggestion | AI가 source items를 보고 반환하는 타임라인 이벤트 초안이다. |
| 아이템 ID 목록 | Item IDs | AI가 이벤트에 포함하겠다고 반환한 source item PK(`timeline_draft_source_item_id`) 목록이다. |
| 타임라인 이벤트 제안 검증 | Timeline Event Suggestion Validation | 서버가 AI 응답의 `itemIds`, 시간 범위, 빈 이벤트 여부 등을 검증하는 과정이다. |

## 비동기 작성 작업

타임라인 draft 생성은 AI 그룹핑을 기다리지 않도록 비동기 폴링으로 처리한다. POST는 draft 본체가 아니라 **draft를 만들어내는 작업(Draft Task)**을 생성해 즉시 반환하고, 클라이언트는 작업 ID로 폴링한다.

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 작성 작업 | Draft Task | DRAFT 상태 daily record를 비동기로 생성하는 작업이다. POST가 즉시 반환하는 리소스이며, draft 본체와는 별개다. Redis에 상태를 보관한다. |
| 작업 ID | Task ID | 작성 작업의 식별자(UUID)다. 클라이언트가 이 ID로 결과를 폴링한다. |
| 작업 상태 | Task Status | 작성 작업의 진행 상태다. `PROCESSING`(진행중), `SUCCESS`(완료), `FAILED`(실패) 중 하나다. |
| 타임라인 이벤트 제안 콜백 | Timeline Event Suggestion Callback | AI가 타임라인 이벤트 제안 결과를 서버로 되돌려주는 내부 호출이다. source item은 POST 시점에 MySQL(`timeline_draft_source_items`)에 저장되고, 이 콜백 시점에 최종 timeline(daily record·events·items)이 저장된다. |

## 저장 규칙

| 규칙 | 설명 |
| --- | --- |
| Daily Record 유일성 | `UNIQUE(user_id, record_date)`를 둔다. |
| 이벤트-아이템 관계 | MVP에서 Timeline Item은 정확히 하나의 Timeline Event에 속한다. |
| 이벤트 FK | `timeline_items.timeline_event_id`는 `NOT NULL`이다. |
| Cascade 삭제 | Timeline Event 삭제 시 그 하위 Timeline Items도 함께 삭제한다. |
| 단일 트랜잭션 | Daily Record, Timeline Events, Timeline Items 저장은 하나의 DB 트랜잭션으로 처리한다. |
| AI 호출 위치 | AI 호출은 DB 트랜잭션 밖에서 수행한다. |
| 추가 데이터 처리 | 같은 날짜에 새 source item이 들어오면 기존 event, item, title, subtitle, memo는 자동 변경하지 않는다. 새 이벤트로 append한다. |

## 사용 금지 표현

| 금지 표현 | 대신 사용할 표현 |
| --- | --- |
| Timeline Card / 타임라인 카드 | Timeline Event / 타임라인 이벤트 |
| Card Suggestion | Timeline Event Suggestion |
| timeline_card_id | timeline_event_id |
| Candidate | Source Item |
| Raw Timeline Item | Source Item |
| Card Item | Timeline Item |
| Display Text | Title 또는 Subtitle |
| Metadata Map | Typed Payload |
| Map<String, Object> payload | TimelineItemPayload |
| photoUri / 사진 URI | filename(DB 저장) 또는 photoUrl(응답) |

