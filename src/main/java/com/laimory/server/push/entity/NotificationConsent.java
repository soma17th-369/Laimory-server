package com.laimory.server.push.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * subject별 광고성·야간 광고성 수신 동의의 현재 상태 snapshot. 행 부재는 언제나 미동의로 해석하며
 * rollout 공백이나 조회 장애를 동의로 추정하지 않는다(fail-closed).
 *
 * <p>동의 이력·증적은 {@link NotificationConsentEvent}가 소유한다. 이 행은 "지금 보낼 수 있는가"만
 * 답하고, 각 동의의 근거 문서와 시각을 함께 보관해 어떤 버전에 동의했는지 재구성할 수 있게 한다.
 *
 * <p>동의 주체는 법적으로 회원이지만 owner 키는 subject다 — 인증·push 등록·worker가 공통으로 가진
 * 축이 subject 하나뿐이고, raw userId ↔ subject 역방향 mapping은 저장하지 않는 것이 #282의 계약이다.
 *
 * <p>불변식(service가 보장): 야간 동의가 ON이면 일반 광고 동의도 ON이다.
 */
@Entity
@Table(name = "notification_consents")
@Getter
public class NotificationConsent extends BaseEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    @Column(name = "advertising_push_consented", nullable = false)
    private boolean advertisingPushConsented;

    /** 동의 당시의 현재 문서 — 철회 뒤에도 마지막 동의 근거로 보존한다(미동의 이력이면 NULL). */
    @Column(name = "advertising_term_document_id")
    private Long advertisingTermDocumentId;

    @Column(name = "advertising_consented_at")
    private LocalDateTime advertisingConsentedAt;

    @Column(name = "advertising_withdrawn_at")
    private LocalDateTime advertisingWithdrawnAt;

    @Column(name = "night_advertising_push_consented", nullable = false)
    private boolean nightAdvertisingPushConsented;

    @Column(name = "night_term_document_id")
    private Long nightTermDocumentId;

    @Column(name = "night_consented_at")
    private LocalDateTime nightConsentedAt;

    @Column(name = "night_withdrawn_at")
    private LocalDateTime nightWithdrawnAt;

    protected NotificationConsent() {
    }
}
