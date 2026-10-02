-- #517: 공지사항. 앱은 공개 조회만 하고 등록·수정·숨김은 localhost 관리자 웹이 한다.
-- 원문은 약관(term_documents.content_url 선례)처럼 게시된 page가 소유하고 행은 그 주소만 담는다
-- (이미지·서식이 들어갈 수 있어 서버가 본문 텍스트를 직접 반환하지 않는다).
-- additive CREATE TABLE이라 구 앱과 호환된다(rolling 배포 중 실행 가능).
CREATE TABLE notices (
    notice_id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    -- 게시된 공지 원문 page의 절대 https URL. 서버는 조회·검증만 하고 HTTP로 열지 않는다.
    content_url VARCHAR(512) NOT NULL,
    -- 노출 제어는 이 flag 하나다 — hard delete 없이 숨김이 삭제 역할을 한다(실수 복구 가능).
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    -- 감사 컬럼 (BaseEntity). 공개 응답의 publishedAt은 created_at이다.
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (notice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
