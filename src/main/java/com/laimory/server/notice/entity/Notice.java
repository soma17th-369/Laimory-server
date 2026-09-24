package com.laimory.server.notice.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.URI;
import lombok.Getter;

/**
 * 공지사항 한 건. 원문은 게시된 공지 page가 소유하고 이 행은 제목과 그 주소({@code contentUrl})만 담는다
 * — 약관({@code TermDocument}) 선례와 같은 구조다. 이미지·서식이 들어갈 수 있어 서버가 본문을 직접
 * 반환하지 않고 클라이언트가 URL을 WebView로 연다.
 *
 * <p>노출 제어는 {@code hidden} 하나다 — 관리자는 등록·수정·숨김/재노출만 하고 hard delete는 없다
 * (실수 복구 가능). 공개 목록은 숨김이 아닌 행만 보이고, 게시 시각은 {@code created_at}이다.
 * 약관과 달리 버전·불변 계약이 없어 기존 행의 제목·URL 수정(전체 교체)을 허용한다.
 */
@Entity
@Table(name = "notices")
@Getter
public class Notice extends BaseEntity {

    public static final int TITLE_MAX_LENGTH = 255;
    public static final int CONTENT_URL_MAX_LENGTH = 512;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notice_id")
    private Long noticeId;

    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    /** 게시된 공지 원문 page의 절대 https URL — 서버는 조회·검증만 하고 HTTP로 열지 않는다. */
    @Column(name = "content_url", nullable = false, length = CONTENT_URL_MAX_LENGTH)
    private String contentUrl;

    @Column(name = "hidden", nullable = false)
    private boolean hidden;

    protected Notice() {
    }

    private Notice(String title, String contentUrl) {
        this.title = title;
        this.contentUrl = contentUrl;
        this.hidden = false;
    }

    /** 즉시 노출되는 새 공지를 만든다(예약 게시 없음). */
    public static Notice of(String title, String contentUrl) {
        return new Notice(normalizeTitle(title), requireHttpsUrl(contentUrl));
    }

    /** 제목·원문 URL을 통째로 교체한다(부분 갱신 아님). 노출 상태는 건드리지 않는다. */
    public void edit(String title, String contentUrl) {
        this.title = normalizeTitle(title);
        this.contentUrl = requireHttpsUrl(contentUrl);
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

    /** 약관 등록({@code TermDocumentRegistrationService})과 같은 기준 — host가 있는 절대 HTTPS URL만 받는다. */
    private static String requireHttpsUrl(String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank() || contentUrl.length() > CONTENT_URL_MAX_LENGTH) {
            throw new IllegalArgumentException("contentUrl is required and must be at most "
                    + CONTENT_URL_MAX_LENGTH + " characters");
        }
        URI uri = URI.create(contentUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("contentUrl must be absolute HTTPS with a host");
        }
        return contentUrl;
    }
}
