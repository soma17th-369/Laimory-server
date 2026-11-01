-- #620: 장소 후보마다 그 장소의 좌표를 저장한다(AI 결과 placeCandidates의 선택 좌표).
-- additive nullable 컬럼이라 구 앱과 호환된다(rolling 배포 중 실행 가능). 기존 후보와 구 앱 insert는 좌표 NULL이다.
-- 둘은 항상 함께 NULL이거나 함께 값이다(앱 검증 — 반쪽·범위 밖·NaN 후보 좌표는 결과 전체 400).
ALTER TABLE timeline_event_place_candidates
    ADD COLUMN latitude DOUBLE NULL,
    ADD COLUMN longitude DOUBLE NULL,
    ALGORITHM=INSTANT;
