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
 * subject별 FCM 전체 수신 마스터의 단일 관문. 신규 행은 기본 ON이며, 행을 만드는 것은 가입
 * transaction과 rollout backfill뿐이다 — 조회는 기본값으로 답하고 쓰기는 던진다.
 *
 * <p>행 부재는 깨진 불변식이라 조회·쓰기 모두 던진다. 예외는 worker의 batch 조회 하나로, 거기서는
 * 한 행 때문에 batch 전체를 실패시키지 않고 결과에서 빠진 subject를 발송 대상에서 제외한다.
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

    /** 설정 화면이 보여줄 현재 값 — <b>순수 읽기</b>다. 행이 없으면 쓰기와 같은 이유로 던진다. */
    public boolean findPushEnabled(UUID subjectId) {
        return pushPreferenceRepository.findById(subjectId)
                .map(PushPreference::isPushEnabled)
                .orElseThrow(() -> new IllegalStateException("push preference row is missing"));
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
