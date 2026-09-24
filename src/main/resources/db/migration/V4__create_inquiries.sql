-- #518: 문의사항. 로그인 사용자가 앱에서 접수하고, 답변은 서버에 저장하지 않는다(접수 email로 수동 회신).
-- additive CREATE TABLE이라 구 앱과 호환된다(rolling 배포 중 실행 가능).
CREATE TABLE inquiries (
    inquiry_id BIGINT NOT NULL AUTO_INCREMENT,
    subject_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, -- 콘텐츠 owner authority
    -- 답장 받을 주소(사용자 입력, 회원 정보에 email이 없다). 탈퇴 삭제(#302)가 행째 지운다.
    email VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    -- 관리자가 답장을 보낸 뒤 표시하는 처리 시각 — 답장 발송 여부의 유일한 기록(서버는 email을 보내지 않는다).
    answered_at DATETIME(6) NULL,
    -- 감사 컬럼 (BaseEntity)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    modified_by VARCHAR(32) NULL,
    PRIMARY KEY (inquiry_id),
    KEY idx_inquiries_subject (subject_id),
    -- mapping 삭제가 문의를 암묵 cascade하지 않게 RESTRICT(subject_preferences 선례 — 물리 삭제는 #302 소유).
    CONSTRAINT fk_inquiries_subject
        FOREIGN KEY (subject_id) REFERENCES user_subject_links (subject_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 문의 한 건의 첨부 사진 filename(최대 3장). S3 full key는 subject namespace의 inquiries prefix에서 파생한다.
CREATE TABLE inquiry_attachments (
    inquiry_attachment_id BIGINT NOT NULL AUTO_INCREMENT,
    inquiry_id BIGINT NOT NULL,
    filename VARCHAR(64) NOT NULL, -- {uuidv7}.{jpg|png|webp}
    position INT NOT NULL,         -- 접수 요청의 순서(0부터)
    PRIMARY KEY (inquiry_attachment_id),
    UNIQUE KEY uq_inquiry_attachments_position (inquiry_id, position),
    CONSTRAINT fk_inquiry_attachments_inquiry
        FOREIGN KEY (inquiry_id) REFERENCES inquiries (inquiry_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
