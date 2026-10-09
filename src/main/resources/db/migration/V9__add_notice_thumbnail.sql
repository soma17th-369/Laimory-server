-- #560: 앱 시작 팝업 썸네일. 관리자 웹이 presigned PUT으로 사진 bucket의 notices/ prefix에 올린 파일명
-- ({uuidv7}.{jpg|png|webp})만 담고 S3 key·CDN URL은 서버가 파생한다(사진·문의 첨부와 같은 규칙).
-- 썸네일이 없는 공지는 NULL이다 — 팝업 지정에만 필수라 컬럼은 nullable이다.
-- additive 컬럼이라 구 앱과 호환된다(rolling 배포 중 실행 가능).
ALTER TABLE notices ADD COLUMN thumbnail_filename VARCHAR(64) NULL;

-- "팝업 공지는 썸네일이 있다" 불변식을 처음부터 성립시킨다 — 썸네일 이전에 지정된 팝업은 모두 썸네일이
-- 없으므로 해제한다. 관리자가 썸네일을 올린 뒤 다시 지정한다.
UPDATE notices SET popup = FALSE WHERE popup = TRUE;
