package com.laimory.server.push.service;

import com.laimory.server.push.repository.PushRegistrationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * FID 등록·해제와 발송 대상 조회. FID는 opaque 식별자다 — trim·대소문자 변환·형식 재작성 없이 그대로
 * 저장·비교하고, 원문은 application log·예외 메시지에 남기지 않는다(길이 위반 메시지도 길이만 언급).
 */
@Service
@RequiredArgsConstructor
public class PushRegistrationService {

    static final int MAX_FIREBASE_INSTALLATION_ID_LENGTH = 255;

    private final PushRegistrationRepository pushRegistrationRepository;
    private final Clock clock;

    /** 등록·갱신·계정 전환(원자 재결합). 같은 사용자·FID 재등록은 멱등 성공이며 freshness만 갱신된다. */
    public void register(String applicationVersion, long userId, String firebaseInstallationId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        validate(firebaseInstallationId);
        pushRegistrationRepository.upsert(userId, firebaseInstallationId, LocalDateTime.now(clock));
    }

    /** owner 조건 해제 — 미존재 등록도 성공(멱등). 계정 전환으로 재결합된 등록은 이전 사용자가 못 지운다. */
    public void unregister(String applicationVersion, long userId, String firebaseInstallationId) {
        validate(firebaseInstallationId);
        pushRegistrationRepository.deleteByUserIdAndFirebaseInstallationId(userId, firebaseInstallationId);
    }

    /** 사용자의 활성 설치 전체 FID(발송 대상). */
    public List<String> findFirebaseInstallationIds(long userId) {
        return pushRegistrationRepository.findAllFirebaseInstallationIdsByUserId(userId);
    }

    /** FCM이 영구 무효로 판정한 FID 등록 제거. 빈 목록이면 query 없이 no-op. */
    public void removeInvalidRegistrations(Collection<String> firebaseInstallationIds) {
        if (firebaseInstallationIds.isEmpty()) {
            return;
        }
        pushRegistrationRepository.deleteAllByFirebaseInstallationIdIn(firebaseInstallationIds);
    }

    private static void validate(String firebaseInstallationId) {
        if (firebaseInstallationId == null || firebaseInstallationId.isBlank()) {
            throw new IllegalArgumentException("firebaseInstallationId is required");
        }
        if (firebaseInstallationId.length() > MAX_FIREBASE_INSTALLATION_ID_LENGTH) {
            throw new IllegalArgumentException("firebaseInstallationId must be at most "
                    + MAX_FIREBASE_INSTALLATION_ID_LENGTH + " characters");
        }
    }
}
