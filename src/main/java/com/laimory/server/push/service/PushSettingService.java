package com.laimory.server.push.service;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.dto.PushSettingsResponse;
import java.time.format.DateTimeFormatter;
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
     * 조회 응답의 시각 표기 형식. 시각은 서버 고정이라 입력으로 받지 않으며 이 formatter는 출력 전용이다
     * (#318 — 앱은 이 값을 "매일 HH:mm" 안내 문구에 쓴다).
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final PushPreferenceService pushPreferenceService;
    private final ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;

    /**
     * 서버 권위 상태 조회 — 순수 읽기다. 설정 행이 없으면 기본값으로 가리지 않고 던진다(조회·발송·쓰기가
     * 한 방향을 가리킨다). 행은 가입 transaction과 rollout backfill이 만든다.
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

    /** 예정 알림 마스터 ON/OFF — 종류별 설정값·시각은 보존한다. */
    public void updatePushEnabled(String applicationVersion, UUID subjectId, Boolean enabled) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        pushPreferenceService.updatePushEnabled(subjectId, requireEnabled(enabled));
    }

    /** 일일 리마인더 ON/OFF — 기본이 ON이라 이 쓰기는 사용자가 끄는(또는 다시 켜는) 유일한 수단이다. */
    public void updateDailyReminderEnabled(String applicationVersion, UUID subjectId, Boolean enabled) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        scheduledNotificationPreferenceService.updateEnabled(subjectId, DAILY_REMINDER, requireEnabled(enabled));
    }

    private static boolean requireEnabled(Boolean enabled) {
        if (enabled == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        return enabled;
    }
}
