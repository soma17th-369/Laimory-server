-- #517: 공지사항. 앱은 공개 조회만 하고 등록·수정·숨김은 localhost 관리자 웹이 한다.
-- additive CREATE TABLE이라 구 앱과 호환된다(rolling 배포 중 실행 가능).
CREATE TABLE notices (
    notice_id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    -- 노출 제어는 이 flag 하나다 — hard delete 없이 숨김이 삭제 역할을 한다(실수 복구 가능).
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    -- 감사 컬럼 (BaseEntity). 공개 응답의 publishedAt은 created_at이다.
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (notice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
