package com.laimory.server.user.service;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.service.NotificationConsentService;
import com.laimory.server.push.service.PushPreferenceService;
import com.laimory.server.push.service.ScheduledNotificationPreferenceService;
import com.laimory.server.user.Provider;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 사용자 계정 생성의 DB 트랜잭션 경계 전담 빈(#282, 계획 §2.3 —
 * {@link com.laimory.server.timeline.service.TimelineSaveTransactionService}와 같은 형태).
 *
 * <p>{@code users} insert로 userId(IDENTITY)를 확보한 <b>같은 transaction</b>에서 subject mapping과
 * subject 축 기본 설정 행을 insert한다 — mapping insert·secret/HMAC 처리가 실패하면 user insert까지 함께 rollback되어 부분
 * user나 orphan subject를 남기지 않는다.
 *
 * <p>동시 최초 로그인의 UNIQUE 패배도 이 transaction 전체가 rollback된 뒤
 * {@link org.springframework.dao.DataIntegrityViolationException}으로 전파된다 — 바깥
 * {@link UserService#findOrCreate}의 무트랜잭션 catch-재조회가 그대로 유효한 이유다(패자의 user·mapping
 * 어느 쪽도 남지 않고, 승자는 자기 transaction에서 user와 mapping을 함께 commit했다).
 */
@Service
@RequiredArgsConstructor
public class NewUserProvisioner {

    private final UserRepository userRepository;
    private final SubjectMappingService subjectMappingService;
    private final PushPreferenceService pushPreferenceService;
    private final ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    private final NotificationConsentService notificationConsentService;

    /**
     * user, subject mapping, subject 축 기본 설정 행을 한 transaction에서 저장한다.
     * {@code saveAndFlush}로 insert를 즉시 flush해 UNIQUE 위반이 이 메서드 안에서 드러나게 한다
     * (commit 시점으로 미루지 않음).
     *
     * <p>푸시 마스터(ON)·일일 리마인더(OFF/21:00)·수신 동의(전부 미동의) 기본 행을 여기서 함께 만든다 —
     * 가입 직후부터 설정 조회·worker 스캔이 추정 없이 동작하고, 나중에 행을 만들어 주는 경로가 실패해도
     * 조용히 누락된 사용자가 생기지 않는다. 각 leaf service의 쓰기는 insert-if-absent라 재실행에 안전하다.
     */
    @Transactional
    public User provision(Provider provider, String providerUserId, String email, String nickname) {
        User user = userRepository.saveAndFlush(User.of(provider, providerUserId, email, nickname));
        UUID subjectId = subjectMappingService.createFor(user.getUserId());
        pushPreferenceService.createDefaultIfAbsent(subjectId);
        scheduledNotificationPreferenceService.createDefaultIfAbsent(
                subjectId, ScheduledNotificationType.DAILY_REMINDER);
        notificationConsentService.createDefaultIfAbsent(subjectId);
        return user;
    }
}
