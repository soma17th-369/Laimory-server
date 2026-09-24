package com.laimory.server.notice.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 공지사항 한 건. 본문 텍스트를 서버가 직접 소유한다(약관처럼 외부 page URL을 가리키지 않는다).
 *
 * <p>노출 제어는 {@code hidden} 하나다 — 관리자는 등록·수정·숨김/재노출만 하고 hard delete는 없다
 * (실수 복구 가능). 공개 목록·상세는 숨김이 아닌 행만 보이고, 게시 시각은 {@code created_at}이다.
 */
@Entity
@Table(name = "notices")
@Getter
public class Notice extends BaseEntity {

    public static final int TITLE_MAX_LENGTH = 255;
    /** MySQL TEXT(64KB) 안에 여유 있게 들어가는 상한 — 초과 입력이 500(DB 거절)이 아니라 400으로 끝나게 한다. */
    public static final int BODY_MAX_LENGTH = 10_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long noticeId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    protected Notice() {
    }

    private Notice(String title, String body) {
        this.title = title;
        this.body = body;
        this.hidden = false;
    }

    /** 즉시 노출되는 새 공지를 만든다(예약 게시 없음). */
    public static Notice of(String title, String body) {
        return new Notice(normalizeTitle(title), requireBody(body));
    }

    /** 제목·본문을 통째로 교체한다(부분 갱신 아님). 노출 상태는 건드리지 않는다. */
    public void edit(String title, String body) {
        this.title = normalizeTitle(title);
        this.body = requireBody(body);
    }

    public void changeVisibility(boolean hidden) {
        this.hidden = hidden;
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String normalized = title.strip();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("title must be at most " + TITLE_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        if (body.length() > BODY_MAX_LENGTH) {
            throw new IllegalArgumentException("body must be at most " + BODY_MAX_LENGTH + " characters");
        }
        return body;
    }
}
