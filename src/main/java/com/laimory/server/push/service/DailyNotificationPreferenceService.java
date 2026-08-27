package com.laimory.server.push.service;

import com.laimory.server.push.PushTimes;
import com.laimory.server.push.entity.DailyNotificationPreference;
import com.laimory.server.push.repository.DailyNotificationPreferenceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일일 알림 설정과 occurrence 스케줄 상태의 단일 관문.
 *
 * <p>{@code nextDueAt}은 항상 <b>현재 이후 첫 occurrence</b>이며 ON/OFF를 바꿀 때마다 다시 계산된다.
 *
 * <p>기본값은 ON이고 발송 시각은 서버가 21:00으로 고정한다(#318) — 전체 사용자에게 매일 같은 시각으로
 * 일괄 발송하고 사용자는 끄기만 한다. 시각은 DB가 아니라 이 클래스의 상수가 소유하므로 사용자 입력으로도
 * 운영 SQL로도 바뀌지 않는다.
 */
@Service
@RequiredArgsConstructor
public class DailyNotificationPreferenceService {

    static final boolean DEFAULT_ENABLED = true;
    /** 서버가 고정한 발송 시각(KST) — 값의 권위는 DB가 아니라 여기다(#321). */
    static final LocalTime NOTIFICATION_TIME = LocalTime.of(21, 0);
    private static final int MAX_BATCH_SIZE = 1_000;

    private final DailyNotificationPreferenceRepository dailyNotificationPreferenceRepository;
    private final Clock clock;

    /** 가입 transaction 합류용 기본 ON 행 생성. 이미 있으면 no-op(멱등). */
    public void createDefaultIfAbsent(UUID subjectId) {
        LocalDateTime nowKst = nowKst();
        dailyNotificationPreferenceRepository.insertIfAbsent(
                subjectId.toString(), DEFAULT_ENABLED, computeNextDueAt(nowKst), nowKst);
    }

    /**
     * 설정 화면이 보여줄 현재 값 — <b>순수 읽기</b>다. 행이 없으면 쓰기와 같은 이유로 던진다.
     *
     * <p>기본값으로 답하지 않는다. 기본이 ON이 된 뒤로(#318) 행 부재를 기본값으로 가리면 "리마인더 켜짐"이라고
     * 답하면서 정작 worker는 없는 행을 claim하지 못해 아무것도 보내지 않고, 사용자가 끄려 해도 쓰기가
     * 던진다. 행 존재는 가입 transaction과 rollout backfill이 보장하며 부재는 그 보장이 깨졌다는
     * 운영 신호다 — 읽기도 같은 신호를 내야 조회·발송·쓰기가 한 방향을 가리킨다.
     */
    public Settings findSettings(UUID subjectId) {
        return find(subjectId)
                .map(preference -> new Settings(preference.isEnabled(), NOTIFICATION_TIME))
                .orElseThrow(() -> new IllegalStateException("daily notification preference row is missing"));
    }

    public Optional<DailyNotificationPreference> find(UUID subjectId) {
        return dailyNotificationPreferenceRepository.findById(subjectId);
    }

    /**
     * ON/OFF 전환 — {@code enabled}와 다음 예정 시각을 함께 바꾸는 UPDATE 한 문장이다. 꺼져 있는 동안
     * 과거가 된 {@code nextDueAt}을 그대로 켜면 그 값이 허용 지연 안쪽일 때 21:00 run이 곧바로 발송하므로
     * 다음 미래 occurrence로 재장전한다. 시각이 서버 고정이라 그 값을 알아내려고 행을 읽을 필요가 없다.
     *
     * <p>행은 만들지 않는다 — 행 존재는 가입 transaction과 rollout backfill이 보장하고, 0행은 그
     * 보장이 깨졌다는 운영 신호다.
     */
    public void updateEnabled(UUID subjectId, boolean enabled) {
        if (dailyNotificationPreferenceRepository.updateEnabled(
                subjectId, enabled, computeNextDueAt(nowKst())) == 0) {
            throw new IllegalStateException("daily notification preference row is missing");
        }
    }

    /**
     * due occurrence를 row lock으로 분리하고 같은 짧은 transaction에서 현재 시각 이후 첫 occurrence로
     * 옮긴다. 반환 시 transaction·row lock이 끝났으므로 호출자는 외부 I/O를 안전하게 수행할 수 있다.
     *
     * <p>허용 지연을 넘긴 행도 함께 claim한다 — 발송 대상 판정은 호출자가 반환된 {@code nextDueAt}으로
     * 하고, 여기서는 오래된 행이 다음 run에서 다시 선택되지 않도록 전진만 보장한다.
     *
     * @return claim한 행들(값은 claim 시점 상태 — {@code nextDueAt}은 방금 처리한 occurrence 시각이다)
     */
    @Transactional
    public List<DailyNotificationPreference> claimDue(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
        }
        LocalDateTime nowKst = nowKst();
        List<DailyNotificationPreference> due =
                dailyNotificationPreferenceRepository.findDueForUpdateSkipLocked(nowKst, limit);
        if (due.isEmpty()) {
            return List.of();
        }
        // 전진 값은 Java에서 KST로 계산한다 — DB 안에서 시각을 파생하면 JDBC의 timezone 변환 때문에
        // JVM zone에 따라 결과가 달라진다. 시각이 서버 고정이라 claim된 행 전부가 같은 값을 갖는다.
        int advanced = dailyNotificationPreferenceRepository.advanceNextDueAt(
                subjectIdsOf(due), computeNextDueAt(nowKst));
        if (advanced != due.size()) {
            throw new IllegalStateException("daily notification claim count mismatch");
        }
        return List.copyOf(due);
    }

    /** 다음 예정 시각 — 현재 이후 첫 occurrence다(오늘 고정 시각이 아직 미래면 오늘, 아니면 다음 날). */
    static LocalDateTime computeNextDueAt(LocalDateTime nowKst) {
        LocalDate today = nowKst.toLocalDate();
        LocalDateTime todayOccurrence = LocalDateTime.of(today, NOTIFICATION_TIME);
        if (todayOccurrence.isAfter(nowKst)) {
            return todayOccurrence;
        }
        return LocalDateTime.of(today.plusDays(1), NOTIFICATION_TIME);
    }

    /** 설정 화면이 보여줄 값 — 시각은 서버 고정 상수다. */
    public record Settings(boolean enabled, LocalTime notificationTime) {
    }

    /** 마스터 batch 조회를 위해 claim 결과에서 subject를 뽑는 호출부 편의. */
    public static List<UUID> subjectIdsOf(Collection<DailyNotificationPreference> preferences) {
        return preferences.stream().map(DailyNotificationPreference::getSubjectId).distinct().toList();
    }

    private LocalDateTime nowKst() {
        return PushTimes.kstWallClock(clock.instant());
    }
}
