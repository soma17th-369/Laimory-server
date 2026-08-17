package com.laimory.server.push.service;

import com.laimory.server.push.repository.PushRegistrationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
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
    public void register(String applicationVersion, UUID subjectId, String firebaseInstallationId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        validate(firebaseInstallationId);
        pushRegistrationRepository.upsert(subjectId.toString(), firebaseInstallationId, LocalDateTime.now(clock));
    }

    /** owner 조건 해제 — 미존재 등록도 성공(멱등). 계정 전환으로 재결합된 등록은 이전 사용자가 못 지운다. */
    public void unregister(String applicationVersion, UUID subjectId, String firebaseInstallationId) {
        validate(firebaseInstallationId);
        pushRegistrationRepository.deleteBySubjectIdAndFirebaseInstallationId(subjectId, firebaseInstallationId);
    }

    /** 사용자의 활성 설치 전체 FID(발송 대상). */
    public List<String> findFirebaseInstallationIds(UUID subjectId) {
        return pushRegistrationRepository.findAllFirebaseInstallationIdsBySubjectId(subjectId);
    }

    /**
     * 탈퇴 transaction 합류용(#305) — subject의 push 등록 전부 삭제(멱등, 0행 허용).
     * repository delete가 REQUIRED 전파로 호출자 transaction에 합류한다.
     */
    public void unregisterAllForSubject(UUID subjectId) {
        pushRegistrationRepository.deleteAllBySubjectId(subjectId);
    }

    /**
     * FCM이 영구 무효로 판정한 FID 등록 제거. 빈 목록이면 query 없이 no-op.
     * {@code snapshotAt}(발송 대상 조회 시각) 이후 갱신된 재등록은 지우지 않는다 — 지연 도착한 무효 응답이
     * 최신 등록을 삭제하는 레이스 차단.
     */
    public void removeInvalidRegistrations(Collection<String> firebaseInstallationIds, LocalDateTime snapshotAt) {
        if (firebaseInstallationIds.isEmpty()) {
            return;
        }
        pushRegistrationRepository.deleteInvalidRegistrations(firebaseInstallationIds, snapshotAt);
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
