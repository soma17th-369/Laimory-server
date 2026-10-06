-- #553: 앱 시작 팝업 공지 지정. 관리자가 공지별로 켜고 끄며 여러 건이 동시에 지정될 수 있다.
-- 이니셜라이저는 popup = true AND hidden = false인 공지 id만 내려준다 — 숨기면 지정은 남은 채 팝업에서
-- 빠지고, 다시 노출하면 팝업도 복귀한다(숨김 쓰기 경로는 이 컬럼을 건드리지 않는다).
-- additive 컬럼 + DEFAULT라 구 앱의 공지 등록과도 호환된다(rolling 배포 중 실행 가능).
ALTER TABLE notices ADD COLUMN popup BOOLEAN NOT NULL DEFAULT FALSE;
