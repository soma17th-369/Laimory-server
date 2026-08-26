package com.laimory.server.push.service;

import com.laimory.server.push.PushTimes;
import com.laimory.server.push.entity.SubjectPreference;
import com.laimory.server.push.repository.SubjectPreferenceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * subject 축 설정 버킷의 단일 관문 — 예정 알림 마스터와 앱 온보딩 완료 여부(#382)를 함께 소유한다.
 * 신규 행은 마스터 ON·온보딩 false이며, 행을 만드는 것은 가입 transaction과 rollout backfill뿐이다 —
 * 설정 쓰기는 행을 만들지 않는다.
 *
 * <p>행 부재는 깨진 불변식이라 조회·쓰기 모두 던진다. 예외는 worker의 batch 조회 하나로, 거기서는
 * 한 행 때문에 batch 전체를 실패시키지 않고 결과에서 빠진 subject를 발송 대상에서 제외한다.
 */
@Service
@RequiredArgsConstructor
public class SubjectPreferenceService {

    static final boolean DEFAULT_PUSH_ENABLED = true;
    static final boolean DEFAULT_ONBOARDING_COMPLETED = false;

    private final SubjectPreferenceRepository subjectPreferenceRepository;
    private final Clock clock;

    /**
     * 가입 transaction 합류용 기본 행 생성(마스터 ON·온보딩 미완료). 이미 있으면 no-op(멱등).
     * 두 기본값을 INSERT에 명시한다 — DB default에만 암묵 의존하면 기본값의 권위가 두 곳으로 갈린다.
     */
    public void createDefaultIfAbsent(UUID subjectId) {
        subjectPreferenceRepository.insertIfAbsent(subjectId.toString(), DEFAULT_PUSH_ENABLED,
                DEFAULT_ONBOARDING_COMPLETED, auditNow());
    }

    /** 설정 화면이 보여줄 현재 값 — <b>순수 읽기</b>다. 행이 없으면 쓰기와 같은 이유로 던진다. */
    public boolean findPushEnabled(UUID subjectId) {
        return subjectPreferenceRepository.findById(subjectId)
                .map(SubjectPreference::isPushEnabled)
                .orElseThrow(() -> new IllegalStateException("subject preference row is missing"));
    }

    /**
     * 앱 시작 화면이 분기에 쓸 온보딩 완료 여부 — <b>순수 읽기</b>다(#382). 저장값을 그대로 반환하며
     * 약관 동의 이력에서 계산하지 않는다. 행이 없으면 {@code false}로 추정하거나 조회 중 만들지 않고
     * {@link #findPushEnabled}와 같은 이유로 던진다.
     */
    public boolean findOnboardingCompleted(UUID subjectId) {
        return subjectPreferenceRepository.findById(subjectId)
                .map(SubjectPreference::isOnboardingCompleted)
                .orElseThrow(() -> new IllegalStateException("subject preference row is missing"));
    }

    /**
     * 마스터 ON/OFF 변경(멱등) — 한 컬럼만 바꾸는 UPDATE 한 문장이다.
     *
     * <p>쓰기 경로는 행을 만들지 않는다. 행 존재는 가입 transaction과 rollout backfill이 보장하며,
     * 0행은 그 보장이 깨졌다는 운영 신호라 조용히 넘기지 않고 던진다.
     */
    public void updatePushEnabled(UUID subjectId, boolean pushEnabled) {
        if (subjectPreferenceRepository.updatePushEnabled(subjectId, pushEnabled) == 0) {
            throw new IllegalStateException("subject preference row is missing");
        }
    }

    /**
     * 온보딩 완료 기록(#382) — {@code false → true} 단방향 전이이며 되돌리는 짝은 두지 않는다.
     * 이미 완료한 subject의 재호출도 matched row 1이라 멱등 성공한다(값이 같아도 0행이 아니다).
     *
     * <p>쓰기 경로는 행을 만들지 않는다 — 0행은 마스터 쓰기와 같은 운영 신호라 조용히 넘기지 않고 던진다.
     */
    public void completeOnboarding(UUID subjectId) {
        if (subjectPreferenceRepository.markOnboardingCompleted(subjectId) == 0) {
            throw new IllegalStateException("subject preference row is missing");
        }
    }

    /**
     * worker의 마스터 batch 필터 — 행이 있는 subject의 상태만 담는다. 호출자는 결과에 없는 subject를
     * 마스터 누락으로 다루고 발송 대상에서 제외한다(추정 금지).
     */
    public Map<UUID, Boolean> findPushEnabledBySubjectIds(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        List<SubjectPreference> preferences = subjectPreferenceRepository.findAllBySubjectIdIn(subjectIds);
        return preferences.stream()
                .collect(Collectors.toMap(SubjectPreference::getSubjectId, SubjectPreference::isPushEnabled,
                        (first, second) -> first));
    }

    private LocalDateTime auditNow() {
        return PushTimes.kstWallClock(clock.instant());
    }
}
