package com.laimory.server.push.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.entity.NotificationConsent;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동의 상태 전이 transaction의 단일 소유자 — 현재 snapshot 갱신과 append-only event insert가 항상 함께
 * commit되게 한다. 증적 없는 상태 변경도, 상태 없는 증적도 남지 않는다.
 *
 * <p><b>판정을 미리 읽지 않는다.</b> 각 전이는 조건부 UPDATE이고 그 영향 행 수가 직전 상태의 근거다 —
 * 1이면 이 요청이 상태를 바꾼 것({@code APPLIED}), 0이면 그 순간 이미 같은 상태였다는 뜻
 * ({@code ALREADY_IN_STATE})이다. 읽고 나서 판단하면 읽기와 쓰기 사이에 낀 남의 commit 때문에
 * "이미 그 상태였다"는 거짓 증적이 남을 수 있다(동의·철회 동시 진입 시 철회 유실). UPDATE가 잡는 행
 * 락이 직렬화하므로 별도 {@code SELECT ... FOR UPDATE}는 필요 없다.
 *
 * <p>snapshot 읽기는 event에 남길 <b>근거 문서 ID를 라벨로 채우기 위해서만</b> 쓴다 — 상태 판정에는
 * 쓰지 않는다.
 *
 * <p>{@code TermAgreementTransactionService} 선례대로 transaction 경계를 담당하는 별도 빈이다 —
 * {@link NotificationConsentService}의 UNIQUE 경합 복구가 self-invocation 없이 새 transaction에서
 * 재조회할 수 있어야 하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class NotificationConsentTransactionService {

    private final NotificationConsentRepository notificationConsentRepository;
    private final NotificationConsentEventRepository notificationConsentEventRepository;

    /** 광고 동의 ON + 증적. 이미 같은 문서로 동의 상태였으면 상태는 그대로 두고 증적만 남긴다. */
    @Transactional
    public List<NotificationConsentEvent> consentAdvertising(ConsentCommand command, Long termDocumentId) {
        prepare(command.subjectId(), command.occurredAt());
        int applied = notificationConsentRepository.consentAdvertising(
                command.subjectId(), termDocumentId, command.occurredAt());
        return save(List.of(command.event(NotificationConsentType.ADVERTISING_PUSH,
                NotificationConsentAction.CONSENT, termDocumentId, result(applied))));
    }

    /**
     * 야간 동의 ON + 증적. 영향 0행은 두 경우라 그때만 한 번 읽어 분류한다 — 일반 광고 동의가 없으면
     * 409로 거절하고, 이미 같은 문서로 동의 상태면 증적만 남긴다.
     */
    @Transactional
    public List<NotificationConsentEvent> consentNight(ConsentCommand command, Long termDocumentId) {
        prepare(command.subjectId(), command.occurredAt());
        int applied = notificationConsentRepository.consentNight(
                command.subjectId(), termDocumentId, command.occurredAt());
        if (applied == 0 && !currentState(command.subjectId()).advertisingConsented()) {
            // 야간 동의는 일반 광고 동의를 전제한다 — 부분 적용 없이 거절한다.
            throw new BusinessException(ExceptionType.NOTIFICATION_CONSENT_REQUIRED);
        }
        return save(List.of(command.event(NotificationConsentType.NIGHT_ADVERTISING_PUSH,
                NotificationConsentAction.CONSENT, termDocumentId, result(applied))));
    }

    /**
     * 광고 동의 철회 + (ON이었다면) 야간 동의 철회 + 증적 전부를 한 transaction으로 남긴다.
     * 야간을 먼저 내려 "야간 ON이면 일반도 ON"이라는 불변식이 중간 상태에서도 깨지지 않게 한다.
     */
    @Transactional
    public List<NotificationConsentEvent> withdrawAdvertising(ConsentCommand command) {
        NotificationConsentService.ConsentState before = prepare(command.subjectId(), command.occurredAt());
        int nightWithdrawn = notificationConsentRepository.withdrawNight(
                command.subjectId(), command.occurredAt());
        int advertisingWithdrawn = notificationConsentRepository.withdrawAdvertising(
                command.subjectId(), command.occurredAt());

        List<NotificationConsentEvent> events = new ArrayList<>();
        events.add(command.event(NotificationConsentType.ADVERTISING_PUSH, NotificationConsentAction.WITHDRAW,
                before.advertisingTermDocumentId(), result(advertisingWithdrawn)));
        if (nightWithdrawn == 1) {
            // 일반 동의 철회가 야간 동의도 함께 내렸다는 사실을 별도 증적으로 남긴다.
            events.add(command.event(NotificationConsentType.NIGHT_ADVERTISING_PUSH,
                    NotificationConsentAction.WITHDRAW, before.nightTermDocumentId(),
                    NotificationConsentProcessingResult.APPLIED));
        }
        return save(events);
    }

    /** 야간 동의만 철회 + 증적. 일반 광고 동의는 그대로 둔다. */
    @Transactional
    public List<NotificationConsentEvent> withdrawNight(ConsentCommand command) {
        NotificationConsentService.ConsentState before = prepare(command.subjectId(), command.occurredAt());
        int withdrawn = notificationConsentRepository.withdrawNight(command.subjectId(), command.occurredAt());
        return save(List.of(command.event(NotificationConsentType.NIGHT_ADVERTISING_PUSH,
                NotificationConsentAction.WITHDRAW, before.nightTermDocumentId(), result(withdrawn))));
    }

    /**
     * 행이 없을 때만 기본 OFF로 만들고 현재 값을 돌려준다. 반환값은 event에 남길 근거 문서 ID를 채우는
     * 용도이며 상태 판정에는 쓰지 않는다(판정은 조건부 UPDATE의 영향 행 수가 소유).
     *
     * <p>있는 행에도 {@code INSERT IGNORE}를 날리면 그 행에 S락이 잡히고, 이어지는 조건부 UPDATE가
     * X락을 요구하면서 같은 subject를 동시에 다루는 두 transaction이 서로의 S락을 기다려 deadlock이
     * 난다(실 MySQL 동시 철회 테스트로 확인). 그래서 <b>먼저 읽고 없을 때만</b> insert한다 — 잠금 없는
     * 읽기라 정상 경로에서는 UPDATE의 X락 하나만 잡힌다.
     */
    private NotificationConsentService.ConsentState prepare(UUID subjectId, LocalDateTime occurredAt) {
        Optional<NotificationConsent> existing = notificationConsentRepository.findById(subjectId);
        if (existing.isPresent()) {
            return NotificationConsentService.ConsentState.of(existing.get());
        }
        notificationConsentRepository.insertIfAbsent(subjectId.toString(), occurredAt);
        return currentState(subjectId);
    }

    private NotificationConsentService.ConsentState currentState(UUID subjectId) {
        return notificationConsentRepository.findById(subjectId)
                .map(NotificationConsentService.ConsentState::of)
                .orElseGet(NotificationConsentService.ConsentState::none);
    }

    private static NotificationConsentProcessingResult result(int affectedRows) {
        return affectedRows == 1
                ? NotificationConsentProcessingResult.APPLIED
                : NotificationConsentProcessingResult.ALREADY_IN_STATE;
    }

    private List<NotificationConsentEvent> save(List<NotificationConsentEvent> events) {
        return notificationConsentEventRepository.saveAll(events);
    }

    /**
     * 한 요청이 전이와 증적에 공통으로 넘기는 값 묶음. 상태는 담지 않는다 — 상태 판정은 조건부 UPDATE가
     * 한다는 계약을 타입으로도 드러낸다.
     */
    public record ConsentCommand(UUID subjectId, LocalDateTime occurredAt, String senderName,
                                 NotificationConsentSource source) {

        NotificationConsentEvent event(NotificationConsentType type, NotificationConsentAction action,
                                       Long termDocumentId, NotificationConsentProcessingResult result) {
            return NotificationConsentEvent.of(subjectId, type, action, termDocumentId,
                    occurredAt, senderName, result, source);
        }
    }
}
