package com.laimory.server.push.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.PushComplianceClass;
import com.laimory.server.push.PushTimes;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.dto.NotificationConsentResultResponse;
import com.laimory.server.push.dto.PushSettingsResponse;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.service.NotificationConsentService.ConsentState;
import com.laimory.server.terms.service.TermDocumentService;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 푸시 설정 화면의 use case orchestration — 마스터·종류별 설정·법적 동의 세 leaf service를 합성한다.
 * repository를 직접 주입하지 않으며 각 저장소의 transaction 경계는 leaf service가 소유한다.
 *
 * <p>여기서 지키는 계약은 "부분 변경 없음"이다. 광고성 알림을 켜거나 야간 시각으로 바꾸는 데 필요한
 * 동의가 없으면 어떤 값도 바꾸지 않고 409로 거절한다. 저장 시각 기준 검사는 설정 단계에서 빨리
 * 거절하기 위한 것이고, 실제 발송의 최종 야간 판정 권위는 sender의 전송 직전 재판정이다.
 */
@Service
@RequiredArgsConstructor
public class PushSettingService {

    /** 설정 화면이 다시 보여줄 최근 처리결과 창. */
    static final Duration RECENT_CONSENT_WINDOW = Duration.ofDays(14);
    private static final ScheduledNotificationType DAILY_REMINDER = ScheduledNotificationType.DAILY_REMINDER;
    /**
     * {@code HH:mm} 고정 파싱. STRICT resolver라 {@code 24:00} 같은 범위 밖 값이 조용히 {@code 00:00}으로
     * 정규화되지 않는다 — 사용자가 의도하지 않은 시각에 알림이 가지 않게 입력 단계에서 거절한다.
     */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private final PushPreferenceService pushPreferenceService;
    private final ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    private final NotificationConsentService notificationConsentService;
    private final TermDocumentService termDocumentService;

    /** 서버 권위 상태 조회 — 누락 행은 기본값으로 응답하면서 같은 request에서 멱등 보정한다. */
    public PushSettingsResponse getSettings(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        boolean pushEnabled = pushPreferenceService.getOrCreatePushEnabled(subjectId);
        ScheduledNotificationPreference dailyReminder =
                scheduledNotificationPreferenceService.getOrCreate(subjectId, DAILY_REMINDER);
        ConsentState consent = notificationConsentService.getOrCreateState(subjectId);
        List<NotificationConsentEvent> recent =
                notificationConsentService.findRecentEvents(subjectId, RECENT_CONSENT_WINDOW);

        return new PushSettingsResponse(
                pushEnabled,
                new PushSettingsResponse.DailyReminder(
                        dailyReminder.isEnabled(),
                        TIME_FORMAT.format(dailyReminder.getNotificationTime()),
                        DAILY_REMINDER.complianceClass()),
                consentStatus(consent.advertisingConsented(), consent.advertisingTermDocumentId()),
                consentStatus(consent.nightAdvertisingConsented(), consent.nightTermDocumentId()),
                NotificationConsentResultResponse.from(recent));
    }

    /** 전체 푸시 마스터 ON/OFF — 종류별 설정값·시각은 보존한다. */
    public void updatePushEnabled(String applicationVersion, UUID subjectId, Boolean enabled) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        pushPreferenceService.updatePushEnabled(subjectId, requireEnabled(enabled));
    }

    /**
     * 일일 리마인더 ON/OFF. 광고성으로 확정된 알림을 켜려면 광고 동의가 있어야 하고, 저장된 시각이
     * 야간이면 야간 동의도 있어야 한다. OFF 전환에는 동의를 요구하지 않는다.
     */
    public void updateDailyReminderEnabled(String applicationVersion, UUID subjectId, Boolean enabled) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        boolean target = requireEnabled(enabled);
        ScheduledNotificationPreference preference =
                scheduledNotificationPreferenceService.getOrCreate(subjectId, DAILY_REMINDER);
        if (target && DAILY_REMINDER.complianceClass() == PushComplianceClass.ADVERTISING) {
            requireAdvertisingConsent(subjectId, preference.getNotificationTime());
        }
        scheduledNotificationPreferenceService.updateEnabled(subjectId, DAILY_REMINDER, target);
    }

    /**
     * 일일 리마인더 시각 변경. 이미 켜져 있는 광고성 알림을 <b>야간 시각으로</b> 옮길 때만 야간 동의를
     * 요구한다 — 주간 시각으로 옮기는 것은 수신 범위를 넓히지 않으므로 동의를 묻지 않는다.
     *
     * <p>일반 광고 동의는 여기서 검사하지 않는다. 검사하면 수신거부로 동의를 철회한 사용자(그 경우
     * {@code enabled}는 true로 남는다)가 시각을 주간으로 바꾸는 것조차 막혀, 리마인더를 끄거나 재동의하는
     * 것 말고는 빠져나갈 수 없는 상태가 된다. 동의 없는 발송은 worker가 발송 시점에 다시 막는다.
     *
     * <p>OFF 상태에서는 동의 없이 야간 시각을 저장할 수 있고 발송은 계속 차단된다.
     */
    public void updateDailyReminderTime(String applicationVersion, UUID subjectId, String time) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        LocalTime notificationTime = parseTime(time);
        ScheduledNotificationPreference preference =
                scheduledNotificationPreferenceService.getOrCreate(subjectId, DAILY_REMINDER);
        if (preference.isEnabled() && DAILY_REMINDER.complianceClass() == PushComplianceClass.ADVERTISING
                && PushTimes.isNight(notificationTime)) {
            requireNightAdvertisingConsent(subjectId);
        }
        scheduledNotificationPreferenceService.updateNotificationTime(subjectId, DAILY_REMINDER, notificationTime);
    }

    /** 광고성 수신 동의·철회 — 철회는 야간 동의도 함께 내린다. */
    public List<NotificationConsentEvent> applyAdvertisingConsent(String applicationVersion, UUID subjectId,
                                                                   UUID clientRequestId, Boolean consented,
                                                                   String termVersion) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return notificationConsentService.apply(subjectId, requireClientRequestId(clientRequestId),
                NotificationConsentType.ADVERTISING_PUSH, requireConsented(consented), termVersion,
                NotificationConsentSource.PUSH_SETTINGS);
    }

    /** 야간 광고성 수신 동의·철회 — 동의는 일반 광고 동의가 ON일 때만 가능하다. */
    public List<NotificationConsentEvent> applyNightAdvertisingConsent(String applicationVersion, UUID subjectId,
                                                                        UUID clientRequestId, Boolean consented,
                                                                        String termVersion) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return notificationConsentService.apply(subjectId, requireClientRequestId(clientRequestId),
                NotificationConsentType.NIGHT_ADVERTISING_PUSH, requireConsented(consented), termVersion,
                NotificationConsentSource.PUSH_SETTINGS);
    }

    /** 광고성 알림 활성화 조건 — 일반 동의는 항상, 야간 시각이면 야간 동의도 필요하다. */
    private void requireAdvertisingConsent(UUID subjectId, LocalTime notificationTime) {
        ConsentState consent = notificationConsentService.getOrCreateState(subjectId);
        if (!consent.advertisingConsented()) {
            throw new BusinessException(ExceptionType.NOTIFICATION_CONSENT_REQUIRED);
        }
        if (PushTimes.isNight(notificationTime) && !consent.nightAdvertisingConsented()) {
            throw new BusinessException(ExceptionType.NOTIFICATION_CONSENT_REQUIRED);
        }
    }

    /**
     * 야간 시각으로 옮길 때의 조건. 야간 동의가 ON이면 일반 광고 동의도 ON이라는 불변식이 있으므로
     * 야간 동의만 확인하면 충분하다.
     */
    private void requireNightAdvertisingConsent(UUID subjectId) {
        if (!notificationConsentService.getOrCreateState(subjectId).nightAdvertisingConsented()) {
            throw new BusinessException(ExceptionType.NOTIFICATION_CONSENT_REQUIRED);
        }
    }

    private PushSettingsResponse.ConsentStatus consentStatus(boolean consented, Long termDocumentId) {
        String version = consented
                ? termDocumentService.findVersionById(termDocumentId).orElse(null)
                : null;
        return new PushSettingsResponse.ConsentStatus(consented, version);
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

    private static boolean requireConsented(Boolean consented) {
        if (consented == null) {
            throw new IllegalArgumentException("consented is required");
        }
        return consented;
    }

    private static UUID requireClientRequestId(UUID clientRequestId) {
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        return clientRequestId;
    }
}
