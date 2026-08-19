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
 * <p>{@code nextDueAt}은 항상 <b>현재 이후 첫 occurrence</b>다(오늘 시각이 아직 미래면 오늘, 아니면
 * 다음 날). 하루 1회 캡은 두지 않는다 — 시각 변경은 사용자 행동이므로, 오늘 발송을 이미 받았어도 새
 * 시각이 미래면 그 시각으로 재장전되어 오늘 다시 올 수 있다.
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
                computeNextDueAt(nowKst, DEFAULT_NOTIFICATION_TIME),
                nowKst);
    }

    /**
     * 설정 화면이 보여줄 현재 값 — <b>순수 읽기</b>다. 행이 없으면 기본값을 답한다(그 행을 만들어도 값이
     * 같으므로 조회가 쓰기를 할 이유가 없다). 행은 가입 transaction과 rollout backfill이 만들고,
     * 그래도 없으면 첫 설정 변경이 만든다.
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
     * 종류별 ON/OFF 전환 — {@code enabled} 한 컬럼만 바꾸는 한 문장이다. 행이 없으면 그때만 만들고 다시
     * 시도한다.
     *
     * <p>세 문장을 한 transaction으로 묶지 않는다. 묶으면 대상이 없는 첫 UPDATE가 잡은 gap lock을 계속
     * 쥔 채 INSERT를 시도하게 되고, 같은 행을 동시에 처음 만드는 두 요청이 서로의 gap lock을 기다려
     * deadlock에 빠진다. 문장별로 끊어도 각 문장이 멱등이라 최종 결과는 같다.
     *
     * <p>재시도 UPDATE가 그래도 0행이면 던진다 — {@code INSERT IGNORE}는 FK 위반(마스터 행 부재)도
     * warning으로 삼키므로, 여기서 확인하지 않으면 아무것도 저장하지 않은 요청이 200으로 끝난다.
     */
    public void updateEnabled(UUID subjectId, ScheduledNotificationType notificationType, boolean enabled) {
        if (scheduledNotificationPreferenceRepository.updateEnabled(subjectId, notificationType, enabled) == 0) {
            createDefaultIfAbsent(subjectId, notificationType);
            if (scheduledNotificationPreferenceRepository.updateEnabled(subjectId, notificationType, enabled)
                    == 0) {
                throw new IllegalStateException("scheduled notification preference write was lost");
            }
        }
    }

    /**
     * 시각 변경 — 시각과 새 시각의 다음 미래 occurrence를 한 문장에서 함께 바꾼다. 그래서 worker claim이나
     * 다른 설정 변경과 겹쳐도 두 값이 어긋난 상태가 남지 않는다.
     *
     * <p>{@link #updateEnabled}와 같은 이유로 문장들을 한 transaction으로 묶지 않으며, 재시도 UPDATE가
     * 0행이면 같은 이유로 던진다.
     *
     * <p>쓰기 직후 값을 한 번 재검증한다 — {@code nextDueAt}은 행 lock을 잡기 전에 계산되므로, lock을
     * 기다리는 사이 worker claim이 그 occurrence를 이미 처리했다면 방금 쓴 값이 과거일 수 있다. 그대로
     * 두면 다음 tick이 허용 지연 안에서 같은 occurrence를 중복 발송하므로, 값이 그대로일 때만 하루 뒤로
     * 민다(다른 전진이 이미 지나갔으면 0행 no-op — 멱등 보정).
     */
    public void updateNotificationTime(UUID subjectId, ScheduledNotificationType notificationType,
                                       LocalTime notificationTime) {
        LocalDateTime nextDueAt = computeNextDueAt(nowKst(), notificationTime);
        if (scheduledNotificationPreferenceRepository.updateNotificationTime(subjectId, notificationType,
                notificationTime, nextDueAt) == 0) {
            createDefaultIfAbsent(subjectId, notificationType);
            if (scheduledNotificationPreferenceRepository.updateNotificationTime(subjectId, notificationType,
                    notificationTime, nextDueAt) == 0) {
                throw new IllegalStateException("scheduled notification preference write was lost");
            }
        }
        if (!nextDueAt.isAfter(nowKst())) {
            scheduledNotificationPreferenceRepository.updateNextDueAtIfUnchanged(
                    subjectId, notificationType, nextDueAt, nextDueAt.plusDays(1));
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
