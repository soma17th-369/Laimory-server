# 용어 사전

Laimory 도메인 용어는 아래 표현을 기준으로 사용한다.

## 일일 기록

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 일일 기록 | Daily Record | 한 사용자의 특정 날짜 기록이다. `user_id + record_date`는 유일해야 한다. |
| 기록 날짜 | Record Date | 일일 기록의 대상 날짜다. |
| 하루 감정 | Emotion Type | 하루 전체를 대표하는 감정이다. 5단계: `VERY_HAPPY`/`HAPPY`/`NEUTRAL`/`UNHAPPY`/`VERY_UNHAPPY`. draft 생성 흐름에선 미설정(NULL), 별도 save 흐름에서 설정. 카드별 감정은 MVP에 없다. |
| 작성중 | Draft | AI가 생성했거나 사용자가 아직 편집 중인 일일 기록 상태다. |
| 작성완료 | Saved | 사용자가 저장을 완료한 일일 기록 상태다. |

## 타임라인 카드

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 타임라인 카드 | Timeline Card | 사용자에게 보이는 하루 타임라인의 카드 단위다. |
| 제목 | Title | 카드의 대표 문구다. AI가 생성할 수 있다. |
| 부제목 | Subtitle | 카드의 보조 설명이다. AI가 생성할 수 있다. |
| 메모 | Memo | 사용자가 카드에 남기는 생각, 느낀점, 메모다. |
| 카드 시작 시각 | Start At | 카드가 표현하는 시간 범위의 시작 시각이다. |
| 카드 종료 시각 | End At | 카드가 표현하는 시간 범위의 종료 시각이다. 단일 시점 카드면 비어 있을 수 있다. |

## 타임라인 아이템

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 타임라인 아이템 | Timeline Item | AI가 카드에 포함시킨 source item이 DB에 저장된 것이다. |
| 아이템 타입 | Item Type | 아이템 종류다. 예: `PHOTO`, `CALENDAR`, `LOCATION`, `MOVEMENT`. |
| 아이템 시작 시각 | Start At | 아이템이 발생한 시작 시각이다. |
| 아이템 종료 시각 | End At | 기간형 아이템의 종료 시각이다. 단일 시점 아이템이면 비어 있을 수 있다. |
| 페이로드 | Payload | 타입별 세부 데이터다. DB에는 JSON으로 저장하되 Java에서는 typed payload로 다룬다. |

## 소스 아이템

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 소스 아이템 | Source Item | Android에서 받은 데이터를 서버가 AI 요청 전에 만든 임시 입력 데이터다. DB 엔티티가 아니다. |
| 요청 아이템 ID | Request Item ID | AI 요청 배열에서 source item의 0-based index다. DB의 `timeline_items.id`가 아니다. |
| 채택된 소스 아이템 | Accepted Source Item | AI가 카드의 `itemIds`에 포함한 source item이다. 이것만 Timeline Item으로 저장된다. |
| 누락된 소스 아이템 | Omitted Source Item | AI가 어떤 카드에도 포함하지 않은 source item이다. MVP에서는 저장하지 않는다. |

## Payload 타입

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 아이템 페이로드 | Timeline Item Payload | 모든 payload 타입의 공통 인터페이스다. Java sealed interface로 표현한다. |
| 사진 페이로드 | Photo Payload | 사진 URI, 사진 위치 정보 등을 담는다. |
| 일정 페이로드 | Calendar Payload | 일정 제목, 캘린더명, 위치 텍스트, 참석자 수 등을 담는다. |
| 장소 페이로드 | Location Payload | 장소명, 지역명, 위도, 경도 등을 담는다. |
| 이동 페이로드 | Movement Payload | 출발지, 도착지, 이동수단, 노선명 등을 담는다. |

## AI 카드 생성

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 카드 제안 | Card Proposal | AI가 source items를 보고 반환하는 카드 초안이다. |
| 아이템 ID 목록 | Item IDs | AI가 카드에 포함하겠다고 반환한 request item id 목록이다. |
| 카드 생성 검증 | Card Proposal Validation | 서버가 AI 응답의 `itemIds`, 시간 범위, 빈 카드 여부 등을 검증하는 과정이다. |

## 비동기 작성 작업

타임라인 draft 생성은 AI 그룹핑을 기다리지 않도록 비동기 폴링으로 처리한다. POST는 draft 본체가 아니라 **draft를 만들어내는 작업(Draft Task)**을 생성해 즉시 반환하고, 클라이언트는 작업 ID로 폴링한다.

| 한글명 | 영문명 | 설명 |
| --- | --- | --- |
| 작성 작업 | Draft Task | DRAFT 상태 daily record를 비동기로 생성하는 작업이다. POST가 즉시 반환하는 리소스이며, draft 본체와는 별개다. Redis에 상태를 보관한다. |
| 작업 ID | Task ID | 작성 작업의 식별자(UUID)다. 클라이언트가 이 ID로 결과를 폴링한다. |
| 작업 상태 | Task Status | 작성 작업의 진행 상태다. `PROCESSING`(진행중), `SUCCESS`(완료), `FAILED`(실패) 중 하나다. |
| 카드 생성 콜백 | Card Proposal Callback | AI가 카드 제안 결과를 서버로 되돌려주는 내부 호출이다. 이 콜백 시점에 draft가 MySQL에 저장된다. |

## 저장 규칙

| 규칙 | 설명 |
| --- | --- |
| Daily Record 유일성 | `UNIQUE(user_id, record_date)`를 둔다. |
| 카드-아이템 관계 | MVP에서 Timeline Item은 정확히 하나의 Timeline Card에 속한다. |
| 카드 FK | `timeline_items.timeline_card_id`는 `NOT NULL`이다. |
| Cascade 삭제 | Timeline Card 삭제 시 그 하위 Timeline Items도 함께 삭제한다. |
| 단일 트랜잭션 | Daily Record, Timeline Cards, Timeline Items 저장은 하나의 DB 트랜잭션으로 처리한다. |
| AI 호출 위치 | AI 호출은 DB 트랜잭션 밖에서 수행한다. |
| 추가 데이터 처리 | 같은 날짜에 새 source item이 들어오면 기존 card, item, title, subtitle, memo는 자동 변경하지 않는다. 새 카드로 append한다. |

## 사용 금지 표현

| 금지 표현 | 대신 사용할 표현 |
| --- | --- |
| Candidate | Source Item |
| Raw Timeline Item | Source Item |
| Card Item | Timeline Item |
| Display Text | Title 또는 Subtitle |
| Metadata Map | Typed Payload |
| Map<String, Object> payload | TimelineItemPayload |

