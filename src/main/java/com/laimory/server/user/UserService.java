package com.laimory.server.user;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** users leaf 서비스. 조회·갱신은 1:1인 UserRepository로, 신규 생성은 NewUserProvisioner로만 한다. */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final NewUserProvisioner newUserProvisioner;

    /**
     * (provider, providerUserId)로 사용자를 찾고 없으면 생성한다. Kakao 기존 사용자는 이번 로그인의
     * 닉네임으로 갱신한다 — 단 누락 claim(null)은 동의 철회인지 provider 응답 누락인지 구분할 수 없어
     * 기존 값을 지우지 않는다. Google 기존 사용자는 갱신 없이 반환한다(기존 동작 유지).
     *
     * <p>의도적으로 <b>무트랜잭션</b>이다: 동시 최초 로그인 레이스에서 한쪽 insert가 UNIQUE 위반으로 지는데,
     * 합류 트랜잭션 안에서 catch하면 트랜잭션이 rollback-only로 오염돼 같은 트랜잭션의 재조회가 무의미해진다
     * ({@code DailyRecordService.findOrCreateDraft} 주석 참고 — 같은 함정으로 옛 upsert를 폐기한 이력).
     * 여기선 생성이 {@link NewUserProvisioner#provision}의 자체 트랜잭션(user + subject mapping을 함께
     * commit/rollback)에서 실행되고 catch는 그 밖이라 재조회가 유효하다 — 패자의 provisioner transaction이
     * 통째로 rollback되므로 orphan subject mapping도 남지 않는다.
     * <b>호출자는 이 메서드를 둘러싼 트랜잭션 안에서 부르지 않는다.</b>
     */
    public User findOrCreate(Provider provider, String providerUserId, String email, String nickname) {
        return userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(existing -> refreshKakaoNickname(existing, provider, nickname))
                .orElseGet(() -> {
                    try {
                        return newUserProvisioner.provision(provider, providerUserId, email, nickname);
                    } catch (DataIntegrityViolationException e) {
                        // 동시 최초 로그인: 상대가 먼저 insert — 그 행으로 수렴하고 이번 닉네임을 적용한다.
                        return userRepository.findByProviderAndProviderUserId(provider, providerUserId)
                                .map(winner -> refreshKakaoNickname(winner, provider, nickname))
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * 인증 userId의 회원을 조회한다(내 회원 정보 응답 구성용). 유효하게 서명된 토큰이라도 회원 행이 없으면
     * 기존 401 계약({@code -2001})으로 수렴시켜 탈퇴 여부·내부 식별자 존재를 노출하지 않는다 —
     * userId는 예외 message·log에 넣지 않는다.
     */
    public User getProfile(String applicationVersion, Long userId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ExceptionType.API_AUTHENTICATION_REQUIRED));
    }

    private User refreshKakaoNickname(User user, Provider provider, String nickname) {
        if (provider != Provider.KAKAO || nickname == null) {
            return user;
        }
        user.updateNickname(nickname);
        return userRepository.saveAndFlush(user);
    }
}
