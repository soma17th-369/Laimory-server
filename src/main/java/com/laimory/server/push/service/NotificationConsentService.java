package com.laimory.server.push.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.PushSenderProperties;
import com.laimory.server.push.PushTimes;
import com.laimory.server.push.entity.NotificationConsent;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.terms.service.TermDocumentSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 광고성·야간 광고성 수신 동의의 현재 상태와 증적을 소유한다.
 *
 * <p>동의 owner는 subject다 — 인증 요청, push 등록, worker가 공통으로 가진 축이 subject 하나뿐이고
 * raw userId ↔ subject 역방향 mapping은 저장하지 않기 때문이다(#282). 법적 주체가 회원이라는 사실은
 * subject가 회원과 1:1이라는 점으로 보존된다.
 *
 * <p>멱등 축은 Android durable outbox가 붙이는 {@code clientRequestId}다. 같은 request ID의 재시도는
 * 상태를 다시 바꾸지 않고 원래 증적을 그대로 돌려주며, 같은 request ID로 다른 의사 표시가 오면 아무것도
 * 바꾸지 않고 409로 거절한다. 동시 재시도의 UNIQUE 경합은 winner의 증적을 새 transaction에서 되읽어
 * 같은 응답으로 수렴시킨다(duplicate key를 500으로 노출하지 않는다).
 *
 * <p>행 부재는 언제나 미동의다. 조회 장애나 rollout 공백을 동의로 추정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationConsentService {

    private final NotificationConsentRepository notificationConsentRepository;
    private final NotificationConsentEventRepository notificationConsentEventRepository;
    private final NotificationConsentTransactionService notificationConsentTransactionService;
    private final TermDocumentService termDocumentService;
    private final PushSenderProperties pushSenderProperties;
    private final Clock clock;

    /** 가입 transaction 합류용 기본 OFF 행 생성. 이미 있으면 no-op(멱등). */
    public void createDefaultIfAbsent(UUID subjectId) {
        notificationConsentRepository.insertIfAbsent(subjectId.toString(), nowKst());
    }

    /** 설정 조회 경로의 get-or-create — 누락 행을 같은 request에서 기본 OFF로 멱등 보정한다. */
    public ConsentState getOrCreateState(UUID subjectId) {
        createDefaultIfAbsent(subjectId);
        return findState(subjectId);
    }

    /** 현재 동의 상태 — 행이 없으면 전부 미동의로 읽는다(추정 금지). */
    public ConsentState findState(UUID subjectId) {
        return notificationConsentRepository.findById(subjectId)
                .map(ConsentState::of)
                .orElseGet(ConsentState::none);
    }

    /**
     * worker의 광고 발송 gate — claim한 subject들의 동의 상태를 batch 조회한다. 행이 없는 subject는
     * 결과에 없고 호출자는 미동의로 다룬다.
     */
    public Map<UUID, ConsentState> findStatesBySubjectIds(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return notificationConsentRepository.findAllBySubjectIdIn(subjectIds).stream()
                .collect(Collectors.toMap(NotificationConsent::getSubjectId, ConsentState::of,
                        (first, second) -> first));
    }

    /** 설정 GET의 최근 처리결과 — 사용자에게 처리 결과를 다시 보여줄 수 있게 서버 증적을 돌려준다. */
    public List<NotificationConsentEvent> findRecentEvents(UUID subjectId, Duration window) {
        return notificationConsentEventRepository.findRecent(subjectId, nowKst().minus(window));
    }

    /** 탈퇴 transaction 합류용 — 현재 상태 snapshot만 지운다(증적 event는 보존한다). */
    public void deleteStateForSubject(UUID subjectId) {
        notificationConsentRepository.deleteBySubjectId(subjectId);
    }

    /**
     * 동의·철회 의사 표시 하나를 처리하고 그 결과 증적을 돌려준다.
     *
     * <p>일반 광고 동의 철회는 ON이던 야간 동의도 같은 transaction에서 내리고 두 증적을 함께 남긴다.
     * 상태가 이미 같아도 새 {@code clientRequestId}면 {@code ALREADY_IN_STATE} 증적을 남긴다 — 사용자
     * 의사 표시는 언제나 처리 결과를 받는다.
     *
     * @param termVersion 동의일 때만 사용하는 현재 문서 버전(불일치·미존재는 409)
     */
    public List<NotificationConsentEvent> apply(UUID subjectId, UUID clientRequestId, NotificationConsentType type,
                                                boolean consented, String termVersion,
                                                NotificationConsentSource source) {
        Objects.requireNonNull(clientRequestId, "clientRequestId is required");
        createDefaultIfAbsent(subjectId);

        NotificationConsentAction action = consented
                ? NotificationConsentAction.CONSENT : NotificationConsentAction.WITHDRAW;
        Optional<List<NotificationConsentEvent>> replay = findReplay(subjectId, clientRequestId, type, action);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return consented
                    ? consent(subjectId, clientRequestId, type, termVersion, source)
                    : withdraw(subjectId, clientRequestId, type, source);
        } catch (DataIntegrityViolationException e) {
            // 같은 request ID 동시 재시도 — winner가 commit한 증적을 새 transaction에서 되읽어 수렴한다.
            return findReplay(subjectId, clientRequestId, type, action)
                    .orElseThrow(() -> e);
        }
    }

    private Optional<List<NotificationConsentEvent>> findReplay(UUID subjectId, UUID clientRequestId,
                                                                 NotificationConsentType type,
                                                                 NotificationConsentAction action) {
        List<NotificationConsentEvent> existing =
                notificationConsentEventRepository.findAllBySubjectIdAndClientRequestId(subjectId, clientRequestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        boolean sameIntent = existing.stream()
                .anyMatch(event -> event.getConsentType() == type && event.getAction() == action);
        if (!sameIntent) {
            throw new BusinessException(ExceptionType.CONSENT_REQUEST_MISMATCH);
        }
        return Optional.of(sorted(existing));
    }

    private List<NotificationConsentEvent> consent(UUID subjectId, UUID clientRequestId,
                                                    NotificationConsentType type, String termVersion,
                                                    NotificationConsentSource source) {
        LocalDateTime occurredAt = nowKst();
        ConsentState state = findState(subjectId);
        if (type == NotificationConsentType.NIGHT_ADVERTISING_PUSH && !state.advertisingConsented()) {
            // 야간 동의는 일반 광고 동의를 전제한다 — 부분 적용 없이 거절한다.
            throw new BusinessException(ExceptionType.NOTIFICATION_CONSENT_REQUIRED);
        }

        TermDocumentSummary document = requireCurrentDocument(type, termVersion, occurredAt);
        boolean alreadyConsented = type == NotificationConsentType.ADVERTISING_PUSH
                ? state.advertisingConsented() : state.nightAdvertisingConsented();
        Long recordedDocumentId = type == NotificationConsentType.ADVERTISING_PUSH
                ? state.advertisingTermDocumentId() : state.nightTermDocumentId();
        boolean sameDocument = alreadyConsented && document.termDocumentId().equals(recordedDocumentId);

        NotificationConsentProcessingResult result = sameDocument
                ? NotificationConsentProcessingResult.ALREADY_IN_STATE
                : NotificationConsentProcessingResult.APPLIED;
        List<NotificationConsentEvent> events = List.of(event(subjectId, clientRequestId, type,
                NotificationConsentAction.CONSENT, document.termDocumentId(), occurredAt, result, source));

        if (result == NotificationConsentProcessingResult.ALREADY_IN_STATE) {
            return notificationConsentTransactionService.recordEventsOnly(events);
        }
        return type == NotificationConsentType.ADVERTISING_PUSH
                ? notificationConsentTransactionService.consentAdvertising(
                        subjectId, document.termDocumentId(), occurredAt, events)
                : notificationConsentTransactionService.consentNight(
                        subjectId, document.termDocumentId(), occurredAt, events);
    }

    private List<NotificationConsentEvent> withdraw(UUID subjectId, UUID clientRequestId,
                                                     NotificationConsentType type,
                                                     NotificationConsentSource source) {
        LocalDateTime occurredAt = nowKst();
        ConsentState state = findState(subjectId);
        List<NotificationConsentEvent> events = new ArrayList<>();

        if (type == NotificationConsentType.NIGHT_ADVERTISING_PUSH) {
            events.add(event(subjectId, clientRequestId, type, NotificationConsentAction.WITHDRAW,
                    state.nightTermDocumentId(), occurredAt,
                    state.nightAdvertisingConsented()
                            ? NotificationConsentProcessingResult.APPLIED
                            : NotificationConsentProcessingResult.ALREADY_IN_STATE,
                    source));
            return state.nightAdvertisingConsented()
                    ? notificationConsentTransactionService.withdrawNight(subjectId, occurredAt, events)
                    : notificationConsentTransactionService.recordEventsOnly(events);
        }

        events.add(event(subjectId, clientRequestId, NotificationConsentType.ADVERTISING_PUSH,
                NotificationConsentAction.WITHDRAW, state.advertisingTermDocumentId(), occurredAt,
                state.advertisingConsented()
                        ? NotificationConsentProcessingResult.APPLIED
                        : NotificationConsentProcessingResult.ALREADY_IN_STATE,
                source));
        if (state.nightAdvertisingConsented()) {
            // 일반 동의 철회는 야간 동의도 함께 내린다 — 그 사실도 별도 증적으로 남긴다.
            events.add(event(subjectId, clientRequestId, NotificationConsentType.NIGHT_ADVERTISING_PUSH,
                    NotificationConsentAction.WITHDRAW, state.nightTermDocumentId(), occurredAt,
                    NotificationConsentProcessingResult.APPLIED, source));
        }
        return state.advertisingConsented() || state.nightAdvertisingConsented()
                ? notificationConsentTransactionService.withdrawAdvertising(subjectId, occurredAt, events)
                : notificationConsentTransactionService.recordEventsOnly(events);
    }

    /** 제출 버전이 지금의 현재 문서와 정확히 일치해야 한다 — 미존재·과거·미래 버전은 같은 409로 수렴한다. */
    private TermDocumentSummary requireCurrentDocument(NotificationConsentType type, String termVersion,
                                                        LocalDateTime nowKst) {
        if (termVersion == null || termVersion.isBlank()) {
            throw new IllegalArgumentException("termVersion is required to consent");
        }
        TermType termType = termTypeOf(type);
        return termDocumentService.findCurrentSummaries(List.of(termType), nowKst).stream()
                .filter(summary -> summary.termType() == termType && termVersion.equals(summary.version()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ExceptionType.STALE_TERM_VERSION));
    }

    private NotificationConsentEvent event(UUID subjectId, UUID clientRequestId, NotificationConsentType type,
                                            NotificationConsentAction action, Long termDocumentId,
                                            LocalDateTime occurredAt,
                                            NotificationConsentProcessingResult result,
                                            NotificationConsentSource source) {
        return NotificationConsentEvent.of(subjectId, clientRequestId, type, action, termDocumentId, occurredAt,
                pushSenderProperties.requireSenderName(), result, source);
    }

    private static List<NotificationConsentEvent> sorted(List<NotificationConsentEvent> events) {
        return events.stream()
                .sorted(Comparator.comparing(NotificationConsentEvent::getNotificationConsentEventId))
                .toList();
    }

    static TermType termTypeOf(NotificationConsentType type) {
        return type == NotificationConsentType.ADVERTISING_PUSH
                ? TermType.ADVERTISING_PUSH_CONSENT
                : TermType.NIGHT_ADVERTISING_PUSH_CONSENT;
    }

    private LocalDateTime nowKst() {
        return PushTimes.kstWallClock(clock.instant());
    }

    /**
     * 발송 gate와 응답이 함께 쓰는 현재 동의 상태 view. 행 부재는 {@link #none()}으로 표현해 호출부가
     * {@code Optional} 분기 없이 미동의를 다루게 한다.
     */
    public record ConsentState(boolean advertisingConsented, Long advertisingTermDocumentId,
                               boolean nightAdvertisingConsented, Long nightTermDocumentId) {

        static ConsentState of(NotificationConsent consent) {
            return new ConsentState(consent.isAdvertisingPushConsented(), consent.getAdvertisingTermDocumentId(),
                    consent.isNightAdvertisingPushConsented(), consent.getNightTermDocumentId());
        }

        static ConsentState none() {
            return new ConsentState(false, null, false, null);
        }
    }
}
