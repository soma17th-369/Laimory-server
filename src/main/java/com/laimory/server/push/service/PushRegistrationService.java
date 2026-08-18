package com.laimory.server.push.service;

import com.laimory.server.push.OptOutTokens;
import com.laimory.server.push.entity.PushRegistration;
import com.laimory.server.push.repository.PushRegistrationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /**
     * 등록·갱신·계정 전환(원자 재결합). 같은 사용자·FID 재등록은 멱등 성공이며 freshness만 갱신된다.
     *
     * <p>{@code optOutToken}은 Android가 installation별로 만들어 보관하는 수신거부 credential이다.
     * 서버는 형식만 검증하고 SHA-256 hash만 저장하며, 값이 없으면 기존 hash를 지운다 — 구버전 앱으로
     * 되돌아간 설치가 유효하지 않은 수신거부 수단을 남기지 않게 한다.
     */
    public void register(String applicationVersion, UUID subjectId, String firebaseInstallationId,
                         String optOutToken) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        validate(firebaseInstallationId);
        String optOutTokenHash = optOutToken == null || optOutToken.isBlank()
                ? null : OptOutTokens.hash(optOutToken);
        pushRegistrationRepository.upsert(subjectId.toString(), firebaseInstallationId, optOutTokenHash,
                LocalDateTime.now(clock));
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

    /** 정보성 발송의 subject별 활성 FID batch 조회 — 설치가 없는 subject는 결과에서 빠진다. */
    public Map<UUID, List<String>> findFirebaseInstallationIdsBySubjectIds(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return groupBySubject(pushRegistrationRepository.findAllBySubjectIdIn(subjectIds));
    }

    /**
     * 광고성 발송의 subject별 활성 FID batch 조회 — 수신거부 token을 가진 설치만 포함한다.
     * 알림에서 바로 수신거부할 수 없는 설치에는 광고성 알림을 보내지 않는다.
     */
    public Map<UUID, List<String>> findTokenCapableFirebaseInstallationIdsBySubjectIds(
            Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        return groupBySubject(pushRegistrationRepository.findTokenCapableBySubjectIdIn(subjectIds));
    }

    /**
     * 비로그인 수신거부용 등록 조회 — 호출자 transaction 안에서 FID 행을 잠근다. owner subject와 token
     * hash를 같은 잠금 아래에서 읽어야 계정 전환과 경합해도 현재 owner에게만 철회가 적용된다.
     */
    public Optional<PushRegistration> findForOptOut(String firebaseInstallationId) {
        if (firebaseInstallationId == null || firebaseInstallationId.isBlank()) {
            return Optional.empty();
        }
        return pushRegistrationRepository.findByFirebaseInstallationIdForUpdate(firebaseInstallationId);
    }

    private static Map<UUID, List<String>> groupBySubject(List<SubjectInstallation> installations) {
        return installations.stream().collect(Collectors.groupingBy(SubjectInstallation::subjectId,
                Collectors.mapping(SubjectInstallation::firebaseInstallationId, Collectors.toList())));
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
