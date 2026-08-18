package com.laimory.server.push.service;

import com.laimory.server.push.PushTimes;
import com.laimory.server.push.entity.PushPreference;
import com.laimory.server.push.repository.PushPreferenceRepository;
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
 * subject별 FCM 전체 수신 마스터의 단일 관문. 신규 행은 기본 ON이며 어떤 경로에서도 행이 없다는 이유로
 * 사용자의 기존 수신 상태가 바뀌지 않게 get-or-create로 수렴시킨다.
 *
 * <p>행 부재 해석은 두 갈래다. 기존 정보성 푸시(타임라인 완료)는 rollout 공백에서 기존 동작을 보존하려고
 * ON으로 읽고({@link #isPushEnabledForLegacyCompatibility}), 새 광고성 발송은 추정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PushPreferenceService {

    static final boolean DEFAULT_PUSH_ENABLED = true;

    private final PushPreferenceRepository pushPreferenceRepository;
    private final Clock clock;

    /** 가입 transaction 합류용 기본 ON 행 생성. 이미 있으면 no-op(멱등). */
    public void createDefaultIfAbsent(UUID subjectId) {
        pushPreferenceRepository.insertIfAbsent(subjectId.toString(), DEFAULT_PUSH_ENABLED, auditNow());
    }

    /** 설정 조회·변경 경로의 get-or-create — rollout 공백 행을 같은 request에서 멱등 보정한다. */
    @Transactional
    public boolean getOrCreatePushEnabled(UUID subjectId) {
        createDefaultIfAbsent(subjectId);
        return pushPreferenceRepository.findById(subjectId)
                .map(PushPreference::isPushEnabled)
                .orElse(DEFAULT_PUSH_ENABLED);
    }

    /** 마스터 ON/OFF 변경(멱등) — 종류별 설정값·시각은 보존한다. */
    @Transactional
    public void updatePushEnabled(UUID subjectId, boolean pushEnabled) {
        createDefaultIfAbsent(subjectId);
        pushPreferenceRepository.updatePushEnabled(subjectId, pushEnabled);
    }

    /**
     * 기존 타임라인 완료 푸시의 마스터 판정 — 행이 없으면 ON으로 읽는다(rollout 공백에서 기존 동작 보존).
     * DB 조회 장애는 여기서 ON으로 숨기지 않고 예외를 그대로 올려 호출자의 기존 실패 격리를 따른다.
     */
    public boolean isPushEnabledForLegacyCompatibility(UUID subjectId) {
        return pushPreferenceRepository.findById(subjectId)
                .map(PushPreference::isPushEnabled)
                .orElse(DEFAULT_PUSH_ENABLED);
    }

    /**
     * worker의 마스터 batch 필터 — 행이 있는 subject의 상태만 담는다. 호출자는 결과에 없는 subject를
     * 마스터 누락으로 다루고 광고성 발송에서는 제외한다(추정 금지).
     */
    public Map<UUID, Boolean> findPushEnabledBySubjectIds(Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Map.of();
        }
        List<PushPreference> preferences = pushPreferenceRepository.findAllBySubjectIdIn(subjectIds);
        return preferences.stream()
                .collect(Collectors.toMap(PushPreference::getSubjectId, PushPreference::isPushEnabled,
                        (first, second) -> first));
    }

    /** 탈퇴 transaction 합류용 — 종류별 행 삭제 뒤에 호출한다(FK RESTRICT). */
    public void deleteForSubject(UUID subjectId) {
        pushPreferenceRepository.deleteBySubjectId(subjectId);
    }

    private LocalDateTime auditNow() {
        return PushTimes.kstWallClock(clock.instant());
    }
}
