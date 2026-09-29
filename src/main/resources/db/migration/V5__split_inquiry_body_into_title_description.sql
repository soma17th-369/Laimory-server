-- #530: 문의 본문(body)을 제목(title)·내용(description)으로 나눈다. body는 description으로 rename하고
-- 기존 행의 title은 고정 문구로 채운 뒤 기본값을 제거한다(이후 접수는 title을 반드시 넣는다).
-- 구 앱과 호환되지 않는다 — 배포 중 구 앱의 문의 접수는 실패하고, ddl-auto=validate라 구 image로
-- 되돌리면 기동하지 못한다(단계적 전환 대신 한 번에 바꾸기로 승인된 결정).
ALTER TABLE inquiries
    RENAME COLUMN body TO description,
    ADD COLUMN title VARCHAR(100) NOT NULL DEFAULT '(제목 없음)' AFTER email;
ALTER TABLE inquiries ALTER COLUMN title DROP DEFAULT;
