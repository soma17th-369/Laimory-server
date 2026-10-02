package com.laimory.server.inquiry.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 문의 한 건. 사용자가 앱에서 접수한 답장 주소·제목·내용을 담고 첨부 filename은
 * {@link InquiryAttachment}가 소유한다.
 *
 * <p>답변은 이 행에 저장하지 않는다 — 관리자가 {@code email}로 직접 회신하고 {@code answeredAt}만 표시한다.
 * 앱의 "내 문의" 조회(#529)는 소유자 자신의 입력 내용과 {@code answeredAt}에서 파생한 처리 상태만 읽는다. owner는 콘텐츠
 * subject라 탈퇴 삭제(#302)가 subject FK {@code RESTRICT} 아래에서 행째 지운다(email PII 포함).
 */
@Entity
@Table(name = "inquiries")
@Getter
public class Inquiry extends BaseEntity {

    public static final int EMAIL_MAX_LENGTH = 255;
    public static final int TITLE_MAX_LENGTH = 100;
    public static final int DESCRIPTION_MAX_LENGTH = 2_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inquiry_id")
    private Long inquiryId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, updatable = false, length = 36)
    private UUID subjectId;

    @Column(name = "email", nullable = false, updatable = false, length = EMAIL_MAX_LENGTH)
    private String email;

    @Column(name = "title", nullable = false, updatable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Column(name = "description", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String description;

    /** 관리자가 답장을 보낸 뒤 표시한 시각(Asia/Seoul 벽시계) — null이면 미처리. */
    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    protected Inquiry() {
    }

    private Inquiry(UUID subjectId, String email, String title, String description) {
        this.subjectId = subjectId;
        this.email = email;
        this.title = title;
        this.description = description;
    }

    /** 앱 인증 접수 한 건. 접수 후 주소·제목·내용은 바뀌지 않는다(수정 API 없음). */
    public static Inquiry of(UUID subjectId, String email, String title, String description) {
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId is required");
        }
        return new Inquiry(subjectId, requireValidEmail(email), requireValidTitle(title),
                requireDescription(description));
    }

    /** 처리됨 표시·해제. 표시 시각은 호출자가 캡처한 KST 벽시계다(해제는 null). */
    public void changeAnswered(LocalDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }

    public boolean isAnswered() {
        return answeredAt != null;
    }

    /** null·공백뿐은 거절, 그 외 strip 후 최대 255자({@code TimelineEventInputRules.requireValidTitle} 선례). */
    private static String requireValidEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        String normalized = email.strip();
        if (normalized.length() > EMAIL_MAX_LENGTH) {
            throw new IllegalArgumentException("email must be at most " + EMAIL_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    /** null·공백뿐은 거절, 그 외 strip 후 최대 100자({@code TimelineEventInputRules.requireValidTitle} 선례). */
    private static String requireValidTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String normalized = title.strip();
        if (normalized.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("title must be at most " + TITLE_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    private static String requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        if (description.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "description must be at most " + DESCRIPTION_MAX_LENGTH + " characters");
        }
        return description;
    }
}
