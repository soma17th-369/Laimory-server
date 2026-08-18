package com.laimory.server.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 광고성 알림에 표기하고 동의 증적에 남기는 전송자 정보. 값은 법무가 확정한 법인명·연락처이며 브랜드
 * 표시명이나 운영 편의 문구가 아니다.
 *
 * <p>기본값은 빈 문자열이라 설정 API만 배포한 단계에서도 기동한다. 실제로 광고성 발송을 켤 때
 * ({@code DailyReminderWorkerProperties})와 동의 증적을 만들 때 non-blank를 요구한다 — 확정 전 값이
 * 사용자 통지나 증적에 빈 문자열로 남지 않게 한다.
 */
@Component
public class PushSenderProperties {

    private final String senderName;
    private final String senderContact;

    public PushSenderProperties(@Value("${app.push.sender-name:}") String senderName,
                                @Value("${app.push.sender-contact:}") String senderContact) {
        this.senderName = senderName == null ? "" : senderName.trim();
        this.senderContact = senderContact == null ? "" : senderContact.trim();
    }

    public boolean isConfigured() {
        return !senderName.isBlank() && !senderContact.isBlank();
    }

    public String senderName() {
        return senderName;
    }

    public String senderContact() {
        return senderContact;
    }

    /**
     * 증적·표기에 쓸 전송자 법인명. 미설정 상태에서 광고 관련 경로가 호출되면 빈 값을 남기는 대신
     * 구성 오류로 실패시킨다(약관 gate의 구성 오류 처리 선례).
     */
    public String requireSenderName() {
        if (senderName.isBlank()) {
            throw new IllegalStateException("app.push.sender-name is required for advertising consent records");
        }
        return senderName;
    }
}
