package com.laimory.server.push.service;

import com.laimory.server.push.PushMetrics;
import com.laimory.server.push.PushTimes;
import com.laimory.server.push.entity.PushPreference;
import com.laimory.server.push.repository.PushPreferenceRepository;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * subject별 FCM 전체 수신 마스터의 단일 관문. 신규 행은 기본 ON이며 어떤 경로에서도 행이 없다는 이유로
 * 사용자의 기존 수신 상태가 바뀌지 않게 get-or-create로 수렴시킨다.
 *
 * <p>행 부재 해석은 두 갈래다. 기존 정보성 푸시(타임라인 완료)는 rollout 공백에서 기존 동작을 보존하려고
 * ON으로 읽고({@link #isPushEnabledForLegacyCompatibility}), 예정 알림 발송은 추정하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PushPreferenceService {

    static final boolean DEFAULT_PUSH_ENABLED = true;

    private final PushPreferenceRepository pushPreferenceRepository;
    private final PushMetrics pushMetrics;
    private final Clock clock;

    /** 가입 transaction 합류용 기본 ON 행 생성. 이미 있으면 no-op(멱등). */
    public void createDefaultIfAbsent(UUID subjectId) {
        pushPreferenceRepository.insertIfAbsent(subjectId.toString(), DEFAULT_PUSH_ENABLED, auditNow());
    }

    /**
     * 설정 화면이 보여줄 현재 값 — <b>순수 읽기</b>다. 행이 없으면 기본 ON을 답한다(그 행을 만들어도
     * 값이 같으므로 조회가 쓰기를 할 이유가 없다).
     */
    public boolean findPushEnabled(UUID subjectId) {
        return pushPreferenceRepository.findById(subjectId)
                .map(PushPreference::isPushEnabled)
                .orElse(DEFAULT_PUSH_ENABLED);
    }

    /**
     * 마스터 ON/OFF 변경(멱등) — 한 컬럼만 바꾸는 UPDATE 한 문장이다.
     *
     * <p>쓰기 경로는 행을 만들지 않는다. 행 존재는 가입 transaction과 rollout backfill이 보장하며,
     * 0행은 그 보장이 깨졌다는 운영 신호라 조용히 넘기지 않고 던진다.
     */
    public void updatePushEnabled(UUID subjectId, boolean pushEnabled) {
        if (pushPreferenceRepository.updatePushEnabled(subjectId, pushEnabled) == 0) {
            throw new IllegalStateException("push preference row is missing");
        }
    }

    /**
     * 기존 타임라인 완료 푸시의 마스터 판정 — 행이 없으면 ON으로 읽는다(rollout 공백에서 기존 동작 보존).
     * DB 조회 장애는 여기서 ON으로 숨기지 않고 예외를 그대로 올려 호출자의 기존 실패 격리를 따른다.
     *
     * <p>부재는 metric으로 관측만 한다 — 이 값이 0으로 수렴하지 않으면 backfill이 덜 끝난 것이다.
     * 여기서 행을 만들지는 않는다. 조회가 쓰기를 하지 않는다는 규칙을 이 경로에도 똑같이 적용한다.
     */
    public boolean isPushEnabledForLegacyCompatibility(UUID subjectId) {
        Optional<PushPreference> preference = pushPreferenceRepository.findById(subjectId);
        if (preference.isPresent()) {
            return preference.get().isPushEnabled();
        }
        pushMetrics.recordPreferenceMissing();
        return DEFAULT_PUSH_ENABLED;
    }

    /**
     * worker의 마스터 batch 필터 — 행이 있는 subject의 상태만 담는다. 호출자는 결과에 없는 subject를
     * 마스터 누락으로 다루고 발송 대상에서 제외한다(추정 금지).
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
