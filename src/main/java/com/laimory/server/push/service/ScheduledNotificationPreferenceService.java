package com.laimory.server.push.service;

import com.laimory.server.push.PushTimes;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.push.repository.ScheduledNotificationPreferenceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예정 알림 종류별 설정과 occurrence 스케줄 상태의 단일 관문.
 *
 * <p>{@code nextDueAt} 계산 규칙은 한 곳에서만 산다: 같은 KST 날짜의 occurrence를 이미 처리했거나 새
 * 시각이 이미 지났으면 다음 날, 아니면 오늘이다. 발송 여부와 무관하게 하루의 occurrence는 최대 한 번만
 * 처리된다.
 *
 * <p>기본값은 OFF/21:00이다 — 시각은 표시·저장할 수 있지만 사용자가 직접 켜기 전에는 발송하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ScheduledNotificationPreferenceService {

    static final boolean DEFAULT_ENABLED = false;
    static final LocalTime DEFAULT_NOTIFICATION_TIME = LocalTime.of(21, 0);
    private static final int MAX_BATCH_SIZE = 1_000;

    private final ScheduledNotificationPreferenceRepository scheduledNotificationPreferenceRepository;
    private final Clock clock;

    /** 가입 transaction 합류용 기본 OFF/21:00 행 생성. 이미 있으면 no-op(멱등). */
    public void createDefaultIfAbsent(UUID subjectId, ScheduledNotificationType notificationType) {
        LocalDateTime nowKst = nowKst();
        scheduledNotificationPreferenceRepository.insertIfAbsent(
                subjectId.toString(),
                notificationType.name(),
                DEFAULT_ENABLED,
                DEFAULT_NOTIFICATION_TIME,
                computeNextDueAt(nowKst, DEFAULT_NOTIFICATION_TIME, null),
                nowKst);
    }

    /**
     * 설정 조회·변경 경로의 get-or-create — rollout 공백 행을 같은 request에서 멱등 보정한다.
     *
     * <p><b>있는 행에는 쓰기를 하지 않는다.</b> 있는 행에 {@code INSERT IGNORE}를 날리면 그 행에 S락이
     * 잡히고, 뒤따르는 설정 UPDATE가 X락을 요구하면서 같은 subject를 동시에 다루는 두 transaction이
     * 서로의 S락을 기다려 deadlock에 빠진다(실 MySQL 동시 토글 테스트로 확인).
     */
    @Transactional
    public ScheduledNotificationPreference getOrCreate(UUID subjectId, ScheduledNotificationType notificationType) {
        Optional<ScheduledNotificationPreference> existing = find(subjectId, notificationType);
        if (existing.isPresent()) {
            return existing.get();
        }
        createDefaultIfAbsent(subjectId, notificationType);
        return find(subjectId, notificationType)
                // insert-if-absent 직후라 도달할 수 없다 — 조용한 기본값 대신 불변식 위반으로 드러낸다.
                .orElseThrow(() -> new IllegalStateException(
                        "scheduled notification preference missing after insert-if-absent"));
    }

    public Optional<ScheduledNotificationPreference> find(UUID subjectId,
                                                          ScheduledNotificationType notificationType) {
        return scheduledNotificationPreferenceRepository.findById(
                new ScheduledNotificationPreferenceId(subjectId, notificationType));
    }

    /** 종류별 ON/OFF 전환 — 다음 예정 시각을 저장된 시각 기준으로 다시 계산한다. */
    @Transactional
    public void updateEnabled(UUID subjectId, ScheduledNotificationType notificationType, boolean enabled) {
        ScheduledNotificationPreference preference = getOrCreate(subjectId, notificationType);
        LocalDateTime nextDueAt = computeNextDueAt(nowKst(), preference.getNotificationTime(),
                preference.getLastProcessedOccurrenceDate());
        scheduledNotificationPreferenceRepository.updateEnabled(subjectId, notificationType, enabled, nextDueAt);
    }

    /** 시각 변경 — OFF 상태에서도 저장하며 다음 예정 시각을 함께 옮긴다. */
    @Transactional
    public void updateNotificationTime(UUID subjectId, ScheduledNotificationType notificationType,
                                       LocalTime notificationTime) {
        ScheduledNotificationPreference preference = getOrCreate(subjectId, notificationType);
        LocalDateTime nextDueAt = computeNextDueAt(nowKst(), notificationTime,
                preference.getLastProcessedOccurrenceDate());
        scheduledNotificationPreferenceRepository.updateNotificationTime(
                subjectId, notificationType, notificationTime, nextDueAt);
    }

    /**
     * due occurrence를 row lock으로 분리하고 같은 짧은 transaction에서 처리 완료로 표시한 뒤 현재 시각
     * 이후 첫 occurrence로 옮긴다. 반환 시 transaction·row lock이 끝났으므로 호출자는 외부 I/O를
     * 안전하게 수행할 수 있다.
     *
     * <p>허용 지연을 넘긴 행도 함께 claim한다 — 발송 대상 판정은 호출자가 반환된 {@code nextDueAt}으로
     * 하고, 여기서는 오래된 행이 매분 다시 선택되지 않도록 전진만 보장한다.
     *
     * @return claim한 행들(값은 claim 시점 상태 — {@code nextDueAt}은 방금 처리한 occurrence 시각이다)
     */
    @Transactional
    public List<ScheduledNotificationPreference> claimDue(ScheduledNotificationType notificationType, int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        LocalDateTime nowKst = nowKst();
        List<ScheduledNotificationPreference> due = scheduledNotificationPreferenceRepository
                .findDueForUpdateSkipLocked(notificationType.name(), nowKst, limit);
        if (due.isEmpty()) {
            return List.of();
        }
        // 전진 값은 Java에서 KST로 계산한다 — SQL 안에서 DATETIME 파라미터와 TIME 컬럼을 섞어 파생하면
        // JDBC의 timezone 변환 때문에 JVM zone에 따라 결과가 달라진다. 같은 (occurrence 날짜, 다음 시각)
        // 조합끼리 묶어 문장 수를 줄인다(대부분 같은 시각을 쓰므로 보통 1~2개로 수렴).
        Map<Advance, List<UUID>> subjectsByAdvance = new LinkedHashMap<>();
        for (ScheduledNotificationPreference preference : due) {
            LocalDate occurrenceDate = preference.getNextDueAt().toLocalDate();
            Advance advance = new Advance(occurrenceDate,
                    computeNextDueAt(nowKst, preference.getNotificationTime(), occurrenceDate));
            subjectsByAdvance.computeIfAbsent(advance, key -> new ArrayList<>())
                    .add(preference.getSubjectId());
        }
        int advanced = 0;
        for (Map.Entry<Advance, List<UUID>> entry : subjectsByAdvance.entrySet()) {
            advanced += scheduledNotificationPreferenceRepository.markProcessedAndAdvance(
                    notificationType, entry.getValue(),
                    entry.getKey().occurrenceDate(), entry.getKey().nextDueAt());
        }
        if (advanced != due.size()) {
            throw new IllegalStateException("scheduled notification claim count mismatch");
        }
        return List.copyOf(due);
    }

    /** 탈퇴 transaction 합류용 — subject의 모든 종류 행 삭제(마스터 삭제보다 먼저). */
    public void deleteAllForSubject(UUID subjectId) {
        scheduledNotificationPreferenceRepository.deleteAllBySubjectId(subjectId);
    }

    /**
     * 다음 예정 시각 — 같은 KST 날짜의 occurrence를 이미 처리했거나 오늘 시각이 이미 지났으면 다음 날,
     * 아니면 오늘이다. 하루의 occurrence는 발송·skip 어느 쪽이든 한 번만 처리된다.
     */
    static LocalDateTime computeNextDueAt(LocalDateTime nowKst, LocalTime notificationTime,
                                          LocalDate lastProcessedOccurrenceDate) {
        LocalDate today = nowKst.toLocalDate();
        LocalDateTime todayOccurrence = LocalDateTime.of(today, notificationTime);
        boolean processedToday = today.equals(lastProcessedOccurrenceDate);
        if (!processedToday && todayOccurrence.isAfter(nowKst)) {
            return todayOccurrence;
        }
        return LocalDateTime.of(today.plusDays(1), notificationTime);
    }

    /** 같은 전진 값을 공유하는 행을 한 문장으로 묶기 위한 그룹 키. */
    private record Advance(LocalDate occurrenceDate, LocalDateTime nextDueAt) {
    }

    /** 마스터·동의 batch 조회를 위해 claim 결과에서 subject를 뽑는 호출부 편의. */
    public static List<UUID> subjectIdsOf(Collection<ScheduledNotificationPreference> preferences) {
        return preferences.stream().map(ScheduledNotificationPreference::getSubjectId).distinct().toList();
    }

    private LocalDateTime nowKst() {
        return PushTimes.kstWallClock(clock.instant());
    }
}
