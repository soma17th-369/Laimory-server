package com.laimory.server.push.service;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.dto.PushSettingsResponse;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 푸시 설정 화면의 use case orchestration — 마스터와 종류별 설정 두 leaf service를 합성한다.
 * repository를 직접 주입하지 않으며 각 저장소의 transaction 경계는 leaf service가 소유한다.
 */
@Service
@RequiredArgsConstructor
public class PushSettingService {

    private static final ScheduledNotificationType DAILY_REMINDER = ScheduledNotificationType.DAILY_REMINDER;
    /**
     * {@code HH:mm} 고정 파싱. STRICT resolver라 {@code 24:00} 같은 범위 밖 값이 조용히 {@code 00:00}으로
     * 정규화되지 않는다 — 사용자가 의도하지 않은 시각에 알림이 가지 않게 입력 단계에서 거절한다.
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private final PushPreferenceService pushPreferenceService;
    private final ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;

    /**
     * 서버 권위 상태 조회 — 순수 읽기다. 설정 행이 아직 없는 사용자에게는 기본값을 답하고 행을 만들지
     * 않는다(만들어도 값이 같다). 행은 가입 transaction과 rollout backfill이 만들며, 그래도 없으면 첫
     * 설정 변경이 만든다.
     */
    public PushSettingsResponse getSettings(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        ScheduledNotificationPreferenceService.Settings dailyReminder =
                scheduledNotificationPreferenceService.findSettings(subjectId, DAILY_REMINDER);
        return new PushSettingsResponse(
                pushPreferenceService.findPushEnabled(subjectId),
                new PushSettingsResponse.DailyReminder(
                        dailyReminder.enabled(),
                        TIME_FORMAT.format(dailyReminder.notificationTime())));
    }

    /** 전체 푸시 마스터 ON/OFF — 종류별 설정값·시각은 보존한다. */
    public void updatePushEnabled(String applicationVersion, UUID subjectId, Boolean enabled) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        pushPreferenceService.updatePushEnabled(subjectId, requireEnabled(enabled));
    }

    /** 일일 리마인더 ON/OFF — 사용자가 직접 켜야 발송된다(기본 OFF). */
    public void updateDailyReminderEnabled(String applicationVersion, UUID subjectId, Boolean enabled) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        scheduledNotificationPreferenceService.updateEnabled(subjectId, DAILY_REMINDER, requireEnabled(enabled));
    }

    /** 일일 리마인더 시각 변경 — OFF 상태에서도 저장하며 발송 여부는 {@code enabled}가 정한다. */
    public void updateDailyReminderTime(String applicationVersion, UUID subjectId, String time) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        scheduledNotificationPreferenceService.updateNotificationTime(
                subjectId, DAILY_REMINDER, parseTime(time));
    }

    /** 분 단위 {@code HH:mm}만 받는다 — 초·나노 표기와 한 자리 시각은 DB 변경 전에 거절한다. */
    static LocalTime parseTime(String time) {
        if (time == null || time.isBlank()) {
            throw new IllegalArgumentException("time is required");
        }
        try {
            return LocalTime.parse(time, TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("time must be in HH:mm format");
        }
    }

    private static boolean requireEnabled(Boolean enabled) {
        if (enabled == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        return enabled;
    }


}
