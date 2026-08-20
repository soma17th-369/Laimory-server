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
 * <p>{@code nextDueAt}은 항상 <b>현재 이후 첫 occurrence</b>이며 설정을 바꿀 때마다 다시 계산된다.
 * 하루 1회 캡은 두지 않는다 — 껐다 다시 켠 시점에 오늘 시각이 아직 미래면 오늘 또 온다(사용자 행동).
 *
 * <p>기본값은 ON/21:00이다(#318) — 전체 사용자에게 매일 같은 시각으로 일괄 발송하고 사용자는 끄기만
 * 한다. 시각은 서버가 고정하며 사용자 입력으로 바뀌지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ScheduledNotificationPreferenceService {

    static final boolean DEFAULT_ENABLED = true;
    static final LocalTime DEFAULT_NOTIFICATION_TIME = LocalTime.of(21, 0);
    private static final int MAX_BATCH_SIZE = 1_000;

    private final ScheduledNotificationPreferenceRepository scheduledNotificationPreferenceRepository;
    private final Clock clock;

    /** 가입 transaction 합류용 기본 ON/21:00 행 생성. 이미 있으면 no-op(멱등). */
    public void createDefaultIfAbsent(UUID subjectId, ScheduledNotificationType notificationType) {
        LocalDateTime nowKst = nowKst();
        scheduledNotificationPreferenceRepository.insertIfAbsent(
                subjectId.toString(),
                notificationType.name(),
                DEFAULT_ENABLED,
                DEFAULT_NOTIFICATION_TIME,
                computeNextDueAt(nowKst, DEFAULT_NOTIFICATION_TIME),
                nowKst);
    }

    /**
     * 설정 화면이 보여줄 현재 값 — <b>순수 읽기</b>다. 행이 없으면 기본값을 답한다(그 행을 만들어도 값이
     * 같으므로 조회가 쓰기를 할 이유가 없다). 행은 가입 transaction과 rollout backfill이 만든다.
     */
    public Settings findSettings(UUID subjectId, ScheduledNotificationType notificationType) {
        return find(subjectId, notificationType)
                .map(preference -> new Settings(preference.isEnabled(), preference.getNotificationTime()))
                .orElseGet(() -> new Settings(DEFAULT_ENABLED, DEFAULT_NOTIFICATION_TIME));
    }

    public Optional<ScheduledNotificationPreference> find(UUID subjectId,
                                                          ScheduledNotificationType notificationType) {
        return scheduledNotificationPreferenceRepository.findById(
                new ScheduledNotificationPreferenceId(subjectId, notificationType));
    }

    /**
     * 종류별 ON/OFF 전환 — {@code enabled}와 다음 예정 시각을 함께 바꾼다. 꺼져 있는 동안 과거가 된
     * {@code nextDueAt}을 그대로 켜면 켠 직후 tick이 곧바로 발송하므로, 저장된 시각 기준 다음 미래
     * occurrence로 재장전한다.
     *
     * <p>시각을 입력으로 받지 않으므로 저장된 값을 읽는다. 이 계산은 SQL에 맡길 수 없다 — JDBC가
     * {@code TIME} 값을 connection timezone으로 변환해 저장하므로 SQL 안에서 컬럼끼리 날짜를 파생하면
     * JVM zone에 따라 9시간 어긋난다(UTC 통합 테스트에서 실측).
     *
     * <p>행은 만들지 않는다 — 행 존재는 가입 transaction과 rollout backfill이 보장하고, 부재는 그
     * 보장이 깨졌다는 운영 신호다.
     */
    public void updateEnabled(UUID subjectId, ScheduledNotificationType notificationType, boolean enabled) {
        LocalTime notificationTime = find(subjectId, notificationType)
                .map(ScheduledNotificationPreference::getNotificationTime)
                .orElseThrow(() -> new IllegalStateException("scheduled notification preference row is missing"));
        LocalDateTime nextDueAt = computeNextDueAt(nowKst(), notificationTime);
        if (scheduledNotificationPreferenceRepository.updateEnabled(
                subjectId, notificationType, enabled, nextDueAt) == 0) {
            throw new IllegalStateException("scheduled notification preference row is missing");
        }
    }

    /**
     * due occurrence를 row lock으로 분리하고 같은 짧은 transaction에서 현재 시각 이후 첫 occurrence로
     * 옮긴다. 반환 시 transaction·row lock이 끝났으므로 호출자는 외부 I/O를 안전하게 수행할 수 있다.
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
        // JDBC의 timezone 변환 때문에 JVM zone에 따라 결과가 달라진다. 같은 다음 시각끼리 묶어 문장 수를
        // 줄인다(대부분 같은 시각을 쓰므로 보통 1~2개로 수렴).
        Map<LocalDateTime, List<UUID>> subjectsByNextDueAt = new LinkedHashMap<>();
        for (ScheduledNotificationPreference preference : due) {
            subjectsByNextDueAt
                    .computeIfAbsent(computeNextDueAt(nowKst, preference.getNotificationTime()),
                            key -> new ArrayList<>())
                    .add(preference.getSubjectId());
        }
        int advanced = 0;
        for (Map.Entry<LocalDateTime, List<UUID>> entry : subjectsByNextDueAt.entrySet()) {
            advanced += scheduledNotificationPreferenceRepository.advanceNextDueAt(
                    notificationType, entry.getValue(), entry.getKey());
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

    /** 다음 예정 시각 — 현재 이후 첫 occurrence다(오늘 시각이 아직 미래면 오늘, 아니면 다음 날). */
    static LocalDateTime computeNextDueAt(LocalDateTime nowKst, LocalTime notificationTime) {
        LocalDate today = nowKst.toLocalDate();
        LocalDateTime todayOccurrence = LocalDateTime.of(today, notificationTime);
        if (todayOccurrence.isAfter(nowKst)) {
            return todayOccurrence;
        }
        return LocalDateTime.of(today.plusDays(1), notificationTime);
    }

    /** 설정 화면이 보여줄 값. 행이 없으면 기본값이 담긴다. */
    public record Settings(boolean enabled, LocalTime notificationTime) {
    }

    /** 마스터 batch 조회를 위해 claim 결과에서 subject를 뽑는 호출부 편의. */
    public static List<UUID> subjectIdsOf(Collection<ScheduledNotificationPreference> preferences) {
        return preferences.stream().map(ScheduledNotificationPreference::getSubjectId).distinct().toList();
    }

    private LocalDateTime nowKst() {
        return PushTimes.kstWallClock(clock.instant());
    }
}
