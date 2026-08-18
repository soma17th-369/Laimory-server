package com.laimory.server.push.service;

import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 상태 전이 transaction의 단일 소유자 — 현재 snapshot 갱신과 append-only event insert가 항상 함께
 * commit되게 한다. 증적 없는 상태 변경도, 상태 없는 증적도 남지 않는다.
 *
 * <p>{@code TermAgreementTransactionService} 선례대로 transaction 경계만 담당하는 별도 빈이다 —
 * {@link NotificationConsentService}의 UNIQUE 경합 복구가 self-invocation 없이 새 transaction에서
 * 재조회할 수 있어야 하기 때문이다(같은 빈 안에서 잡으면 이미 rollback-only인 transaction에서 읽게 된다).
 */
@Service
@RequiredArgsConstructor
public class NotificationConsentTransactionService {

    private final NotificationConsentRepository notificationConsentRepository;
    private final NotificationConsentEventRepository notificationConsentEventRepository;

    /** 광고 동의 ON + 증적. */
    @Transactional
    public List<NotificationConsentEvent> consentAdvertising(UUID subjectId, Long termDocumentId,
                                                              LocalDateTime occurredAt,
                                                              List<NotificationConsentEvent> events) {
        notificationConsentRepository.consentAdvertising(subjectId, termDocumentId, occurredAt);
        return notificationConsentEventRepository.saveAll(events);
    }

    /** 야간 동의 ON + 증적 — 일반 광고 동의가 ON인 행만 바뀌므로 영향 0행은 불변식 위반이다. */
    @Transactional
    public List<NotificationConsentEvent> consentNight(UUID subjectId, Long termDocumentId,
                                                        LocalDateTime occurredAt,
                                                        List<NotificationConsentEvent> events) {
        if (notificationConsentRepository.consentNight(subjectId, termDocumentId, occurredAt) != 1) {
            throw new IllegalStateException("night advertising consent requires an active advertising consent");
        }
        return notificationConsentEventRepository.saveAll(events);
    }

    /** 광고 동의 철회 + (ON이었다면) 야간 동의 철회 + 증적 전부를 한 transaction으로 남긴다. */
    @Transactional
    public List<NotificationConsentEvent> withdrawAdvertising(UUID subjectId, LocalDateTime occurredAt,
                                                               List<NotificationConsentEvent> events) {
        notificationConsentRepository.withdrawAdvertising(subjectId, occurredAt);
        return notificationConsentEventRepository.saveAll(events);
    }

    /** 야간 동의만 철회 + 증적. 일반 광고 동의는 그대로 둔다. */
    @Transactional
    public List<NotificationConsentEvent> withdrawNight(UUID subjectId, LocalDateTime occurredAt,
                                                         List<NotificationConsentEvent> events) {
        notificationConsentRepository.withdrawNight(subjectId, occurredAt);
        return notificationConsentEventRepository.saveAll(events);
    }

    /** 상태 변화가 없는 의사 표시({@code ALREADY_IN_STATE}) — 증적만 남긴다. */
    @Transactional
    public List<NotificationConsentEvent> recordEventsOnly(List<NotificationConsentEvent> events) {
        return notificationConsentEventRepository.saveAll(events);
    }
}
