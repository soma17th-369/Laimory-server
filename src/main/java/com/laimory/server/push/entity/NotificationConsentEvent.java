package com.laimory.server.push.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
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
 * 동의·철회 의사 표시 하나의 append-only 증적. 수정·삭제하지 않는다 — 언제 어떤 문구·버전에 대해
 * 무슨 의사를 표시했고 서버가 어떻게 처리했는지를 나중에 증명하는 기록이다.
 *
 * <p>{@code clientRequestId}는 Android durable outbox가 붙이는 멱등 키다.
 * {@code (subject, clientRequestId, consentType)} UNIQUE가 재시도로 같은 의사 표시가 여러 event가 되는
 * 것을 DB에서 막는다 — 응답이 유실돼도 재시도는 원래 event를 그대로 돌려받는다.
 *
 * <p>{@code senderName}은 event 생성 시점의 법무 확정 전송자 법인명 snapshot이다(설정이 바뀌어도
 * 과거 증적의 표기는 변하지 않는다).
 */
@Entity
@Table(name = "notification_consent_events")
@Getter
public class NotificationConsentEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_consent_event_id")
    private Long notificationConsentEventId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "client_request_id", nullable = false, length = 36)
    private UUID clientRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 64)
    private NotificationConsentType consentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationConsentAction action;

    /** 의사 표시 대상 문서 — 동의 이력이 없는 상태의 철회만 NULL이다. */
    @Column(name = "term_document_id")
    private Long termDocumentId;

    /** 서버가 캡처한 KST 벽시계 처리 시각(클라이언트 입력 아님). */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "sender_name", nullable = false, length = 255)
    private String senderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_result", nullable = false, length = 32)
    private NotificationConsentProcessingResult processingResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationConsentSource source;

    protected NotificationConsentEvent() {
    }

    private NotificationConsentEvent(UUID subjectId, UUID clientRequestId, NotificationConsentType consentType,
                                     NotificationConsentAction action, Long termDocumentId, LocalDateTime occurredAt,
                                     String senderName, NotificationConsentProcessingResult processingResult,
                                     NotificationConsentSource source) {
        this.subjectId = subjectId;
        this.clientRequestId = clientRequestId;
        this.consentType = consentType;
        this.action = action;
        this.termDocumentId = termDocumentId;
        this.occurredAt = occurredAt;
        this.senderName = senderName;
        this.processingResult = processingResult;
        this.source = source;
    }

    public static NotificationConsentEvent of(UUID subjectId, UUID clientRequestId,
                                              NotificationConsentType consentType, NotificationConsentAction action,
                                              Long termDocumentId, LocalDateTime occurredAt, String senderName,
                                              NotificationConsentProcessingResult processingResult,
                                              NotificationConsentSource source) {
        return new NotificationConsentEvent(subjectId, clientRequestId, consentType, action, termDocumentId,
                occurredAt, senderName, processingResult, source);
    }
}
